package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;

/** Corrections may replace a mistaken implementation plan, never erase typed outcome constraints. */
final class AiPlanCorrection {
    private AiPlanCorrection() { }
    static boolean preservesBehavior(JsonObject before, JsonObject after) {
        return signatures(before.getAsJsonArray("requirements"), false).equals(signatures(after.getAsJsonArray("requirements"), false))
            && signatures(before.getAsJsonArray("structuralRequirements"), true)
                .equals(signatures(after.getAsJsonArray("structuralRequirements"), true))
            && before.get("goal").equals(after.get("goal"));
    }
    private static java.util.List<String> signatures(JsonArray array, boolean structural) {
        var values = new ArrayList<String>();
        for (var element : array) {
            var item = element.getAsJsonObject().deepCopy();
            for (String key : new String[]{"ref", "toRef", "graphRef"}) item.remove(key);
            if (structural) for (String key : new String[]{"slotIndex", "outputSocket", "inputSocket"}) item.remove(key);
            // Canonical property ordering makes transport field order irrelevant.
            var sorted = new JsonObject();
            item.keySet().stream().sorted().forEach(key -> sorted.add(key, item.get(key)));
            values.add(sorted.toString());
        }
        Collections.sort(values);
        return values;
    }
}
