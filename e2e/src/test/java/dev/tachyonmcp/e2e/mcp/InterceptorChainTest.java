/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.interceptor.McpInterceptor;
import dev.tachyonmcp.api.server.interceptor.McpInvocation;
import dev.tachyonmcp.api.server.interceptor.McpOutcome;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** E2E contract of the {@link McpInterceptor} seam, exercised through a real MCP client. */
class InterceptorChainTest extends AbstractMcpE2eTest {

    private static final String CALL_ECHO =
            // language=json
            """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}
            """;

    @Override
    protected SessionMode sessionMode() {
        return SessionMode.STATEFUL;
    }

    @Override
    protected void startDefaultServer() {
        startEmptyServer();
    }

    @Test
    void interceptorsRunOutermostFirstAndWrapTheHandler() throws Exception {
        var trace = new CopyOnWriteArrayList<String>();
        startServer(
                b -> b.withInterceptors(recorder(trace, "outer"), recorder(trace, "inner")),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> {
                    trace.add("handler");
                    return ToolResult.text("hi");
                }));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, CALL_ECHO);

            assertThatResponse(response).isSuccess().hasTextContent("hi");
            assertThat(trace).containsExactly("outer>", "inner>", "handler", "inner<", "outer<");
        }
    }

    @Test
    void interceptorSeesMethodTargetSessionAndParams() throws Exception {
        var seen = new CopyOnWriteArrayList<Observed>();
        startServer(
                b -> b.withInterceptors(observing(seen)),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.text("hi")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();
            assertThatResponse(client.post(sessionId, CALL_ECHO)).isSuccess();

            var toolCall = observationOf(seen, "tools/call");
            assertThat(toolCall.target()).isEqualTo("echo");
            assertThat(toolCall.resourceUri()).isNull();
            assertThat(toolCall.sessionId()).isEqualTo(sessionId);
            assertThat(toolCall.protocolVersion()).isEqualTo("2025-11-25");
            assertThat(toolCall.requestId()).isEqualTo("1");
            assertThat(toolCall.paramsJson()).contains("\"echo\"").contains("\"hi\"");
        }
    }

    @Test
    void initializeIsIntercepted() throws Exception {
        var seen = new CopyOnWriteArrayList<Observed>();
        startServer(b -> b.withInterceptors(observing(seen)), s -> {});

        try (var client = createTestClient()) {
            client.initialize();

            assertThat(observationOf(seen, "initialize").requestId()).isNotNull();
        }
    }

    @Test
    void notificationsAreInterceptedWithoutARequestId() throws Exception {
        var seen = new CopyOnWriteArrayList<Observed>();
        startServer(b -> b.withInterceptors(observing(seen)), s -> {});

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            // notifications are dispatched off the request thread — 202 returns before the chain runs
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> assertThat(seen).extracting(Observed::method).contains("notifications/initialized"));

            var notification = observationOf(seen, "notifications/initialized");
            assertThat(notification.requestId()).isNull();
            assertThat(notification.target()).isNull();
        }
    }

    @Test
    void interceptorMayShortCircuitWithAServerError() throws Exception {
        var handlerRan = new CopyOnWriteArrayList<String>();
        startServer(
                b -> b.withInterceptors((invocation, chain) -> "tools/call".equals(invocation.method())
                        ? chain.reject(new ServerError(ServerError.Kind.INVALID_REQUEST, "blocked by policy"))
                        : chain.proceed()),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> {
                    handlerRan.add("handler");
                    return ToolResult.text("hi");
                }));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, CALL_ECHO);

            assertThatResponse(response).isJsonRpcError().hasErrorCode(-32600).hasErrorMessage("blocked by policy");
            assertThat(handlerRan).isEmpty();
        }
    }

    @Test
    void aThrowingInterceptorBecomesAnInternalError() throws Exception {
        startServer(
                b -> b.withInterceptors((invocation, chain) -> {
                    if ("tools/call".equals(invocation.method())) {
                        throw new IllegalStateException("interceptor exploded");
                    }
                    return chain.proceed();
                }),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.text("hi")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, CALL_ECHO);

            assertThatResponse(response).isJsonRpcError().hasErrorCode(-32603);
            assertThat(response.body()).doesNotContain("interceptor exploded");
        }
    }

    @Test
    void aFailedStageFromAnInterceptorBecomesAnInternalError() throws Exception {
        startServer(
                b -> b.withInterceptors((invocation, chain) -> "tools/call".equals(invocation.method())
                        ? CompletableFuture.failedStage(new IOException("downstream unavailable"))
                        : chain.proceed()),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.text("hi")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, CALL_ECHO);

            assertThatResponse(response).isJsonRpcError().hasErrorCode(-32603);
            assertThat(response.body()).doesNotContain("downstream unavailable");
        }
    }

    @Test
    void anInterceptorMayDisableASingleMethod() throws Exception {
        startServer(
                b -> b.withInterceptors((invocation, chain) -> "tools/list".equals(invocation.method())
                        ? chain.reject(new ServerError(ServerError.Kind.METHOD_NOT_FOUND, "tool listing disabled"))
                        : chain.proceed()),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.text("hi")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var listed = client.post(sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
            assertThatResponse(listed).isJsonRpcError().hasErrorCode(-32601);

            // the rest of the surface is untouched
            assertThatResponse(client.post(sessionId, CALL_ECHO)).isSuccess();
        }
    }

    @Test
    void outcomeCarriesTheResolvedJsonRpcCode() throws Exception {
        var outcomes = new CopyOnWriteArrayList<McpOutcome>();
        startServer(
                b -> b.withInterceptors(
                        (invocation, chain) -> chain.proceed().whenComplete((outcome, error) -> outcomes.add(outcome))),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.text("hi")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"absent","arguments":{}}}
                """);
            assertThatResponse(response).isJsonRpcError().hasErrorCode(-32602);

            assertThat(outcomes)
                    .filteredOn(McpOutcome.Failure.class::isInstance)
                    .map(McpOutcome.Failure.class::cast)
                    .singleElement()
                    .satisfies(failure -> {
                        // the code the wire actually carried, resolved by the protocol codec
                        assertThat(failure.jsonRpcCode()).isEqualTo(-32602);
                        assertThat(failure.error().kind()).isEqualTo(ServerError.Kind.INVALID_PARAMS);
                    });
        }
    }

    @Test
    void aToolReportingIsErrorIsAPayloadFailureOnASuccessfulResponse() throws Exception {
        var outcomes = new CopyOnWriteArrayList<McpOutcome>();
        startServer(
                b -> b.withInterceptors((invocation, chain) -> chain.proceed().whenComplete((outcome, error) -> {
                    if ("tools/call".equals(invocation.method())) {
                        outcomes.add(outcome);
                    }
                })),
                s -> s.tools().register(t -> t.name("echo"), (ctx, request) -> ToolResult.error("nope")));

        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            // still a JSON-RPC success on the wire — the failure lives inside the payload
            assertThatResponse(client.post(sessionId, CALL_ECHO)).isSuccess().isToolError();

            assertThat(outcomes).singleElement().isInstanceOf(McpOutcome.PayloadFailure.class);
        }
    }

    /** Records entry and exit so ordering is observable from the outside. */
    private static McpInterceptor recorder(List<String> trace, String name) {
        return (invocation, chain) -> {
            if (!"tools/call".equals(invocation.method())) {
                return chain.proceed();
            }
            trace.add(name + ">");
            return chain.proceed().whenComplete((result, error) -> trace.add(name + "<"));
        };
    }

    /**
     * An {@link McpInvocation} is only valid during the interception, so the interceptor copies out
     * what the test asserts on rather than retaining the invocation.
     */
    private record Observed(
            String method,
            @Nullable String requestId,
            @Nullable String sessionId,
            String protocolVersion,
            @Nullable String target,
            @Nullable String resourceUri,
            @Nullable String paramsJson) {

        static Observed of(McpInvocation invocation) {
            return new Observed(
                    invocation.method(),
                    Objects.toString(invocation.requestId(), null),
                    invocation.sessionId(),
                    invocation.protocolVersion(),
                    invocation.targetName().orElse(null),
                    invocation.resourceUri().orElse(null),
                    invocation.params().map(JsonDocument::json).orElse(null));
        }
    }

    private static McpInterceptor observing(List<Observed> seen) {
        return (invocation, chain) -> {
            seen.add(Observed.of(invocation));
            return chain.proceed();
        };
    }

    private static Observed observationOf(List<Observed> seen, String method) {
        return seen.stream()
                .filter(observed -> method.equals(observed.method()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no interception recorded for " + method + ", saw " + seen));
    }
}
