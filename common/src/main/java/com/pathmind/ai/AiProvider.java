package com.pathmind.ai;

import java.util.concurrent.CompletableFuture;

public interface AiProvider {
    CompletableFuture<String> generate(AiPresetRequest request);
}
