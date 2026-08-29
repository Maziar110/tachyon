# Observability

Tachyon has one cross-cutting seam: `McpInterceptor`. It wraps **every** inbound MCP operation —
`initialize`, every request, every notification — and is where tracing, auditing, authorization and
rate limiting belong. There is deliberately no second per-request hook type.

## The interceptor SPI

```java
package dev.tachyonmcp.api.server.interceptor;

public interface McpInterceptor {
    CompletionStage<McpOutcome> intercept(McpInvocation invocation, Chain chain);

    interface Chain {
        CompletionStage<McpOutcome> proceed();

        /** Short-circuits; the wire code is resolved for you. */
        CompletionStage<McpOutcome> reject(ServerError error);
    }
}

public sealed interface McpOutcome {
    record Success(@Nullable Object result) implements McpOutcome {}
    record PayloadFailure(@Nullable Object result) implements McpOutcome {}
    record Failure(ServerError error, int jsonRpcCode) implements McpOutcome {}
}
```

`McpOutcome` is what the dispatcher produces *after* resolving the result against the negotiated
protocol version, so an interceptor sees what the wire will actually carry:

| Case | Meaning |
|---|---|
| `Success` | the response carries the handler's result |
| `PayloadFailure` | JSON-RPC success, but the payload reports failure — today a `tools/call` with `isError: true` |
| `Failure` | JSON-RPC error; `jsonRpcCode` is this protocol version's code |

🔴 Never re-derive a JSON-RPC code from `ServerError.Kind`. MCP 2025-11-25 and 2026-07-28 map
several kinds differently (`RESOURCE_NOT_FOUND` is `-32002` on one and `-32602` on the other), which
is exactly why `Failure` carries the resolved code and `Chain.reject` resolves it for you.

`McpInvocation` describes the operation:

| Method | Value |
|---|---|
| `method()` | `tools/call`, `initialize`, `notifications/cancelled`, … |
| `requestId()` | JSON-RPC id, or `null` for a notification |
| `sessionId()` | MCP session id, or `null` in stateless mode |
| `protocolVersion()` | negotiated version, e.g. `2025-11-25` |
| `targetName()` | top-level `name` param — the tool or prompt being called |
| `resourceUri()` | top-level `uri` param — `resources/read` and friends |
| `params()` | raw wire params as a `JsonDocument`, encoded lazily on first call |
| `context()` | the handler-facing `InteractionContext` |

### Registration

```java
var server = TachyonServer.builder()
        .withInterceptors(tracing, audit)   // tracing is the outermost
        .build();
```

Repeated calls append. There is no `ServiceLoader` discovery: the application owns the order, and
no dependency can silently insert itself into the chain.

Kotlin:

```kotlin
TachyonServer(port = 8080) {
    interceptors(tracing, audit)
}
```

### Contract

- **One instance serves every concurrent operation.** Implementations must be thread-safe and keep
  no per-request state in fields — keep it in local variables and close over it in the returned
  stage. 🔴 Not in `invocation.context()`: that attribute space is scoped to the *connection*, so
  concurrent requests on one connection share it.
- `intercept` runs on the handler's virtual thread. Blocking for I/O is fine; `synchronized` is not
  (it pins the carrier thread) — use `ReentrantLock`.
- Call `chain.proceed()` from the `intercept` thread. The stage it returns may complete on another.
  🔴 Deferring `proceed()` to another thread detaches the handler from the dispatch's outbound
  stream, silently dropping any progress notification the handler sends. Delay the *response*, never
  the call to `proceed()`.
- An `McpInvocation` is valid only during the interception; `sessionId()` reads through the live
  dispatch. Copy out what you need rather than retaining it.
- Returning `chain.reject(error)` instead of `chain.proceed()` short-circuits the handler — the
  authorization and rate-limiting path.
- Errors travel as a failed `CompletionStage`. An exception thrown out of `intercept` is mapped to
  a JSON-RPC internal error, exactly as a throwing handler is.

Implement `McpInterceptor` and `Chain`; do **not** implement `McpInvocation` — it gains methods
between releases.

## OpenTelemetry

`integrations/tachyon-otel` implements the
[OpenTelemetry MCP semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp),
against `opentelemetry-semconv-incubating`.

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-otel</artifactId>
</dependency>
```

```java
var server = TachyonServer.builder()
        .withInterceptors(McpTelemetryInterceptor.create(GlobalOpenTelemetry.get()))
        .withTools(tools -> tools.register(t -> t.name("forecast"), (ctx, req) -> forecast(req)))
        .build();
