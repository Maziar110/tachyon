/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.interceptor;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.ServerError;
import org.jspecify.annotations.Nullable;

/**
 * What one MCP operation produced, already resolved against the negotiated protocol version.
 *
 * <p>An {@link McpInterceptor} observes the outcome instead of the raw handler result, because the
 * facts an interceptor needs — above all the JSON-RPC error code the response will carry — are
 * decided by the protocol codec, which runs after the handler. Resolving here keeps that mapping in
 * the one place that owns it: two MCP versions encode the same {@link ServerError.Kind}
 * differently, so any code that re-derived it would be wrong on one of them.
 *
 * @author Konstantin Pavlov
 */
@ExperimentalApi
public sealed interface McpOutcome {

    /**
     * The handler produced a result and the JSON-RPC response carries it.
     *
     * @param result the handler's result, already mapped to its wire shape; {@code null} for
     *               notifications, which have no response
     */
    record Success(@Nullable Object result) implements McpOutcome {}

    /**
     * The JSON-RPC call succeeded, but the result payload itself reports a failure — today only a
     * {@code tools/call} whose {@code CallToolResult} carries {@code isError: true}.
     *
     * <p>Still a success on the wire: the client receives a {@code result}, not an {@code error}.
     * The distinction exists for observability and policy, which need to tell "the tool ran and
     * said no" apart from "the tool ran and said yes".
     *
     * @param result the handler's result, already mapped to its wire shape
     */
    record PayloadFailure(@Nullable Object result) implements McpOutcome {}

    /**
     * The operation failed and the response is a JSON-RPC error envelope.
     *
     * <p>Prefer {@link McpInterceptor.Chain#reject(ServerError)} over constructing this directly —
     * it resolves {@code jsonRpcCode} for the protocol version in play, which is not something
     * calling code should have to know.
     *
     * @param error       the protocol-neutral error
     * @param jsonRpcCode the code this protocol version puts on the wire for {@code error}
     */
    record Failure(ServerError error, int jsonRpcCode) implements McpOutcome {}
}
