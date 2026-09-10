package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Curated, executable examples loaded from Pathmind's bundled presets rather than prompt literals. */
public final class AiGoldenGraphLibrary {
    private static final List<Source> SOURCES = List.of(
        new Source("repeat-action", "Repeat an attached action", "/assets/pathmind/ai_examples/repeat_action.json"),
        new Source("onboarding-1", "Onboarding example 1", "/assets/pathmind/onboarding_presets/example_1.json"),
        new Source("onboarding-2", "Onboarding example 2", "/assets/pathmind/onboarding_presets/example_2.json"),
        new Source("onboarding-3", "Onboarding example 3", "/assets/pathmind/onboarding_presets/example_3.json")
    );

    private AiGoldenGraphLibrary() {
    }

    public static JsonArray list() {
        JsonArray examples = new JsonArray();
        for (Source source : SOURCES) {
            load(source).ifPresent(entry -> examples.add(summary(entry)));
        }
        return examples;
    }

    public static JsonArray listMatching(Set<NodeType> requestedTypes) {
        JsonArray examples = new JsonArray();
        if (requestedTypes == null || requestedTypes.isEmpty()) return examples;
        for (Source source : SOURCES) {
            load(source).filter(entry -> containsAny(entry.graph(), requestedTypes))
                .ifPresent(entry -> examples.add(summary(entry)));
        }
        return examples;
    }

    public static JsonArray detailsMatching(Set<NodeType> requestedTypes, int limit) {
        JsonArray examples = new JsonArray();
        if (requestedTypes == null || requestedTypes.isEmpty() || limit <= 0) return examples;
        List<Entry> matches = new java.util.ArrayList<>();
        for (Source source : SOURCES) load(source).filter(entry -> containsAny(entry.graph(), requestedTypes)).ifPresent(matches::add);
        matches.sort(java.util.Comparator.comparingInt((Entry entry) -> matchScore(entry.graph(), requestedTypes)).reversed());
        for (Entry entry : matches) {
            if (examples.size() >= limit) break;
            JsonObject detail = summary(entry);
            detail.add("graph", new com.google.gson.Gson().toJsonTree(entry.graph()));
            examples.add(detail);
        }
        return examples;
    }

    public static Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        for (Source source : SOURCES) {
            if (source.id.equals(id)) return load(source);
        }
        return Optional.empty();
    }

    public static JsonObject summary(Entry entry) {
        JsonObject summary = new JsonObject();
        summary.addProperty("id", entry.id());
        summary.addProperty("name", entry.name());
        summary.addProperty("nodeCount", entry.graph().getNodes() == null ? 0 : entry.graph().getNodes().size());
        summary.addProperty("connectionCount", entry.graph().getConnections() == null ? 0 : entry.graph().getConnections().size());
        JsonArray types = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        if (entry.graph().getNodes() != null) entry.graph().getNodes().forEach(node -> {
            if (node != null && node.getType() != null && seen.add(node.getType().name())) types.add(node.getType().name());
        });
        summary.add("nodeTypes", types);
        summary.add("preview", AiExecutionPreview.preview(entry.graph()));
        return summary;
    }

    private static Optional<Entry> load(Source source) {
        try (InputStream stream = AiGoldenGraphLibrary.class.getResourceAsStream(source.resource)) {
            if (stream == null) return Optional.empty();
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(json);
            return graph == null ? Optional.empty() : Optional.of(new Entry(source.id, source.name, graph));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean containsAny(NodeGraphData graph, Set<NodeType> requestedTypes) {
        if (graph == null || graph.getNodes() == null) return false;
        return graph.getNodes().stream().anyMatch(node -> node != null && requestedTypes.contains(node.getType()));
    }

    private static int matchScore(NodeGraphData graph, Set<NodeType> requestedTypes) {
        if (graph == null || graph.getNodes() == null) return 0;
        Set<NodeType> present = new java.util.HashSet<>();
        graph.getNodes().forEach(node -> { if (node != null && node.getType() != null) present.add(node.getType()); });
        int score = 0;
        for (NodeType requested : requestedTypes) if (present.contains(requested)) score++;
        return score;
    }

    public record Entry(String id, String name, NodeGraphData graph) { }
    private record Source(String id, String name, String resource) { }
}
