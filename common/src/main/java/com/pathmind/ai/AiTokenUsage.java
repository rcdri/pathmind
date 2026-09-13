package com.pathmind.ai;

import com.google.gson.JsonObject;
import java.util.List;

/** Null means unavailable, never zero. Input includes cache reads and writes. Output includes reasoning. */
public record AiTokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long cacheWriteTokens) {
    public static final AiTokenUsage UNKNOWN = new AiTokenUsage(null, null, null, null);

    static Long number(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : null;
    }

    public static AiTokenUsage total(List<AiTokenUsage> usages) {
        if (usages.isEmpty()) return UNKNOWN;
        return new AiTokenUsage(sum(usages, 0), sum(usages, 1), sum(usages, 2), sum(usages, 3));
    }

    private static Long sum(List<AiTokenUsage> usages, int field) {
        long total = 0;
        for (AiTokenUsage usage : usages) {
            Long value = switch (field) {
                case 0 -> usage.inputTokens();
                case 1 -> usage.outputTokens();
                case 2 -> usage.cachedInputTokens();
                default -> usage.cacheWriteTokens();
            };
            if (value == null) return null;
            total += value;
        }
        return total;
    }
}
