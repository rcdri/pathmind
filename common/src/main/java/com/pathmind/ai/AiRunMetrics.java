package com.pathmind.ai;

import java.util.Map;

/** Numeric telemetry only: no prompts, graph contents, keys, or provider reasoning. */
public record AiRunMetrics(String provider, String model, int toolTurns, int repairAttempts,
                           int validationFailures, int toolErrors, long latencyMillis,
                           AiTokenUsage usage, Map<String, Integer> toolCalls) {
    public AiRunMetrics {
        toolCalls = Map.copyOf(toolCalls);
    }

    /** Rates are supplied by the operator per million tokens, not guessed from a model name. */
    public Double estimatedCost(AiPricing pricing) {
        if (pricing == null || usage.inputTokens() == null || usage.outputTokens() == null
            || usage.cachedInputTokens() == null || usage.cacheWriteTokens() == null) return null;
        long uncached = Math.max(0, usage.inputTokens() - usage.cachedInputTokens() - usage.cacheWriteTokens());
        return (uncached * pricing.inputPerMillion() + usage.cachedInputTokens() * pricing.cachedInputPerMillion()
            + usage.cacheWriteTokens() * pricing.cacheWritePerMillion()
            + usage.outputTokens() * pricing.outputPerMillion()) / 1_000_000.0;
    }

    public record AiPricing(double inputPerMillion, double cachedInputPerMillion,
                            double cacheWritePerMillion, double outputPerMillion) {
        public AiPricing {
            for (double rate : new double[] {inputPerMillion, cachedInputPerMillion, cacheWritePerMillion, outputPerMillion}) {
                if (!Double.isFinite(rate) || rate < 0) throw new IllegalArgumentException("Token rates must be finite and non-negative.");
            }
        }
    }
}
