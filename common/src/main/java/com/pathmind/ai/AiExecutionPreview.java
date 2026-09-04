package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces a bounded, human-readable structural execution preview without running world actions. */
public final class AiExecutionPreview {
    private static final int MAX_PATHS = 8;
    private static final int MAX_DEPTH = 32;

    private AiExecutionPreview() {
    }

    public static JsonObject inspect(String presetName, NodeGraphData graph) {
        JsonObject result = new JsonObject();
        result.addProperty("preset", presetName == null ? "" : presetName);
        result.addProperty("nodeCount", graph == null || graph.getNodes() == null ? 0 : graph.getNodes().size());
        result.addProperty("connectionCount", graph == null || graph.getConnections() == null ? 0 : graph.getConnections().size());
        result.addProperty("routineCount", graph == null ? 0 : graph.getRoutines().size());
        result.add("graph", graph == null ? null : com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(graph)));
        return result;
    }

    public static JsonObject preview(NodeGraphData graph) {
        JsonObject result = new JsonObject();
        JsonArray paths = new JsonArray();
        result.add("paths", paths);
        if (graph == null || graph.getNodes() == null || graph.getConnections() == null) {
            result.addProperty("summary", "No graph is available.");
            return result;
        }
        Map<String, NodeGraphData.NodeData> nodes = new LinkedHashMap<>();
        for (NodeGraphData.NodeData node : graph.getNodes()) if (node != null && node.getId() != null) nodes.put(node.getId(), node);
        Map<String, List<NodeGraphData.ConnectionData>> outgoing = new HashMap<>();
        Set<String> incoming = new HashSet<>();
        for (NodeGraphData.ConnectionData connection : graph.getConnections()) {
            if (connection == null) continue;
            outgoing.computeIfAbsent(connection.getOutputNodeId(), ignored -> new ArrayList<>()).add(connection);
            incoming.add(connection.getInputNodeId());
        }
        List<String> roots = new ArrayList<>();
        for (NodeGraphData.NodeData node : nodes.values()) {
            if (node.getType() == NodeType.START) roots.add(node.getId());
        }
        if (roots.isEmpty()) {
            for (String id : nodes.keySet()) if (!incoming.contains(id)) roots.add(id);
        }
        for (String root : roots) collect(root, nodes, outgoing, new ArrayList<>(), new HashSet<>(), paths, 0);
        result.addProperty("summary", paths.isEmpty() ? "No control-flow path could be traced." : paths.size() + " structural path(s) traced; no world actions were executed.");
        return result;
    }

    private static void collect(String id, Map<String, NodeGraphData.NodeData> nodes,
                                Map<String, List<NodeGraphData.ConnectionData>> outgoing,
                                List<String> path, Set<String> visiting, JsonArray paths, int depth) {
        if (paths.size() >= MAX_PATHS) return;
        NodeGraphData.NodeData node = nodes.get(id);
        if (node == null) return;
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(label(node, nodes, new HashSet<>()));
        if (depth >= MAX_DEPTH) {
            nextPath.add("…depth limit");
            paths.add(String.join(" → ", nextPath));
            return;
        }
        if (!visiting.add(id)) {
            nextPath.add("↻ " + label(node, nodes, new HashSet<>()));
            paths.add(String.join(" → ", nextPath));
            return;
        }
        List<NodeGraphData.ConnectionData> next = outgoing.getOrDefault(id, List.of());
        if (next.isEmpty()) {
            paths.add(String.join(" → ", nextPath));
        } else {
            for (NodeGraphData.ConnectionData connection : next) {
                List<String> branch = new ArrayList<>(nextPath);
                if (next.size() > 1) branch.add("output[" + connection.getOutputSocket() + "]");
                collect(connection.getInputNodeId(), nodes, outgoing, branch, new HashSet<>(visiting), paths, depth + 1);
            }
        }
    }

    private static String label(NodeGraphData.NodeData node, Map<String, NodeGraphData.NodeData> nodes, Set<String> visiting) {
        String label = node.getType() == null ? "Unknown" : NodeCatalog.displayName(node.getType());
        if (node.getMode() != null) label += " (" + node.getMode().getDisplayName() + ")";
        if (node.getParameters() != null) {
            List<String> parameters = new ArrayList<>();
            node.getParameters().forEach(parameter -> {
                if (parameter != null && parameter.getValue() != null && !parameter.getValue().isBlank()) {
                    parameters.add((parameter.getName() == null ? parameter.getId() : parameter.getName()) + "=" + parameter.getValue());
                }
            });
            if (!parameters.isEmpty()) label += " (" + String.join(", ", parameters) + ")";
        }
        label += " [" + node.getId() + "]";
        if (!visiting.add(node.getId())) return label + " ↻";
        if (present(node.getAttachedSensorId())) label += " ? { " + attachedLabel(node.getAttachedSensorId(), nodes, visiting) + " }";
        if (present(node.getAttachedActionId())) label += " { " + attachedLabel(node.getAttachedActionId(), nodes, visiting) + " }";
        if (node.getParameterAttachments() != null && !node.getParameterAttachments().isEmpty()) {
            List<String> values = new ArrayList<>();
            node.getParameterAttachments().forEach(attachment -> {
                if (attachment != null) values.add("slot " + attachment.getSlotIndex() + ": "
                    + attachedLabel(attachment.getParameterNodeId(), nodes, new HashSet<>(visiting)));
            });
            label += " (" + String.join("; ", values) + ")";
        }
        return label;
    }

    private static String attachedLabel(String id, Map<String, NodeGraphData.NodeData> nodes, Set<String> visiting) {
        NodeGraphData.NodeData attached = nodes.get(id);
        return attached == null ? "missing [" + id + "]" : label(attached, nodes, new HashSet<>(visiting));
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
