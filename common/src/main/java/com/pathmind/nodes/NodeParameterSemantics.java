package com.pathmind.nodes;

import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Editor-safe access to the same exports and slot mapping used during execution. Never evaluates dynamic reporters. */
public final class NodeParameterSemantics {
    private NodeParameterSemantics() { }

    public static Node specimen(NodeGraphData.NodeData data) {
        Node node = Node.createForEditor(data.getType(), 0, 0);
        if (data.getMode() != null) node.setMode(data.getMode());
        NodeGraphPersistence.restoreParameters(node, data.getParameters());
        return node;
    }

    public static Map<String, String> literalInput(NodeGraphData.NodeData host, int slot, NodeGraphData.NodeData source) {
        Node child = specimen(source);
        return specimen(host).adjustParameterValuesForSlot(child.exportParameterValues(), slot, child);
    }

    public static Set<String> uncertainInputKeys(NodeGraphData.NodeData host, int slot, NodeGraphData.NodeData source) {
        Set<String> keys = new HashSet<>();
        var accepted = NodeCatalog.acceptedTraits(host.getType(), slot);
        var provided = source == null ? java.util.EnumSet.of(NodeValueTrait.ANY) : NodeCatalog.providedTraits(source.getType());
        Node owner = specimen(host);
        for (NodeType type : NodeType.values()) {
            if (!NodeCatalog.isLiteralValueSource(type)) continue;
            var traits = NodeCatalog.providedTraits(type);
            boolean compatibleSlot = accepted.contains(NodeValueTrait.ANY) || traits.stream().anyMatch(accepted::contains);
            boolean compatibleSource = provided.contains(NodeValueTrait.ANY) || provided.contains(NodeValueTrait.VARIABLE)
                || traits.stream().anyMatch(provided::contains);
            if (!compatibleSlot || !compatibleSource) continue;
            Node child = Node.createForEditor(type, 0, 0);
            keys.addAll(owner.adjustParameterValuesForSlot(child.exportParameterValues(), slot, child).keySet());
        }
        if (keys.isEmpty()) for (NodeParameter parameter : owner.getParameters()) keys.add(parameter.getName());
        return keys;
    }
}
