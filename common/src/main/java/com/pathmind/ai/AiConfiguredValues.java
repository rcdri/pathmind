package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeParameterSemantics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/** Conservative static readback: never evaluates sensors, variables or world-dependent reporters. */
final class AiConfiguredValues {
    private AiConfiguredValues() { }

    static Value read(NodeGraphData graph, NodeGraphData.NodeData node, String id) {
        String normalized = NodeParameter.createDefaultId(id);
        String literal = literal(node, normalized);
        String value = literal;
        String source = node.getId();
        boolean known = true;
        var attachments = node.getParameterAttachments() == null ? java.util.List.<NodeGraphData.ParameterAttachmentData>of()
            : new ArrayList<>(node.getParameterAttachments());
        attachments = attachments.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(NodeGraphData.ParameterAttachmentData::getSlotIndex)).toList();
        for (var attachment : attachments) {
            if (attachment == null) continue;
            NodeGraphData.NodeData child = graph.getNodes().stream().filter(candidate -> candidate != null
                && Objects.equals(attachment.getParameterNodeId(), candidate.getId())).findFirst().orElse(null);
            if (child == null || !NodeCatalog.isLiteralValueSource(child.getType())
                || (child.getParameterAttachments() != null && !child.getParameterAttachments().isEmpty())) {
                if (NodeParameterSemantics.uncertainInputKeys(node, attachment.getSlotIndex(), child).stream()
                    .anyMatch(key -> normalized.equals(NodeParameter.createDefaultId(key)))) {
                    known = false; value = null; source = attachment.getParameterNodeId();
                }
                continue;
            }
            var exported = NodeParameterSemantics.literalInput(node, attachment.getSlotIndex(), child);
            for (var entry : exported.entrySet()) if (normalized.equals(NodeParameter.createDefaultId(entry.getKey()))) {
                value = entry.getValue(); source = child.getId(); known = true;
            }
        }
        return new Value(literal, value, source, known);
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
