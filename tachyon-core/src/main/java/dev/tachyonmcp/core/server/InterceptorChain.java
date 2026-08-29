/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.interceptor.McpInterceptor;
import dev.tachyonmcp.api.server.interceptor.McpInvocation;
import dev.tachyonmcp.api.server.interceptor.McpOutcome;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Immutable node in the {@link McpInterceptor} chain: each {@link #proceed()} builds the node for
 * the next interceptor rather than advancing a shared cursor, so an interceptor may call {@code
 * proceed()} more than once (retry) and two threads never race on the position.
 */
@InternalApi
final class InterceptorChain implements McpInterceptor.Chain {

    private final List<McpInterceptor> interceptors;
    private final int index;
    private final McpInvocation invocation;
    private final DispatchContext context;
    private final Supplier<CompletionStage<McpOutcome>> terminal;

    private InterceptorChain(
            List<McpInterceptor> interceptors,
            int index,
            McpInvocation invocation,
            DispatchContext context,
            Supplier<CompletionStage<McpOutcome>> terminal) {
        this.interceptors = interceptors;
        this.index = index;
        this.invocation = invocation;
        this.context = context;
        this.terminal = terminal;
    }

    /**
     * Runs {@code invocation} through every interceptor, outermost first, ending in {@code
     * terminal}. Callers must check for an empty {@code interceptors} list first — the
     * zero-interceptor path is meant to allocate nothing.
     */
    static CompletionStage<McpOutcome> run(
            List<McpInterceptor> interceptors,
            McpInvocation invocation,
            DispatchContext context,
            Supplier<CompletionStage<McpOutcome>> terminal) {
        return new InterceptorChain(interceptors, 0, invocation, context, terminal).proceed();
    }

    @Override
    public CompletionStage<McpOutcome> proceed() {
        if (index == interceptors.size()) {
            return terminal.get();
        }
        final var interceptor = interceptors.get(index);
        try {
            final var next = new InterceptorChain(interceptors, index + 1, invocation, context, terminal);
            return Objects.requireNonNull(
                    interceptor.intercept(invocation, next),
                    () -> interceptor.getClass().getName() + ".intercept returned null");
        } catch (Exception e) {
            // Same failure channel as a throwing handler, so McpDispatcher maps it to -32603.
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<McpOutcome> reject(ServerError error) {
        Objects.requireNonNull(error, "error");
        return CompletableFuture.<McpOutcome>completedStage(McpOutcomes.failure(error, context));
    }
}
