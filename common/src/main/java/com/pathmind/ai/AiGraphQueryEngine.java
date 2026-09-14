package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compact, model-facing graph discovery that avoids retransmitting complete serialized graphs. */
public final class AiGraphQueryEngine {
    private static final int MAX_RESULTS = 40;

    private AiGraphQueryEngine() {
    }

    public static JsonObject find(JsonObject source, JsonArray requestedTypes, String query,
                                  Map<String, String> references) {
        NodeGraphData graph = parse(source);
        Set<NodeType> types = new LinkedHashSet<>();
        if (requestedTypes != null) {
            for (var element : requestedTypes) {
                try {
                    types.add(NodeType.valueOf(element.getAsString().toUpperCase(Locale.ROOT)));
                } catch (RuntimeException ignored) {
                }
            }
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, String> aliases = aliasesById(references);
        JsonArray matches = new JsonArray();
        graph.getNodes().stream().filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt(NodeGraphData.NodeData::getY)
                .thenComparingInt(NodeGraphData.NodeData::getX)
                .thenComparing(NodeGraphData.NodeData::getId))
            .filter(node -> types.isEmpty() || types.contains(node.getType()))
            .filter(node -> needle.isEmpty() || searchable(node, aliases.get(node.getId())).contains(needle))
            .limit(MAX_RESULTS)
            .forEach(node -> matches.add(summary(graph, node, aliases.get(node.getId()))));
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", "Found " + matches.size() + " matching node(s).");
        result.add("nodes", matches);
        return result;
    }

