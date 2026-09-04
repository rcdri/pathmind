package com.pathmind.ai;

import java.util.concurrent.CompletableFuture;

public interface AiProvider {
    default AiProviderCapabilities capabilities() {
        return AiProviderCapabilities.TEXT_ONLY;
    }

    CompletableFuture<String> generate(AiPresetRequest request);
}
