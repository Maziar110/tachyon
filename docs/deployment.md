# Deployment — Tachyon MCP Server

A Tachyon server is a plain Java process with an embedded Netty listener, so it
runs anywhere that runs a JVM or a container. Three settings usually change when
it moves off a developer machine.

Full option reference: [configuration](configuration.md). Stateless mode,
long-running tools and shutdown behaviour: [FAQ](faq.md#deployment-and-operations).

## 1. Bind address

`host` defaults to `127.0.0.1`. A platform routes to the process from outside,
so the server has to listen on every interface.

```java
.network(n -> n.host("0.0.0.0"))
```

## 2. Port

Most platforms assign the port and pass it in the environment. Read it there
instead of hard-coding one.

```java
.network(n -> n.port(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"))))
```

## 3. Public hostname

This is the one that is easy to miss.

[DNS-rebinding protection](configuration.md#dns-rebinding-protection) is on by
default, and it accepts only `localhost` and loopback authorities. A public
hostname is neither. Until `allowedHosts` names it, the server answers
`403 Forbidden` to every request that arrives through that hostname.

```java
.network(n -> {
    var allowedHost = System.getenv("ALLOWED_HOST");
    if (allowedHost != null && !allowedHost.isBlank()) {
        n.allowedHosts(allowedHost);
    }
})
```

Entries are bare authorities, not URLs. `example.com` matches that host on any
port, `example.com:8096` only that port.

Most platforms do not reveal the hostname until the app exists, so deployment is
two passes: deploy, read the assigned hostname, set it, deploy again.

### Verify the guard is on

A successful request does not prove the allowlist works, because an unset
allowlist and a correct one both let a good request through on localhost. Send a
request the server has to refuse. A trailing dot is the same host to DNS but a
different string to the guard:

```shell
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://YOUR-HOST/mcp
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Host: YOUR-HOST.' https://YOUR-HOST/mcp
```

The second must return `403`. If both return the same code, the `Host` check is
not filtering anything and the first result proved nothing.

## Browser clients

`allowedHosts` widens the `Host` check only. A request carrying an `Origin`
header that is not loopback is still rejected, so a browser page cannot reach a
remote Tachyon server. Clients that send no `Origin` are unaffected, which is
most MCP clients.

## More than one instance

Sessions are off by default, and a stateless server scales horizontally with no
sticky routing. A server that sets `session.enabled(true)` keeps sessions and
events in memory, so more than one instance needs sticky routing or shared
`SessionStore` and `SessionEventStore` implementations. See
[session configuration](configuration.md#session).

## Containers

- The JVM has to be PID 1, or it never receives the platform's stop signal and
  `shutdownGracePeriod` never runs. Use the exec form of `CMD`, or `exec` the
  JVM from an entrypoint script.
- The filesystem is ephemeral on most platforms. Anything written at runtime is
  gone after the next deploy.

## Worked example

[`examples/weather-mcp`](../examples/weather-mcp) reads `HOST`, `PORT` and
`ALLOWED_HOST` from the environment, so it needs no code change to run remotely.

One deployment of it, on [Dockhold](https://dockhold.eu):
[tachyon-weather-dockhold](https://github.com/Maziar110/tachyon-weather-dockhold).
That repo is a Dockerfile which fetches a tagged Tachyon release, builds this one
example, and runs it on a trimmed `jlink` runtime. It sets `HOST=0.0.0.0` and
leaves `ALLOWED_HOST` for the second pass described above. Deploy form:

```
https://app.dockhold.eu/new?repo=https://github.com/Maziar110/tachyon-weather-dockhold
```

Set `ALLOWED_HOST` to the hostname the platform assigns, deploy again, then run
the two `curl` checks above. Platform limits and pricing are documented at
[dockhold.eu](https://dockhold.eu).
