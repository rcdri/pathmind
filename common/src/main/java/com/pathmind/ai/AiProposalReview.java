package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.pathmind.data.NodeGraphData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact graph delta and structural trace displayed before the user can apply a proposal. */
public record AiProposalReview(List<String> changes, List<String> executionPaths) {
    private static final Gson GSON = new Gson();
    private static final int MAX_CHANGE_LINES = 12;

    public static AiProposalReview create(NodeGraphData before, NodeGraphData after) {
        List<String> changes = new ArrayList<>();
        Map<String, NodeGraphData.NodeData> oldNodes = nodes(before);
        Map<String, NodeGraphData.NodeData> newNodes = nodes(after);
        for (Map.Entry<String, NodeGraphData.NodeData> entry : newNodes.entrySet()) {
            NodeGraphData.NodeData previous = oldNodes.get(entry.getKey());
            if (previous == null) add(changes, "+ node " + nodeLabel(entry.getValue()));
            else if (!GSON.toJson(previous).equals(GSON.toJson(entry.getValue()))) add(changes, "~ node " + nodeLabel(entry.getValue()));
        }
        for (Map.Entry<String, NodeGraphData.NodeData> entry : oldNodes.entrySet()) {
            if (!newNodes.containsKey(entry.getKey())) add(changes, "− node " + nodeLabel(entry.getValue()));
        }
        diffSet(changes, connections(before), connections(after), "connection");
        diffSet(changes, attachments(before), attachments(after), "attachment");
        if (changes.isEmpty()) changes.add("No serialized graph changes.");

        List<String> paths = new ArrayList<>();
        JsonArray previewPaths = AiExecutionPreview.preview(after).getAsJsonArray("paths");
        if (previewPaths != null) for (JsonElement path : previewPaths) {
            if (paths.size() >= 4) break;
            paths.add(path.getAsString());
        }
        return new AiProposalReview(List.copyOf(changes), List.copyOf(paths));
    }

    public List<String> displayLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Review — graph changes:");
        lines.addAll(changes);
        if (!executionPaths.isEmpty()) {
            lines.add("Execution preview:");
            lines.addAll(executionPaths);
        }
        return List.copyOf(lines);
    }

    private static Map<String, NodeGraphData.NodeData> nodes(NodeGraphData graph) {
        Map<String, NodeGraphData.NodeData> result = new LinkedHashMap<>();
        if (graph != null && graph.getNodes() != null) for (NodeGraphData.NodeData node : graph.getNodes()) {
            if (node != null && node.getId() != null) result.put(node.getId(), node);
        }
        return result;
    }

    private static Set<String> connections(NodeGraphData graph) {
        Set<String> result = new LinkedHashSet<>();
        if (graph != null && graph.getConnections() != null) for (NodeGraphData.ConnectionData connection : graph.getConnections()) {
            if (connection != null) result.add(connection.getOutputNodeId() + "[" + connection.getOutputSocket() + "] → "
                + connection.getInputNodeId() + "[" + connection.getInputSocket() + "]");
        }
        return result;
    }

    private static Set<String> attachments(NodeGraphData graph) {
        Set<String> result = new LinkedHashSet<>();
        if (graph == null || graph.getNodes() == null) return result;
        for (NodeGraphData.NodeData node : graph.getNodes()) {
            if (node == null || node.getId() == null) continue;
            if (present(node.getAttachedSensorId())) result.add(node.getId() + " sensor → " + node.getAttachedSensorId());
            if (present(node.getAttachedActionId())) result.add(node.getId() + " action → " + node.getAttachedActionId());
            if (node.getParameterAttachments() != null) node.getParameterAttachments().forEach(attachment -> {
                if (attachment != null) result.add(node.getId() + " parameter[" + attachment.getSlotIndex() + "] → " + attachment.getParameterNodeId());
            });
        }
        return result;
    }

    private static void diffSet(List<String> changes, Set<String> before, Set<String> after, String kind) {
        for (String value : after) if (!before.contains(value)) add(changes, "+ " + kind + " " + value);
        for (String value : before) if (!after.contains(value)) add(changes, "− " + kind + " " + value);
    }

    private static void add(List<String> changes, String line) {
        if (changes.size() < MAX_CHANGE_LINES) changes.add(line);
        else if (changes.size() == MAX_CHANGE_LINES) changes.add("…additional changes omitted");
    }

    private static String nodeLabel(NodeGraphData.NodeData node) {
        String label = (node.getType() == null ? "UNKNOWN" : node.getType().name()) + " [" + node.getId() + "]";
        String behavior = behaviorSummary(node);
        return behavior.isBlank() ? label : label + " — " + behavior;
    }

    private static String behaviorSummary(NodeGraphData.NodeData node) {
        if (node.getType() != com.pathmind.nodes.NodeType.CRAFT) return "";
        String item = parameter(node, "item");
        String amount = parameter(node, "amount");
        if (item == null || amount == null) return "";
        return "Craft " + amount + "× " + item;
    }

    private static String parameter(NodeGraphData.NodeData node, String id) {
        if (node.getParameters() == null) return null;
        for (NodeGraphData.ParameterData parameter : node.getParameters()) {
            if (parameter != null && id.equals(com.pathmind.nodes.NodeParameter.createDefaultId(parameter.getId()))) {
                return parameter.getValue();
            }
        }
        return null;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
