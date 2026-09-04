package com.pathmind.ai;

import com.google.gson.JsonObject;

/** Immutable request passed from the workspace UI to a provider adapter. */
public record AiPresetRequest(String systemPrompt, String userPrompt, String model,
                              String outputSchemaName, JsonObject outputSchema) {
    public AiPresetRequest(String systemPrompt, String userPrompt, String model) {
        this(systemPrompt, userPrompt, model, "pathmind_preset_proposal", AiPresetResponseSchema.create());
    }
}
