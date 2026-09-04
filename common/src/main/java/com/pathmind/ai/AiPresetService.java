package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeConnection;
import com.pathmind.validation.GraphValidationIssue;
import com.pathmind.validation.GraphValidationResult;
import com.pathmind.validation.GraphValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
        AiProvider configured = AiProviderRegistry.configured(provider)
            .orElseThrow(() -> new IllegalStateException("Configure and enable an AI provider in Settings first."));
        return AiPresetAgent.run(configured, model, prompt, conversation, activeGraph, activePresetName,
            baritoneAvailable, uiUtilsAvailable);
    }

    public static Proposal parseProposal(String content) {
        JsonObject response = JsonParser.parseString(stripCodeFence(content)).getAsJsonObject();
        String target = string(response, "target", "new").toLowerCase(java.util.Locale.ROOT);
        if (!target.equals("new") && !target.equals("current") && !target.equals("inspect")) target = "new";
        NodeGraphData graph = null;
        if (!target.equals("inspect")) {
            if (!response.has("graph") || response.get("graph").isJsonNull()) throw new IllegalArgumentException("AI response did not include a graph.");
            graph = NodeGraphPersistence.parseNodeGraphData(response.get("graph").toString());
            if (graph == null) throw new IllegalArgumentException("AI returned an unreadable graph.");
        }
        List<String> workLog = new ArrayList<>();
        if (response.has("workLog") && response.get("workLog").isJsonArray()) response.getAsJsonArray("workLog").forEach(entry -> {
            if (workLog.size() < 4) workLog.add(shortText(entry.getAsString(), 120));
        });
        return new Proposal(string(response, "title", "Untitled AI preset"), shortText(string(response, "response", string(response, "description", "")), 280), workLog, graph, target);
    }

    /** Runs lossless serialized checks before the normal runtime validator. */
    public static Validation validateProposal(Proposal proposal, String activePreset, boolean baritoneAvailable, boolean uiUtilsAvailable) {
        if (proposal == null || !proposal.changesGraph()) return Validation.success();
        List<ValidationIssue> issues = new ArrayList<>();
        for (String message : AiGraphIntegrityValidator.validate(proposal.graph(), baritoneAvailable, uiUtilsAvailable)) {
            issues.add(new ValidationIssue("error", "serialized_integrity", null, null, message));
        }
        if (!issues.isEmpty()) return new Validation(false, limited(issues));
        try {
            List<Node> nodes = NodeGraphPersistence.convertToNodes(proposal.graph());
            Map<String, Node> byId = new HashMap<>();
            for (Node node : nodes) if (node != null) byId.put(node.getId(), node);
            List<NodeConnection> connections = NodeGraphPersistence.convertToConnections(proposal.graph(), byId);
            String presetName = proposal.editsCurrentPreset() ? activePreset : proposal.title();
            GraphValidationResult result = GraphValidator.validate(nodes, connections, presetName,
                baritoneAvailable, uiUtilsAvailable, proposal.graph().getRoutines(), "");
            for (GraphValidationIssue issue : result.getIssues()) {
                issues.add(new ValidationIssue(issue.getSeverity().name().toLowerCase(java.util.Locale.ROOT),
                    issue.getCode(), issue.getNodeId(), issue.getRoutineId(), issue.getMessage()));
            }
        } catch (RuntimeException exception) {
            issues.add(new ValidationIssue("error", "graph_conversion", null, null,
                exception.getMessage() == null ? "The graph could not be converted." : exception.getMessage()));
        }
        boolean valid = issues.stream().noneMatch(ValidationIssue::isError);
        return issues.isEmpty() ? Validation.success() : new Validation(valid, limited(issues));
    }

    private static List<ValidationIssue> limited(List<ValidationIssue> issues) {
        List<ValidationIssue> ordered = new ArrayList<>(issues);
        ordered.sort(java.util.Comparator.comparing(ValidationIssue::isError).reversed());
        return List.copyOf(ordered.subList(0, Math.min(12, ordered.size())));
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

    public static String graphFingerprint(String presetName, NodeGraphData graph) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String serialized = (presetName == null ? "" : presetName) + "\n" + new com.google.gson.Gson().toJson(graph);
            return HexFormat.of().formatHex(digest.digest(serialized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static boolean matchesSource(Proposal proposal, String presetName, NodeGraphData graph) {
        return proposal != null && proposal.editsCurrentPreset()
            && proposal.sourceFingerprint() != null && !proposal.sourceFingerprint().isBlank()
            && proposal.sourceFingerprint().equals(graphFingerprint(presetName, graph));
    }

    public record Proposal(String title, String response, List<String> workLog, NodeGraphData graph, String target,
                           String sourceFingerprint, AiProposalReview review) {
        public Proposal(String title, String response, List<String> workLog, NodeGraphData graph, String target) {
            this(title, response, workLog, graph, target, "", null);
        }
        public boolean editsCurrentPreset() { return "current".equals(target); }
        public boolean changesGraph() { return "new".equals(target) || "current".equals(target); }
    }

    public record ValidationIssue(String severity, String code, String nodeId, String routineId, String message) {
        public boolean isError() { return "error".equals(severity); }
    }

    public record Validation(boolean valid, List<ValidationIssue> issues) {
        public static Validation success() { return new Validation(true, List.of()); }
        public String summary() {
            if (issues.isEmpty()) return "";
            return issues.stream().map(ValidationIssue::message).collect(java.util.stream.Collectors.joining(" "));
        }
    }
}
