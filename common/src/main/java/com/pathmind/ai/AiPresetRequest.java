package com.pathmind.ai;

/** Immutable request passed from the workspace UI to a provider adapter. */
public record AiPresetRequest(String systemPrompt, String userPrompt, String model) {
}
