package com.pathmind.ai;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Tracks information/graph progress across alternating actions, ignoring presentation text. */
final class AiProgressTracker {
    private final Set<String> observed = new HashSet<>();
    private int revision = -1, stagnant;
    int record(String tool, JsonObject result, int currentRevision) {
        if (currentRevision != revision) { revision = currentRevision; observed.clear(); stagnant = 0; }
        JsonObject data = result == null ? new JsonObject() : result.deepCopy();
        for (String field : new String[]{"message", "guidance", "next", "planRevision", "repairRound", "draftRevision"}) data.remove(field);
        if (observed.add(tool + ":" + data)) stagnant = 0;
        else stagnant++;
        return stagnant;
    }
}
