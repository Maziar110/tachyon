/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.interceptor;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.ServerError;
import java.util.concurrent.CompletionStage;

/**
 * Around-advice over one inbound MCP request or notification — the server's single cross-cutting
 * seam. Register with {@code ServerBuilder.withInterceptors(...)}; the first interceptor registered
 * is the outermost.
 *
 * <p>Every dispatched operation passes through the chain, including {@code initialize} and
 * notifications. A typical interceptor measures, records and forwards:
 *
 * <pre>{@code
 * final class TimingInterceptor implements McpInterceptor {
 *     public CompletionStage<McpOutcome> intercept(McpInvocation invocation, Chain chain) {
 *         final var method = invocation.method();          // copied out, not retained
 *         final var startNanos = System.nanoTime();
 *         return chain.proceed()
 *                 .whenComplete((outcome, error) -> record(method, System.nanoTime() - startNanos));
 *     }
 * }
 * }</pre>
 *
 * <p>An interceptor that inspects what happened switches over the {@link McpOutcome}, which the
 * dispatcher has already resolved against the negotiated protocol version:
 *
 * <pre>{@code
 * switch (outcome) {
 *     case McpOutcome.Success s -> {}
 *     case McpOutcome.PayloadFailure p -> countToolError();
 *     case McpOutcome.Failure f -> count(f.jsonRpcCode(), f.error().kind());
 * }
 * }</pre>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><strong>One instance serves every concurrent operation.</strong> Implementations must be
 *       thread-safe and must not hold per-request state in fields. Keep it in local variables and
 *       close over it in the returned stage, as the timing example above does — <em>not</em> in
 *       {@link McpInvocation#context()}, whose attribute space is shared by every request on the
 *       connection.
 *   <li>Do not retain the {@link McpInvocation} beyond the returned stage; see its javadoc.
 *   <li>{@link #intercept} runs on the handler's <b>virtual thread</b>. Blocking for I/O is the
 *       intended contract, but never use {@code synchronized}, native methods, or anything else
 *       that pins the carrier thread; prefer {@link java.util.concurrent.locks.ReentrantLock}.
 *   <li>{@link Chain#proceed()} must be called from the {@link #intercept} thread. The stage it
 *       returns may complete on a different one. Handing {@code proceed()} to another thread or
 *       deferring it past the return of {@link #intercept} detaches the handler from the dispatch's
 *       outbound stream binding, and progress notifications sent by the handler are silently
 *       dropped — throttle by delaying the <em>response</em>, never the call to {@code proceed()}.
 *   <li>Returning {@link Chain#reject(ServerError)} instead of {@link Chain#proceed()}
 *       short-circuits the handler — the authorization and rate-limiting use case.
 *   <li>Errors travel as a failed {@link CompletionStage}; an exception thrown out of {@link
 *       #intercept} is mapped to a JSON-RPC internal error, exactly as a throwing handler is.
 * </ul>
 *
 * @author Konstantin Pavlov
 */
@ExperimentalApi
@FunctionalInterface
public interface McpInterceptor {

    /**
     * Wraps the dispatch of one MCP operation.
     *
     * @param invocation the operation being dispatched
     * @param chain      continuation to the next interceptor, or to the handler
     * @return a stage yielding the outcome, which an interceptor may substitute
     */
    CompletionStage<McpOutcome> intercept(McpInvocation invocation, Chain chain);

    /**
     * Continuation handed to an {@link McpInterceptor}: invokes the next interceptor in the chain,
     * or the method handler when this is the innermost one.
     *
     * <p>Implemented by Tachyon; application code implements it only in tests.
     */
    interface Chain {

        /**
         * Proceeds to the next interceptor or to the handler.
         *
         * @return a stage yielding the outcome, failed if the remainder of the chain failed
         */
        CompletionStage<McpOutcome> proceed();

        /**
         * Short-circuits the dispatch with a JSON-RPC error, without invoking the handler.
         *
         * <p>Resolves the wire code for the protocol version in play, so callers never hand-write
         * one — two MCP versions encode the same {@link ServerError.Kind} differently.
         *
         * @param error the error to answer with
         * @return a completed stage carrying the corresponding {@link McpOutcome.Failure}
         */
        CompletionStage<McpOutcome> reject(ServerError error);
    }
}
