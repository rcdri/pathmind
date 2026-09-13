package com.pathmind.ai;

import java.util.concurrent.CompletableFuture;

public interface AiProvider {
    default AiProviderCapabilities capabilities() {
        return AiProviderCapabilities.TEXT_ONLY;
    }

    CompletableFuture<String> generate(AiPresetRequest request);

    default String providerId() { return getClass().getSimpleName(); }

    default AiProviderSession openSession() {
        return (request, previousToolResult) -> generate(request).thenApply(content -> new AiModelTurn(content, AiTokenUsage.UNKNOWN));
    }
}
