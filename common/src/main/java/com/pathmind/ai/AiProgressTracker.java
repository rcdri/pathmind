package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Tracks information/graph progress across alternating actions, ignoring presentation text. */
final class AiProgressTracker {
    private final Set<String> observed = new HashSet<>();
    private String lastValidationIssues = "";
    private int repeatedValidationCount;
    private int revision = -1, stagnant;
    int record(String tool, JsonObject result, int currentRevision) {
        if (currentRevision != revision) { revision = currentRevision; observed.clear(); stagnant = 0; }
        JsonObject data = result == null ? new JsonObject() : result.deepCopy();
        for (String field : new String[]{"message", "guidance", "next", "planRevision", "repairRound", "draftRevision"}) data.remove(field);
        if (observed.add(tool + ":" + data)) stagnant = 0;
        else stagnant++;
        if ("validate_graph".equals(tool) && result != null && result.has("ok")) {
            if (result.get("ok").getAsBoolean()) { lastValidationIssues = ""; repeatedValidationCount = 0; }
            else {
                String issues = issueSignature(result);
                repeatedValidationCount = issues.equals(lastValidationIssues) ? repeatedValidationCount + 1 : 0;
                lastValidationIssues = issues;
            }
        }
        return stagnant;
    }
    int repeatedValidationCount() { return repeatedValidationCount; }
    private static String issueSignature(JsonObject result) {
        var values = new ArrayList<String>();
        if (result.has("issues") && result.get("issues").isJsonArray()) for (JsonElement value : result.getAsJsonArray("issues")) {
            if (!value.isJsonObject()) continue;
            var issue = value.getAsJsonObject();
            values.add(field(issue, "code") + ":" + field(issue, "nodeId") + ":" + field(issue, "routineId")
                + ":" + field(issue, "severity") + ":" + field(issue, "expected"));
        }
        if (values.isEmpty()) values.add(field(result, "code"));
        Collections.sort(values);
        return values.toString();
    }
    private static String field(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).toString() : "";
    }
}
