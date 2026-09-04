package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.util.concurrent.CompletableFuture;

/** Requests and parses proposals. Applying a proposal remains an explicit UI action. */
public final class AiPresetService {
    private AiPresetService() {
    }

    public static CompletableFuture<Proposal> request(AiProviderType provider, String model, String prompt,
                                                       boolean baritoneAvailable, boolean uiUtilsAvailable) {
        return AiProviderRegistry.configured(provider)
            .orElseThrow(() -> new IllegalStateException("Configure and enable an AI provider in Settings first."))
            .generate(new AiPresetRequest(AiPresetContextBuilder.systemPrompt(baritoneAvailable, uiUtilsAvailable), prompt, model))
            .thenApply(AiPresetService::parseProposal);
    }

    public static Proposal parseProposal(String content) {
        JsonObject response = JsonParser.parseString(stripCodeFence(content)).getAsJsonObject();
        if (!response.has("graph")) throw new IllegalArgumentException("AI response did not include a graph.");
        NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(response.get("graph").toString());
        if (graph == null) throw new IllegalArgumentException("AI returned an unreadable graph.");
        return new Proposal(string(response, "title", "Untitled AI preset"), string(response, "description", ""), graph);
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstNewline >= 0 && lastFence > firstNewline ? trimmed.substring(firstNewline + 1, lastFence).trim() : trimmed;
    }

    public record Proposal(String title, String description, NodeGraphData graph) {
    }
}
