package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Curated executable patterns indexed by node types and reusable structural traits. */
public final class AiGoldenGraphLibrary {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MATCH_LIMIT = 4;
    private static final List<Source> SOURCES = List.of(
        resource("repeat-action", "Repeat an attached action", "A fixed-count control with an attached action.",
            "/assets/pathmind/ai_examples/repeat_action.json", AiExampleTrait.REPEAT),
        resource("onboarding-1", "Sequence with parameter cards", "A flow sequence configured through typed parameter attachments.",
            "/assets/pathmind/onboarding_presets/example_1.json", AiExampleTrait.PARAMETER_ATTACHMENTS),
        resource("onboarding-2", "Conditional variable workflow", "An if/else driven by composed sensor data and variables.",
            "/assets/pathmind/onboarding_presets/example_2.json", AiExampleTrait.CONDITION_WITH_SENSOR,
            AiExampleTrait.IF_ELSE, AiExampleTrait.PARAMETER_ATTACHMENTS, AiExampleTrait.VARIABLES),
        resource("onboarding-3", "Parallel list workflow", "Lists and variables feeding fork/join and conditional flow.",
            "/assets/pathmind/onboarding_presets/example_3.json", AiExampleTrait.IF_ELSE, AiExampleTrait.FORK_JOIN,
            AiExampleTrait.PARAMETER_ATTACHMENTS, AiExampleTrait.VARIABLES, AiExampleTrait.LISTS),
        generated("condition-with-sensor", "Condition with a sensor", "A sensor gates one attached action before flow continues.",
            AiGoldenGraphPatterns::conditionWithSensor, AiExampleTrait.CONDITION_WITH_SENSOR),
        generated("repeat-until", "Repeat until a sensor matches", "A repeated action exits when its attached sensor becomes true.",
            AiGoldenGraphPatterns::repeatUntil, AiExampleTrait.CONDITION_WITH_SENSOR, AiExampleTrait.REPEAT_UNTIL),
        generated("nested-controls", "Nested repeat and condition", "A repeat contains a sensor-gated action control.",
            AiGoldenGraphPatterns::nestedControls, AiExampleTrait.REPEAT, AiExampleTrait.CONDITION_WITH_SENSOR,
            AiExampleTrait.NESTED_CONTROLS),
        generated("routine-arguments", "Routine with a typed argument", "A reusable routine consumes a typed reporter at a body slot.",
            AiGoldenGraphPatterns::routineWithArguments, AiExampleTrait.PARAMETER_ATTACHMENTS,
            AiExampleTrait.ROUTINE_ARGUMENTS),
        generated("inventory-workflow", "Inventory selection workflow", "An inventory sequence uses a typed slot selection.",
            AiGoldenGraphPatterns::inventoryWorkflow, AiExampleTrait.PARAMETER_ATTACHMENTS,
            AiExampleTrait.INVENTORY_WORKFLOW),
        generated("navigation-collection", "Navigate and collect", "A coordinate target leads into a typed block collection action.",
            AiGoldenGraphPatterns::navigationAndCollection, AiExampleTrait.PARAMETER_ATTACHMENTS,
            AiExampleTrait.NAVIGATION_COLLECTION)
    );

    private AiGoldenGraphLibrary() {
    }

    /** Returns a compact catalog. Full graphs and previews are only returned by relevant detail queries. */
    public static JsonArray list() {
        JsonArray examples = new JsonArray();
        for (Entry entry : entries()) examples.add(indexSummary(entry));
        return examples;
    }

    public static JsonArray listMatching(Set<NodeType> requestedTypes) {
        return listMatching(requestedTypes, Set.of(), DEFAULT_MATCH_LIMIT);
    }

    public static JsonArray listMatching(Set<NodeType> requestedTypes, Set<AiExampleTrait> requestedTraits, int limit) {
        JsonArray examples = new JsonArray();
        for (Entry entry : matches(requestedTypes, requestedTraits, limit)) examples.add(indexSummary(entry));
        return examples;
    }

    public static JsonArray detailsMatching(Set<NodeType> requestedTypes, int limit) {
        return detailsMatching(requestedTypes, inferTraits(requestedTypes), limit);
    }