    public static JsonObject inspectSubgraph(JsonObject source, JsonArray requestedRefs, int radius,
                                             Map<String, String> references) {
        NodeGraphData graph = parse(source);
        Map<String, NodeGraphData.NodeData> byId = new LinkedHashMap<>();
        for (NodeGraphData.NodeData node : graph.getNodes()) if (node != null) byId.put(node.getId(), node);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (requestedRefs != null) {
            for (var element : requestedRefs) {
                String ref = element.getAsString();
                String id = references == null ? ref : references.getOrDefault(ref, ref);
                if (byId.containsKey(id)) selected.add(id);
            }
        }
        if (selected.isEmpty()) return error("inspect_subgraph requires at least one known nodeRefs entry.");
        Map<String, Set<String>> neighbors = neighbors(graph);
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        selected.forEach(id -> queue.add(new Visit(id, 0)));
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            if (visit.depth >= Math.max(0, Math.min(3, radius))) continue;
            for (String neighbor : neighbors.getOrDefault(visit.id, Set.of())) {
                if (selected.size() >= MAX_RESULTS) break;
                if (selected.add(neighbor)) queue.addLast(new Visit(neighbor, visit.depth + 1));
            }
        }
        Map<String, String> aliases = aliasesById(references);
        JsonArray nodes = new JsonArray();
        selected.stream().map(byId::get).filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt(NodeGraphData.NodeData::getY)
                .thenComparingInt(NodeGraphData.NodeData::getX))
            .forEach(node -> nodes.add(summary(graph, node, aliases.get(node.getId()))));
        JsonArray connections = new JsonArray();
        for (NodeGraphData.ConnectionData edge : graph.getConnections()) {
            if (edge == null || !selected.contains(edge.getOutputNodeId()) || !selected.contains(edge.getInputNodeId())) continue;
            JsonObject item = new JsonObject();
            item.addProperty("from", display(edge.getOutputNodeId(), aliases));
            item.addProperty("outputSocket", edge.getOutputSocket());
            item.addProperty("to", display(edge.getInputNodeId(), aliases));
            item.addProperty("inputSocket", edge.getInputSocket());
            connections.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", "Returned a compact " + nodes.size() + "-node neighborhood.");
        result.add("nodes", nodes);
        result.add("connections", connections);
        return result;
    }

    private static JsonObject summary(NodeGraphData graph, NodeGraphData.NodeData node, String alias) {
        JsonObject result = new JsonObject();
        result.addProperty("ref", alias == null ? node.getId() : alias);
        result.addProperty("id", node.getId());
        result.addProperty("type", node.getType().name());
        if (node.getMode() != null) result.addProperty("mode", node.getMode().name());
        result.addProperty("x", node.getX());
        result.addProperty("y", node.getY());
        JsonObject parameters = new JsonObject();
        if (node.getParameters() != null) for (NodeGraphData.ParameterData parameter : node.getParameters()) {
            if (parameter != null) {
                parameters.addProperty(AiConfiguredValues.parameterId(parameter), parameter.getValue());
            }
        }
        result.add("parameters", parameters);
        JsonArray attachments = new JsonArray();
        if (node.getParameterAttachments() != null) for (var attachment : node.getParameterAttachments()) {
            if (attachment == null) continue;
            JsonObject input = new JsonObject();
            input.addProperty("slotIndex", attachment.getSlotIndex());
            input.addProperty("sourceNodeId", attachment.getParameterNodeId());
            graph.getNodes().stream().filter(child -> child != null
                && java.util.Objects.equals(child.getId(), attachment.getParameterNodeId())).findFirst().ifPresent(child -> {
                    input.addProperty("sourceType", child.getType().name());
                    JsonObject sourceParameters = new JsonObject();
                    if (child.getParameters() != null) for (var parameter : child.getParameters()) {
                        if (parameter != null) sourceParameters.addProperty(AiConfiguredValues.parameterId(parameter), parameter.getValue());
                    }
                    input.add("sourceParameters", sourceParameters);
                });
            attachments.add(input);
        }
        result.add("parameterAttachments", attachments);
        if (node.getAttachedSensorId() != null) result.addProperty("sensor", node.getAttachedSensorId());
        if (node.getAttachedActionId() != null) result.addProperty("action", node.getAttachedActionId());
        if (node.getParentControlId() != null) result.addProperty("sensorHost", node.getParentControlId());
        if (node.getParentActionControlId() != null) result.addProperty("actionHost", node.getParentActionControlId());
        if (node.getParentParameterHostId() != null) result.addProperty("parameterHost", node.getParentParameterHostId());
        return result;
    }

    private static String searchable(NodeGraphData.NodeData node, String alias) {
        StringBuilder value = new StringBuilder(node.getId()).append(' ').append(node.getType().name());
        if (alias != null) value.append(' ').append(alias);
        if (node.getMode() != null) value.append(' ').append(node.getMode().name());
        if (node.getParameters() != null) for (NodeGraphData.ParameterData parameter : node.getParameters()) {
            if (parameter != null) value.append(' ').append(parameter.getId()).append(' ')
                .append(parameter.getName()).append(' ').append(parameter.getValue());
        }
        return value.toString().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> neighbors(NodeGraphData graph) {
        Map<String, Set<String>> result = new HashMap<>();
        for (NodeGraphData.ConnectionData edge : graph.getConnections()) {
            if (edge == null) continue;
            link(result, edge.getOutputNodeId(), edge.getInputNodeId());
        }
        for (NodeGraphData.NodeData node : graph.getNodes()) {
            if (node == null) continue;
            link(result, node.getId(), node.getAttachedSensorId());
            link(result, node.getId(), node.getAttachedActionId());
            if (node.getParameterAttachments() != null) for (NodeGraphData.ParameterAttachmentData attachment : node.getParameterAttachments()) {
                if (attachment != null) link(result, node.getId(), attachment.getParameterNodeId());
            }
        }
        return result;
    }

    private static void link(Map<String, Set<String>> values, String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return;
        values.computeIfAbsent(left, ignored -> new LinkedHashSet<>()).add(right);
        values.computeIfAbsent(right, ignored -> new LinkedHashSet<>()).add(left);
    }

    private static Map<String, String> aliasesById(Map<String, String> references) {
        Map<String, String> result = new HashMap<>();
        if (references != null) references.forEach((alias, id) -> result.putIfAbsent(id, alias));
        return result;
    }

    private static String display(String id, Map<String, String> aliases) {
        return aliases.getOrDefault(id, id);
    }

    private static NodeGraphData parse(JsonObject source) {
        if (source == null) throw new IllegalArgumentException("No graph draft is available.");
        NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(source.toString());
        if (graph == null) throw new IllegalArgumentException("The graph draft could not be read.");
        if (graph.getNodes() == null) graph.setNodes(new ArrayList<>());
        if (graph.getConnections() == null) graph.setConnections(new ArrayList<>());
        return graph;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("message", message);
        return result;
    }

    private record Visit(String id, int depth) { }
}
