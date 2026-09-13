package com.pathmind.ai;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded in-memory telemetry. Disk export is explicit in the eval runner. */
public final class AiUsageTelemetry {
    private static final ArrayDeque<AiRunMetrics> RECENT = new ArrayDeque<>();
    private AiUsageTelemetry() { }
    static synchronized void record(AiRunMetrics metrics) {
        RECENT.addLast(metrics);
        while (RECENT.size() > 100) RECENT.removeFirst();
    }
    public static synchronized List<AiRunMetrics> recent() { return List.copyOf(RECENT); }
}
