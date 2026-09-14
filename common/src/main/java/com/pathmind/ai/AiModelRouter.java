package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import java.util.Locale;

/** Small, deterministic router. Explicit model choices always win over automatic routing. */
public final class AiModelRouter {
    public static final String AUTOMATIC = "Auto";
    static final String OPENAI_FAST = "gpt-5.4-mini";
    static final String OPENAI_STRONG = "gpt-5.5";

    private AiModelRouter() { }

    public static String resolve(AiProviderType provider, String configured, String prompt, NodeGraphData graph) {
        String selected = configured == null ? "" : configured.strip();
        if (!selected.isBlank() && !AUTOMATIC.equalsIgnoreCase(selected)) return selected;
        if (provider != AiProviderType.OPENAI) return provider.defaultModel();
        return isComplex(prompt, graph) ? OPENAI_STRONG : OPENAI_FAST;
    }

    static boolean isComplex(String prompt, NodeGraphData graph) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        int score = text.length() > 180 ? 1 : 0;
        int nodes = graph == null || graph.getNodes() == null ? 0 : graph.getNodes().size();
        if (nodes >= 8) score += 2;
        else if (nodes >= 4) score++;
        if (graph != null && graph.getRoutines() != null && !graph.getRoutines().isEmpty()) score += 2;
        if (containsAny(text, "routine", "argument", "parameter attachment")) score += 2;
        if (containsAny(text, "variable", "list", "save my position", "current position", "inventory")) score++;
        if (containsAny(text, "condition", "if/else", "if else", "branch", "repeat until", "fork", "nested")) score++;
        if (containsAny(text, "pathfind", "return to", "travel back", "collect", "craft")) score++;
        int transitions = occurrences(text, " then ") + occurrences(text, " after ") + occurrences(text, " before ");
        if (transitions >= 2) score++;
        return score >= 2;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static int occurrences(String text, String term) {
        int count = 0, from = 0;
        while ((from = text.indexOf(term, from)) >= 0) { count++; from += term.length(); }
        return count;
    }
}
