package com.pathmind.ai;

/** Normalized native function call (or fallback action) and provider-reported usage. */
public record AiModelTurn(String action, AiTokenUsage usage) {
    public AiModelTurn {
        if (usage == null) usage = AiTokenUsage.UNKNOWN;
    }
}
