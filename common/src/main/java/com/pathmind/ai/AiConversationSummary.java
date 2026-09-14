package com.pathmind.ai;

import com.google.gson.JsonObject;
import java.util.List;

/** Advisory conversational continuity, never a graph snapshot or edit authorization. */
public record AiConversationSummary(String goal, List<String> decisions, List<String> unfinished) {
    public AiConversationSummary {
        goal = bounded(goal); decisions = checked(decisions); unfinished = checked(unfinished);
    }
    private static String bounded(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() > 800) throw new IllegalArgumentException("Continuity notes must be at most 800 characters each.");
        return text;
    }
    private static List<String> checked(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 8) throw new IllegalArgumentException("Continuity notes must contain at most eight items.");
        return values.stream().map(AiConversationSummary::bounded).filter(v -> !v.isBlank()).toList();
    }
    public static AiConversationSummary fromAction(JsonObject action) {
        if (!action.has("continuityGoal") && !action.has("continuityDecisions") && !action.has("continuityUnfinished")) return null;
        String goal = !action.has("continuityGoal") || action.get("continuityGoal").isJsonNull()
            ? "" : action.get("continuityGoal").getAsString();
        return new AiConversationSummary(goal, items(action, "continuityDecisions"), items(action, "continuityUnfinished"));
    }
    public static AiConversationSummary merge(AiConversationSummary previous, AiConversationSummary update) {
        if (update == null) return previous;
        String goal = update.goal().isBlank() && previous != null ? previous.goal() : update.goal();
        return new AiConversationSummary(goal, update.decisions(), update.unfinished());
    }
    private static List<String> items(JsonObject action, String key) {
        if (!action.has(key) || action.get(key).isJsonNull()) return List.of();
        var result = new java.util.ArrayList<String>();
        for (var item : action.getAsJsonArray(key)) result.add(item.getAsString());
        return result;
    }
    public String display() {
        StringBuilder text = new StringBuilder("Goal: ").append(goal);
        if (!decisions.isEmpty()) text.append("\n\nDecisions:\n- ").append(String.join("\n- ", decisions));
        if (!unfinished.isEmpty()) text.append("\n\nUnfinished:\n- ").append(String.join("\n- ", unfinished));
        return text.toString();
    }
}