    public static JsonArray detailsMatching(Set<NodeType> requestedTypes, Set<AiExampleTrait> requestedTraits, int limit) {
        JsonArray examples = new JsonArray();
        for (Entry entry : matches(requestedTypes, requestedTraits, limit)) {
            JsonObject detail = summary(entry);
            detail.add("graph", GSON.toJsonTree(entry.graph()));
            examples.add(detail);
        }
        return examples;
    }

    public static Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public static Set<AiExampleTrait> inferTraits(Set<NodeType> types) {
        EnumSet<AiExampleTrait> traits = EnumSet.noneOf(AiExampleTrait.class);
        if (types == null) return traits;
        if (types.contains(NodeType.CONTROL_IF) || types.contains(NodeType.CONTROL_IF_DO)) traits.add(AiExampleTrait.CONDITION_WITH_SENSOR);
        if (types.contains(NodeType.CONTROL_IF_ELSE)) traits.add(AiExampleTrait.IF_ELSE);
        if (types.contains(NodeType.CONTROL_REPEAT)) traits.add(AiExampleTrait.REPEAT);
        if (types.contains(NodeType.CONTROL_REPEAT_UNTIL)) traits.add(AiExampleTrait.REPEAT_UNTIL);
        if (types.contains(NodeType.CONTROL_FORK) || types.contains(NodeType.CONTROL_JOIN_ALL)
            || types.contains(NodeType.CONTROL_JOIN_ANY)) traits.add(AiExampleTrait.FORK_JOIN);
        if (types.contains(NodeType.VARIABLE) || types.contains(NodeType.SET_VARIABLE)) traits.add(AiExampleTrait.VARIABLES);
        if (types.stream().anyMatch(type -> type.name().contains("LIST"))) traits.add(AiExampleTrait.LISTS);
        if (types.contains(NodeType.ROUTINE_CALL) || types.contains(NodeType.ROUTINE_INPUT)) traits.add(AiExampleTrait.ROUTINE_ARGUMENTS);
        if (types.stream().anyMatch(type -> type.name().contains("INVENTORY") || type == NodeType.HOTBAR
            || type == NodeType.MOVE_ITEM || type == NodeType.DROP_ITEM || type == NodeType.DROP_SLOT)) {
            traits.add(AiExampleTrait.INVENTORY_WORKFLOW);
        }
        if (types.stream().anyMatch(type -> type == NodeType.GOTO || type == NodeType.TRAVEL || type == NodeType.GOAL
            || type == NodeType.COLLECT)) traits.add(AiExampleTrait.NAVIGATION_COLLECTION);
        if (types.stream().anyMatch(type -> type.name().startsWith("PARAM_") || type == NodeType.ROUTINE_INPUT)) {
            traits.add(AiExampleTrait.PARAMETER_ATTACHMENTS);
        }
        return traits;
    }

    public static JsonObject summary(Entry entry) {
        JsonObject summary = indexSummary(entry);
        summary.add("preview", AiExecutionPreview.preview(entry.graph()));
        return summary;
    }

    private static JsonObject indexSummary(Entry entry) {
        JsonObject summary = new JsonObject();
        summary.addProperty("id", entry.id());
        summary.addProperty("name", entry.name());
        summary.addProperty("description", entry.description());
        summary.addProperty("nodeCount", entry.graph().getNodes() == null ? 0 : entry.graph().getNodes().size());
        summary.addProperty("connectionCount", entry.graph().getConnections() == null ? 0 : entry.graph().getConnections().size());
        JsonArray types = new JsonArray();
        nodeTypes(entry.graph()).stream().map(Enum::name).forEach(types::add);
        summary.add("nodeTypes", types);
        JsonArray traits = new JsonArray();
        entry.traits().stream().map(Enum::name).forEach(traits::add);
        summary.add("traits", traits);
        return summary;
    }

