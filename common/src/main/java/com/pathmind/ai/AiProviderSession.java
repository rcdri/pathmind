package com.pathmind.ai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Request-scoped provider state. Instances must never be shared between graph requests. */
public interface AiProviderSession extends AutoCloseable {
    CompletableFuture<AiModelTurn> generate(AiPresetRequest request, JsonObject previousToolResult);
    @Override default void close() { }
}
