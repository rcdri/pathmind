package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Requests and parses proposals. Applying a proposal remains an explicit UI action. */
public final class AiPresetService {
    private AiPresetService() {
    }

    public static CompletableFuture<Proposal> request(AiProviderType provider, String model, String prompt,
                                                       boolean baritoneAvailable, boolean uiUtilsAvailable) {
        return request(provider, model, prompt, baritoneAvailable, uiUtilsAvailable, null, "", false);
    }

    public static CompletableFuture<Proposal> request(AiProviderType provider, String model, String prompt,
                                                       boolean baritoneAvailable, boolean uiUtilsAvailable,
                                                       NodeGraphData activeGraph, String activePresetName, boolean editActivePreset) {
        return request(provider, model, prompt, baritoneAvailable, uiUtilsAvailable, activeGraph, activePresetName);
    }

    /** The model chooses whether its complete graph creates a preset or updates the open one. */
    public static CompletableFuture<Proposal> request(AiProviderType provider, String model, String prompt,
                                                       boolean baritoneAvailable, boolean uiUtilsAvailable,
                                                       NodeGraphData activeGraph, String activePresetName) {
        return request(provider, model, prompt, baritoneAvailable, uiUtilsAvailable, activeGraph, activePresetName, "");
    }

    public static CompletableFuture<Proposal> request(AiProviderType provider, String model, String prompt,
                                                       boolean baritoneAvailable, boolean uiUtilsAvailable,
                                                       NodeGraphData activeGraph, String activePresetName, String conversation) {
        String systemPrompt = AiPresetContextBuilder.systemPrompt(baritoneAvailable, uiUtilsAvailable);
        systemPrompt += "\nThe currently open preset is '" + activePresetName + "'. Decide target yourself: use target \"current\" only when the user explicitly asks to modify, extend, fix, or change the open preset; use \"new\" for a requested standalone preset; use \"inspect\" for questions, diagnosis, explanations, or advice. "
            + "For inspect, omit graph and explain the answer in response. For new/current, return a complete graph; current must preserve unrelated behavior. workLog is a concise, user-visible account of checks and decisions, never hidden reasoning. ACTIVE_PRESET_GRAPH=" + new Gson().toJson(activeGraph);
        String audit = agentGraphAudit(activeGraph);
        if (!audit.isBlank()) systemPrompt += "\nACTIVE_GRAPH_AUDIT (must be addressed for inspect requests): " + audit;
        if (conversation != null && !conversation.isBlank()) systemPrompt += "\nConversation so far (honor the user's latest correction):\n" + conversation;
        return AiProviderRegistry.configured(provider)
            .orElseThrow(() -> new IllegalStateException("Configure and enable an AI provider in Settings first."))
            .generate(new AiPresetRequest(systemPrompt, prompt, model))
            .thenApply(AiPresetService::parseProposal);
    }

    public static Proposal parseProposal(String content) {
        JsonObject response = JsonParser.parseString(stripCodeFence(content)).getAsJsonObject();
        String target = string(response, "target", "new").toLowerCase(java.util.Locale.ROOT);
        if (!target.equals("new") && !target.equals("current") && !target.equals("inspect")) target = "new";
        NodeGraphData graph = null;
        if (!target.equals("inspect")) {
            if (!response.has("graph")) throw new IllegalArgumentException("AI response did not include a graph.");
            graph = NodeGraphPersistence.parseNodeGraphData(response.get("graph").toString());
            if (graph == null) throw new IllegalArgumentException("AI returned an unreadable graph.");
        }
        List<String> workLog = new ArrayList<>();
        if (response.has("workLog") && response.get("workLog").isJsonArray()) response.getAsJsonArray("workLog").forEach(entry -> {
            if (workLog.size() < 4) workLog.add(shortText(entry.getAsString(), 120));
        });
        return new Proposal(string(response, "title", "Untitled AI preset"), shortText(string(response, "response", string(response, "description", "")), 280), workLog, graph, target);
    }

    /** Small semantic guard for control-node mistakes that generic graph validation cannot infer. */
    public static String agentGraphAudit(NodeGraphData graph) {
        if (graph == null || graph.getNodes() == null) return "";
        java.util.Map<String, NodeGraphData.NodeData> nodes = new java.util.HashMap<>();
        for (NodeGraphData.NodeData node : graph.getNodes()) if (node != null && node.getId() != null) nodes.put(node.getId(), node);
        java.util.List<String> issues = new ArrayList<>();
        for (NodeGraphData.NodeData node : nodes.values()) {
            if (node.getType() != com.pathmind.nodes.NodeType.CONTROL_REPEAT) continue;
            String actionId = node.getAttachedActionId();
            NodeGraphData.NodeData action = actionId == null ? null : nodes.get(actionId);
            if (action == null) issues.add("Repeat " + node.getId() + " has no attached repeat-body action.");
            else if (!node.getId().equals(action.getParentActionControlId())) issues.add("Repeat " + node.getId() + " and action " + actionId + " do not have matching action attachment ids.");
        }
        return String.join(" ", issues);
    }

    private static String shortText(String value, int limit) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(1, limit - 1)) + "…";
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

    public record Proposal(String title, String response, List<String> workLog, NodeGraphData graph, String target) {
        public boolean editsCurrentPreset() { return "current".equals(target); }
        public boolean changesGraph() { return "new".equals(target) || "current".equals(target); }
    }
}
