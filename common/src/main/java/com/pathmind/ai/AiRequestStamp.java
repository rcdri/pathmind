package com.pathmind.ai;

/** Shared callback guard for reset, provider switch, and request cancellation. */
public record AiRequestStamp(AiProviderType provider, int localGeneration, long historyGeneration) {
    public static AiRequestStamp capture(AiProviderType provider, int localGeneration, AiChatHistoryStore store) {
        return new AiRequestStamp(provider, localGeneration, store.generation(provider));
    }
    public boolean isCurrent(AiProviderType currentProvider, int currentGeneration, AiChatHistoryStore store) {
        return provider == currentProvider && localGeneration == currentGeneration && historyGeneration == store.generation(provider);
    }
}