    private static List<Entry> matches(Set<NodeType> requestedTypes, Set<AiExampleTrait> requestedTraits, int limit) {
        if (limit <= 0) return List.of();
        Set<NodeType> types = discriminativeTypes(requestedTypes);
        Set<AiExampleTrait> traits = requestedTraits == null ? Set.of() : requestedTraits;
        if (types.isEmpty() && traits.isEmpty()) return List.of();
        return entries().stream()
            .filter(entry -> overlap(entry, types, traits) > 0)
            .sorted(Comparator.comparingInt((Entry entry) -> score(entry, types, traits)).reversed()
                .thenComparingInt(entry -> entry.graph().getNodes().size())
                .thenComparing(Entry::id))
            .limit(Math.min(limit, DEFAULT_MATCH_LIMIT))
            .toList();
    }

    private static Set<NodeType> discriminativeTypes(Set<NodeType> requestedTypes) {
        if (requestedTypes == null || requestedTypes.isEmpty()) return Set.of();
        EnumSet<NodeType> types = EnumSet.copyOf(requestedTypes);
        types.removeAll(Set.of(NodeType.START, NodeType.START_CHAIN, NodeType.MESSAGE, NodeType.STICKY_NOTE,
            NodeType.ROUTINE_ENTRY));
        return types;
    }

    private static int overlap(Entry entry, Set<NodeType> types, Set<AiExampleTrait> traits) {
        int overlap = 0;
        Set<NodeType> present = nodeTypes(entry.graph());
        for (NodeType type : types) if (present.contains(type)) overlap++;
        for (AiExampleTrait trait : traits) if (entry.traits().contains(trait)) overlap++;
        return overlap;
    }

    private static int score(Entry entry, Set<NodeType> types, Set<AiExampleTrait> traits) {
        Set<NodeType> present = nodeTypes(entry.graph());
        int matchedTypes = 0;
        int matchedTraits = 0;
        for (NodeType type : types) if (present.contains(type)) matchedTypes++;
        for (AiExampleTrait trait : traits) if (entry.traits().contains(trait)) matchedTraits++;
        int missingTypes = types.size() - matchedTypes;
        int missingTraits = traits.size() - matchedTraits;
        int unrelatedTraits = Math.max(0, entry.traits().size() - matchedTraits);
        return matchedTraits * 12 + matchedTypes * 4 - missingTraits * 7 - missingTypes * 2 - unrelatedTraits;
    }

    private static Set<NodeType> nodeTypes(NodeGraphData graph) {
        Set<NodeType> types = new LinkedHashSet<>();
        collectNodeTypes(graph, types);
        return types;
    }

    private static void collectNodeTypes(NodeGraphData graph, Set<NodeType> types) {
        if (graph == null) return;
        if (graph.getNodes() != null) graph.getNodes().forEach(node -> {
            if (node != null && node.getType() != null) types.add(node.getType());
        });
        if (graph.getRoutines() != null) graph.getRoutines().forEach(routine -> {
            if (routine != null) collectNodeTypes(routine.getGraph(), types);
        });
    }

    private static List<Entry> entries() {
        return EntriesHolder.ENTRIES;
    }

    private static Optional<Entry> load(Source source) {
        try {
            NodeGraphData graph;
            if (source.resource() != null) {
                try (InputStream stream = AiGoldenGraphLibrary.class.getResourceAsStream(source.resource())) {
                    if (stream == null) return Optional.empty();
                    graph = NodeGraphPersistence.parseNodeGraphData(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            } else {
                graph = source.factory().get();
            }
            return graph == null ? Optional.empty() : Optional.of(new Entry(source.id(), source.name(),
                source.description(), source.traits(), graph));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Source resource(String id, String name, String description, String path, AiExampleTrait... traits) {
        return new Source(id, name, description, Set.of(traits), path, null);
    }

    private static Source generated(String id, String name, String description, Supplier<NodeGraphData> factory,
                                    AiExampleTrait... traits) {
        return new Source(id, name, description, Set.of(traits), null, factory);
    }

    public record Entry(String id, String name, String description, Set<AiExampleTrait> traits, NodeGraphData graph) { }
    private record Source(String id, String name, String description, Set<AiExampleTrait> traits, String resource,
                          Supplier<NodeGraphData> factory) { }

    private static final class EntriesHolder {
        private static final List<Entry> ENTRIES;
        static {
            List<Entry> loaded = new ArrayList<>();
            for (Source source : SOURCES) load(source).ifPresent(loaded::add);
            ENTRIES = List.copyOf(loaded);
        }
    }
}
