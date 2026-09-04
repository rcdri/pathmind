package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeCompatibility;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeSlotType;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the model's serialized graph before persistence conversion can normalize it. */
public final class AiGraphIntegrityValidator {
    private AiGraphIntegrityValidator() {
    }

    public static List<String> validate(NodeGraphData graph, boolean baritoneAvailable, boolean uiUtilsAvailable) {
        List<String> issues = new ArrayList<>();
        if (graph == null) {
            return List.of("The proposal did not include a graph.");
        }
        List<NodeGraphData.NodeData> nodes = graph.getNodes();
        List<NodeGraphData.ConnectionData> connections = graph.getConnections();
        if (nodes == null) issues.add("The graph is missing its nodes array.");
        if (connections == null) issues.add("The graph is missing its connections array.");
        if (!issues.isEmpty()) return List.copyOf(issues);

        Map<String, NodeGraphData.NodeData> byId = new LinkedHashMap<>();
        Map<String, Node> specimens = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            NodeGraphData.NodeData data = nodes.get(index);
            if (data == null) {
                issues.add("Node " + index + " is null.");
                continue;
            }
            String id = data.getId();
            if (id == null || id.isBlank()) {
                issues.add("Node " + index + " has no id.");
                continue;
            }
            if (byId.putIfAbsent(id, data) != null) {
                issues.add("Node id '" + id + "' is duplicated.");
                continue;
            }
            NodeType type = data.getType();
            if (type == null) {
                issues.add("Node '" + id + "' has no type.");
                continue;
            }
            if (type.requiresBaritone() && !baritoneAvailable) {
                issues.add("Node '" + id + "' requires Baritone, which is unavailable.");
            }
            if (type.requiresUiUtils() && !uiUtilsAvailable) {
                issues.add("Node '" + id + "' requires UI Utils, which is unavailable.");
            }
            NodeMode mode = data.getMode();
            NodeMode[] modes = NodeMode.getModesForNodeType(type);
            if (mode != null && Arrays.stream(modes).noneMatch(candidate -> candidate == mode)) {
                issues.add("Node '" + id + "' uses mode " + mode + ", which is not valid for " + type + ".");
            }
            Node specimen = new Node(type, data.getX(), data.getY());
            if (mode != null && Arrays.stream(modes).anyMatch(candidate -> candidate == mode)) specimen.setMode(mode);
            specimens.put(id, specimen);
            validateParameters(data, specimen, issues);
        }