```

It emits one `SERVER` span per operation, named `{mcp.method.name} {target}` (`tools/call forecast`),
plus the `mcp.server.operation.duration` histogram in seconds.

| Attribute | Where |
|---|---|
| `mcp.method.name`, `mcp.protocol.version`, `network.transport`, `network.protocol.name` | span + metric |
| `gen_ai.tool.name`, `gen_ai.operation.name=execute_tool` (on `tools/call`) | span + metric |
| `gen_ai.prompt.name` (on `prompts/get`) | span + metric |
| `error.type`, `rpc.response.status_code` (a **string**, per semconv) | span + metric |
| `mcp.session.id`, `jsonrpc.request.id`, `mcp.resource.uri` | span only |
| `gen_ai.tool.call.arguments` | span only, **opt-in** |

Session and request ids are kept off the histogram on purpose — one time series per request would
melt a metrics backend.

### About the semconv constants

`jsonrpc.request.id`, `rpc.response.status_code`, `error.type` and the `network.*` keys come from
the generated `opentelemetry-semconv` / `-incubating` constants.

The `mcp.*` and `gen_ai.*` keys do not: the incubating artifact **deprecated** them with *"Moved to
the OpenTelemetry GenAI semantic conventions repository"*, and that repository publishes no Java
artifact — `io.opentelemetry.semconv` still ships only `opentelemetry-semconv` and
`opentelemetry-semconv-incubating`. Importing constants that are deprecated with nowhere to go
means warnings now and a broken build when OTel removes them, so those keys are declared in
`McpAttributes` instead, the way OpenTelemetry's own instrumentation libraries handle unstable
conventions. That class is deleted the day a GenAI semconv Java artifact ships.

### Span status

Only genuine server faults set the span status to `ERROR`. The conventions list the JSON-RPC codes a
server returns because the *caller* sent something it could not serve — `-32700`, `-32600`, `-32601`,
`-32602`, `-32002` — and those leave the status `UNSET` while still recording
`rpc.response.status_code`.

### Trace context

Spans are parented by `Context.current()`.

- **With the OpenTelemetry Java agent** (`-javaagent:opentelemetry-javaagent.jar`): the agent
  instruments Netty and the JDK executors, so the incoming `traceparent` header is extracted, an
  HTTP `SERVER` span is created, and the context survives Tachyon's async hops. MCP spans appear as
  children of the HTTP span with no extra configuration. **This is the supported setup.**
- **SDK only, no agent**: MCP spans are roots. This interceptor does not read HTTP headers itself —
  adding a Netty library instrumentation through `pipelineCustomizer` is not enough either, because
  library mode does not propagate context across the executor hop.

### Security

`gen_ai.tool.call.arguments` is **off by default**. Tool arguments routinely carry credentials and
personal data, and spans are commonly exported to third-party backends. Enable it deliberately:

```java
McpTelemetryInterceptor.builder(GlobalOpenTelemetry.get())
        .recordPayloads(true)
        .build();
```

Tool *results* are never recorded — rendering a `ToolResult` as a span attribute would mean
serializing a domain object outside the server's configured payload serializer.

### Not covered yet

| Gap | Why |
|---|---|
| W3C `traceparent` extraction without the agent | inbound HTTP headers do not reach the dispatcher |
| `mcp.server.session.duration` | needs a session open/close hook, not a per-request seam |
| `client.address` / `client.port` | not exposed on the channel context |
| Outbound `mcp.client` spans (`sampling/createMessage`, `elicitation/create`) | server→client requests do not pass through the chain |

## Auditing

Auditing needs no separate API — it is an interceptor:

```java
final class AuditInterceptor implements McpInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    @Override
    public CompletionStage<Object> intercept(McpInvocation invocation, Chain chain) {
        // Copied up front: the invocation is only valid for the duration of the interception.
        final var method = invocation.method();
        final var target = invocation.targetName().orElse("-");
        final var requestId = invocation.requestId();
        final var sessionId = invocation.sessionId();
        final var startNanos = System.nanoTime();

        return chain.proceed().whenComplete((outcome, error) -> log.info(
                "mcp method={} target={} id={} session={} outcome={} ms={}",
                method,
                target,
                requestId,
                sessionId,
                error != null ? "failed" : describe(outcome),
                (System.nanoTime() - startNanos) / 1_000_000));
    }

    private static String describe(McpOutcome outcome) {
        return switch (outcome) {
            case McpOutcome.Success ignored -> "ok";
            case McpOutcome.PayloadFailure ignored -> "tool_error";
            case McpOutcome.Failure f -> f.error().kind() + "/" + f.jsonRpcCode();
        };
    }
}
```

`invocation.params()` gives the raw request JSON when the audit trail needs the payload. Treat it as
confidential — log it at `TRACE`, or redact before writing. See the
[logging policy](https://kpavlov.me/blog/logging-policy/).

## Slow request logging

Independent of interceptors, the server can warn about handlers that exceed a threshold:

```java
TachyonServer.builder().monitoring(m -> m.slowRequestLogging().slowRequestThreshold(Duration.ofSeconds(5)));
```
