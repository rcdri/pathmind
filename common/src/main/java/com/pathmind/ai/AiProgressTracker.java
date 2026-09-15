package com.pathmind.ai;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Tracks information/graph progress across alternating actions, ignoring presentation text. */
final class AiProgressTracker {
    private final Set<String> observed = new HashSet<>();
    private final Set<String> recurringFailures = new HashSet<>();
    private int recurringFailureCount;
    private int revision = -1, stagnant;
    int record(String tool, JsonObject result, int currentRevision) {
        if (currentRevision != revision) { revision = currentRevision; observed.clear(); stagnant = 0; }
        JsonObject data = result == null ? new JsonObject() : result.deepCopy();
        for (String field : new String[]{"message", "guidance", "next", "planRevision", "repairRound", "draftRevision"}) data.remove(field);
        if (observed.add(tool + ":" + data)) stagnant = 0;
        else stagnant++;
        if ("validate_graph".equals(tool) && result != null && result.has("ok") && !result.get("ok").getAsBoolean()) {
            JsonObject failure = data.deepCopy();
            failure.remove("actualValues");
            if (!recurringFailures.add(failure.toString())) recurringFailureCount++;
            else recurringFailureCount = 0;
        } else if (result != null && result.has("ok") && result.get("ok").getAsBoolean()) {
            recurringFailures.clear(); recurringFailureCount = 0;
        }
        return Math.max(stagnant, recurringFailureCount);
    }
}
