package com.pathmind.ai;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded local diagnostic summaries, not persisted provider transcripts. */
public final class AiRequestDiagnostics {
    public record Run(AiRunMetrics metrics, AiRequestIntent intent, AiCompletionOutcome outcome, List<AiToolTrace> trace) {
        public Run { trace = List.copyOf(trace); }
    }
    private static final ArrayDeque<Run> RECENT = new ArrayDeque<>();
    private AiRequestDiagnostics() { }
    static synchronized void record(Run run) { RECENT.addLast(run); while (RECENT.size() > 20) RECENT.removeFirst(); }
    public static synchronized List<Run> recent() { return List.copyOf(RECENT); }
}
