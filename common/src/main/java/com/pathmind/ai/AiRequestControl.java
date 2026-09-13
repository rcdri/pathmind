package com.pathmind.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Explicit cancellation and application-authored progress, owned by one user request. */
public final class AiRequestControl {
    private final Consumer<AiRequestProgress> observer;
    private final List<Runnable> cancellationHandlers = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile AiConversationSummary summary;
    public AiConversationSummary summary() { return summary; }
    void summary(AiConversationSummary summary) { if (!cancelled) this.summary = summary; }
    public AiRequestControl() { this(progress -> { }); }
    public AiRequestControl(Consumer<AiRequestProgress> observer) { this.observer = observer; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() {
        List<Runnable> handlers;
        synchronized (this) { if (cancelled) return; cancelled = true; handlers = List.copyOf(cancellationHandlers); cancellationHandlers.clear(); }
        handlers.forEach(Runnable::run);
    }
    void onCancel(Runnable handler) {
        synchronized (this) { if (!cancelled) { cancellationHandlers.add(handler); return; } }
        handler.run();
    }
    synchronized void release() { cancellationHandlers.clear(); }
    void publish(AiRequestProgress progress) {
        if (cancelled && progress.stage() != AiRequestProgress.Stage.CANCELLED) return;
        try { observer.accept(progress); } catch (RuntimeException ignored) { /* UI failures cannot retry graph operations. */ }
    }
}