        validateConnections(connections, byId, specimens, issues);
        validateAttachments(nodes, byId, specimens, issues);
        return List.copyOf(issues);
    }

    private static void validateParameters(NodeGraphData.NodeData data, Node specimen, List<String> issues) {
        if (data.getParameters() == null) {
            issues.add("Node '" + data.getId() + "' is missing its parameters array.");
            return;
        }
        if (data.getType() == NodeType.ROUTINE_CALL || data.getType() == NodeType.ROUTINE_INPUT) return;
        Set<String> seen = new HashSet<>();
        for (NodeGraphData.ParameterData parameter : data.getParameters()) {
            if (parameter == null) {
                issues.add("Node '" + data.getId() + "' has a null parameter.");
                continue;
            }
            NodeParameter expected = null;
            for (NodeParameter candidate : specimen.getParameters()) {
                if ((parameter.getId() != null && parameter.getId().equals(candidate.getId()))
                    || (parameter.getName() != null && parameter.getName().equals(candidate.getName()))) {
                    expected = candidate;
                    break;
                }
            }
            String key = parameter.getId() == null || parameter.getId().isBlank() ? parameter.getName() : parameter.getId();
            if (key == null || key.isBlank()) {
                issues.add("Node '" + data.getId() + "' has a parameter without an id or name.");
            } else if (!seen.add(key)) {
                issues.add("Node '" + data.getId() + "' duplicates parameter '" + key + "'.");
            }
            // Persistence intentionally retains legacy/dynamic parameters. When the catalog
            // owns a matching definition, its type is authoritative; otherwise preserve it.
            if (expected != null && (parameter.getType() == null || !expected.getType().name().equals(parameter.getType()))) {
                issues.add("Parameter '" + key + "' on node '" + data.getId() + "' has the wrong type.");
            }
        }
    }

    private static void validateConnections(List<NodeGraphData.ConnectionData> connections,
                                            Map<String, NodeGraphData.NodeData> byId,
                                            Map<String, Node> specimens, List<String> issues) {
        Set<String> occupiedInputs = new HashSet<>();
        Set<String> occupiedOutputs = new HashSet<>();
        Set<String> exactConnections = new HashSet<>();
        for (int index = 0; index < connections.size(); index++) {
            NodeGraphData.ConnectionData connection = connections.get(index);
            if (connection == null) {
                issues.add("Connection " + index + " is null.");
                continue;
            }
            String outputId = connection.getOutputNodeId();
            String inputId = connection.getInputNodeId();
            Node output = specimens.get(outputId);
            Node input = specimens.get(inputId);
            if (output == null) issues.add("Connection " + index + " references missing output node '" + outputId + "'.");
            if (input == null) issues.add("Connection " + index + " references missing input node '" + inputId + "'.");
            if (output == null || input == null) continue;
            if (outputId.equals(inputId)) issues.add("Connection " + index + " connects node '" + outputId + "' to itself.");
            if (connection.getOutputSocket() < 0 || connection.getOutputSocket() >= output.getOutputSocketCount()) {
                issues.add("Connection " + index + " uses an invalid output socket on '" + outputId + "'.");
            }
            if (connection.getInputSocket() < 0 || connection.getInputSocket() >= input.getInputSocketCount()) {
                issues.add("Connection " + index + " uses an invalid input socket on '" + inputId + "'.");
            }
            String inputKey = inputId + "#" + connection.getInputSocket();
            String outputKey = outputId + "#" + connection.getOutputSocket();
            String exactKey = outputKey + "->" + inputKey;
            if (!exactConnections.add(exactKey)) issues.add("Connection '" + exactKey + "' is duplicated.");
            if (!occupiedInputs.add(inputKey)) issues.add("Input socket '" + inputKey + "' has more than one connection.");
            if (!occupiedOutputs.add(outputKey)) issues.add("Output socket '" + outputKey + "' has more than one connection.");
        }
    }

    private static void validateAttachments(List<NodeGraphData.NodeData> nodes,
                                            Map<String, NodeGraphData.NodeData> byId,
                                            Map<String, Node> specimens, List<String> issues) {
        Map<String, String> parameterParents = new HashMap<>();
        for (NodeGraphData.NodeData hostData : nodes) {
            if (hostData == null || hostData.getId() == null || hostData.getType() == null) continue;
            String hostId = hostData.getId();
            Node host = specimens.get(hostId);
            if (host != null && host.hasSensorSlot()
                && (hostData.getAttachedSensorId() == null || hostData.getAttachedSensorId().isBlank())) {
                issues.add("Node '" + hostId + "' requires a sensor attachment.");
            }
            if (host != null && host.hasActionSlot()
                && (hostData.getAttachedActionId() == null || hostData.getAttachedActionId().isBlank())) {
                issues.add("Node '" + hostId + "' requires an action attachment.");
            }
            validatePairedAttachment(hostId, hostData.getAttachedSensorId(), "sensor", byId, issues,
                child -> host != null && NodeCompatibility.canHostSlot(host.getType(), NodeSlotType.SENSOR)
                    && child.getType() != null && NodeCompatibility.canAttachToSlot(host, specimens.get(child.getId()), NodeSlotType.SENSOR, 0),
                NodeGraphData.NodeData::getParentControlId);
            validatePairedAttachment(hostId, hostData.getAttachedActionId(), "action", byId, issues,
                child -> host != null && child.getType() != null
                    && NodeCompatibility.canAttachToSlot(host, specimens.get(child.getId()), NodeSlotType.ACTION, 0),
                NodeGraphData.NodeData::getParentActionControlId);

            List<NodeGraphData.ParameterAttachmentData> attachments = hostData.getParameterAttachments();
            if (attachments == null) {
                issues.add("Node '" + hostId + "' is missing its parameterAttachments array.");
                continue;
            }
            Set<Integer> slots = new HashSet<>();
            for (NodeGraphData.ParameterAttachmentData attachment : attachments) {
                if (attachment == null) {
                    issues.add("Node '" + hostId + "' has a null parameter attachment.");
                    continue;
                }
                int slot = attachment.getSlotIndex();
                String childId = attachment.getParameterNodeId();
                NodeGraphData.NodeData childData = byId.get(childId);
                Node child = specimens.get(childId);
                if (!slots.add(slot)) issues.add("Node '" + hostId + "' attaches more than one value to slot " + slot + ".");
                if (host == null || slot < 0 || slot >= host.getParameterSlotCount()) {
                    issues.add("Node '" + hostId + "' uses invalid parameter slot " + slot + ".");
                }
                if (childData == null || child == null) {
                    issues.add("Node '" + hostId + "' references missing parameter node '" + childId + "'.");
                    continue;
                }
                if (host != null && slot >= 0 && slot < host.getParameterSlotCount()
                    && !NodeCompatibility.canAttachToSlot(host, child, NodeSlotType.PARAMETER, slot)) {
                    issues.add("Node '" + childId + "' is incompatible with parameter slot " + slot + " on '" + hostId + "'.");
                }
                String previousParent = parameterParents.putIfAbsent(childId, hostId);
                if (previousParent != null && !previousParent.equals(hostId)) {
                    issues.add("Parameter node '" + childId + "' is attached to both '" + previousParent + "' and '" + hostId + "'.");
                }
                if (!hostId.equals(childData.getParentParameterHostId())) {
                    issues.add("Parameter attachment between '" + hostId + "' and '" + childId + "' is not bidirectional.");
                }
            }
        }

        for (NodeGraphData.NodeData child : nodes) {
            if (child == null || child.getId() == null) continue;
            validateParentReference(child, child.getParentControlId(), "sensor", byId,
                NodeGraphData.NodeData::getAttachedSensorId, issues);
            validateParentReference(child, child.getParentActionControlId(), "action", byId,
                NodeGraphData.NodeData::getAttachedActionId, issues);
            if (child.getParentParameterHostId() != null && !child.getParentParameterHostId().isBlank()) {
                NodeGraphData.NodeData parent = byId.get(child.getParentParameterHostId());
                boolean linked = parent != null && parent.getParameterAttachments() != null
                    && parent.getParameterAttachments().stream().anyMatch(attachment -> attachment != null
                        && child.getId().equals(attachment.getParameterNodeId()));
                if (!linked) issues.add("Parameter parent reference from '" + child.getId() + "' is not bidirectional.");
            }
        }
    }

    private static void validatePairedAttachment(String hostId, String childId, String kind,
                                                 Map<String, NodeGraphData.NodeData> byId, List<String> issues,
                                                 java.util.function.Predicate<NodeGraphData.NodeData> compatible,
                                                 java.util.function.Function<NodeGraphData.NodeData, String> parentId) {
        if (childId == null || childId.isBlank()) return;
        NodeGraphData.NodeData child = byId.get(childId);
        if (child == null) {
            issues.add("Node '" + hostId + "' references missing " + kind + " node '" + childId + "'.");
            return;
        }
        if (!compatible.test(child)) issues.add("Node '" + childId + "' cannot be attached as a " + kind + " to '" + hostId + "'.");
        if (!hostId.equals(parentId.apply(child))) issues.add(kind + " attachment between '" + hostId + "' and '" + childId + "' is not bidirectional.");
    }

    private static void validateParentReference(NodeGraphData.NodeData child, String parentId, String kind,
                                                Map<String, NodeGraphData.NodeData> byId,
                                                java.util.function.Function<NodeGraphData.NodeData, String> childId,
                                                List<String> issues) {
        if (parentId == null || parentId.isBlank()) return;
        NodeGraphData.NodeData parent = byId.get(parentId);
        if (parent == null || !child.getId().equals(childId.apply(parent))) {
            issues.add(kind + " parent reference from '" + child.getId() + "' is not bidirectional.");
        }
    }
}
