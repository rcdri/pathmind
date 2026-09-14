package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.NodeParameter;

/** Conservative static readback: never evaluates sensors, variables or world-dependent reporters. */
final class AiConfiguredValues {
    private AiConfiguredValues() { }

    static Value read(NodeGraphData graph, NodeGraphData.NodeData node, String id) {
        String normalized = NodeParameter.createDefaultId(id);
        String literal = literal(node, normalized);
        String value = literal;
        String source = node.getId();
        if (node.getParameterAttachments() != null) for (var attachment : node.getParameterAttachments()) {
            if (attachment == null) continue;
            NodeGraphData.NodeData child = graph.getNodes().stream().filter(candidate -> candidate != null
                && attachment.getParameterNodeId().equals(candidate.getId())).findFirst().orElse(null);
            if (child == null || !NodeCatalog.isLiteralValueSource(child.getType())
                || (child.getParameterAttachments() != null && !child.getParameterAttachments().isEmpty())) {
                return new Value(literal, null, "runtime input", false);
            }
            String exported = literal(child, normalized);
            if (exported != null) { value = exported; source = child.getId(); }
        }
        return new Value(literal, value, source, true);
    }

    private static String literal(NodeGraphData.NodeData node, String id) {
        if (node.getParameters() == null) return null;
        for (var parameter : node.getParameters()) {
            if (parameter != null && id.equals(parameterId(parameter))) return parameter.getValue();
        }
        return null;
    }

    static String parameterId(NodeGraphData.ParameterData parameter) {
        return NodeParameter.createDefaultId(parameter.getId() == null || parameter.getId().isBlank()
            ? parameter.getName() : parameter.getId());
    }

    record Value(String literal, String effective, String sourceNodeId, boolean staticallyKnown) { }
}
