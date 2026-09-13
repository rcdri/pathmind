package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeValueTrait;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts validator output into bounded, executable repair guidance for the graph agent. */
public final class AiGraphRepairAdvisor {
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    private AiGraphRepairAdvisor() {
    }

    public static JsonObject describe(AiPresetService.ValidationIssue issue, NodeGraphData graph,
                                      Map<String, String> references) {
        Map<String, NodeGraphData.NodeData> nodes = nodes(graph);
        String nodeId = present(issue.nodeId()) ? issue.nodeId() : firstKnownQuoted(issue.message(), nodes.keySet());
        String code = refineCode(issue.code(), issue.message());
        JsonObject result = new JsonObject();
        result.addProperty("severity", issue.severity());
        result.addProperty("code", code);
        if (present(nodeId)) result.addProperty("nodeId", nodeId);
        if (present(issue.routineId())) result.addProperty("routineId", issue.routineId());
        result.addProperty("message", issue.message());
        result.add("expected", expected(code, nodeId, graph));
        result.addProperty("actual", actual(nodes.get(nodeId)));
        result.add("relatedNodeIds", relatedIds(issue.message(), nodeId, nodes.keySet()));
        result.add("suggestedOperations", suggestions(code));
        JsonArray inspectRefs = new JsonArray();
        Map<String, String> aliases = aliasesById(references);
        for (var element : result.getAsJsonArray("relatedNodeIds")) {
            String id = element.getAsString();
            inspectRefs.add(aliases.getOrDefault(id, id));
        }
        result.add("inspectRefs", inspectRefs);
        return result;
    }

    private static String refineCode(String code, String message) {
        if (!"serialized_integrity".equals(code) || message == null) return code;
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("requires an action attachment")) return "missing_action_attachment";
        if (normalized.contains("requires a sensor attachment")) return "missing_sensor_attachment";
        if (normalized.contains("invalid output socket")) return "invalid_output_socket";
        if (normalized.contains("invalid input socket")) return "invalid_input_socket";
        if (normalized.contains("more than one connection")) return "multiple_connections";
        if (normalized.contains("not bidirectional")) return "unpaired_attachment";
        if (normalized.contains("incompatible with parameter slot")) return "incompatible_parameter_attachment";
        if (normalized.contains("references missing")) return "missing_reference";
        if (normalized.contains("duplicated")) return "duplicate_serialized_identity";
        return code;
    }

    private static JsonArray expected(String code, String nodeId, NodeGraphData graph) {
        JsonArray values = new JsonArray();
        switch (code) {
            case "missing_action_attachment" -> values.add("One compatible ACTION attachment owned by this control node.");
            case "missing_sensor_attachment" -> values.add("One compatible boolean SENSOR attachment owned by this control node.");
            case "missing_parameter_slot" -> addMissingSlots(values, nodeId, graph);
            case "multiple_inputs", "multiple_connections" -> values.add("At most one connection may occupy each input and output socket.");
            case "invalid_input_socket", "invalid_output_socket" -> values.add("A socket index within the endpoint node's described contract.");
            case "unpaired_attachment" -> values.add("Host and child attachment fields must reference each other.");
            case "incompatible_parameter_attachment", "incompatible_routine_argument",
                 "ordering_non_numeric_operand", "boolean_operator_non_boolean_operand",
                 "comparison_boolean_type_mismatch" -> values.add("An attached value whose traits match the host slot contract.");
            case "missing_start" -> values.add("A START node connected to the root control-flow path.");
            case "missing_routine_entry" -> values.add("Exactly one ROUTINE_ENTRY in the routine definition graph.");
            case "missing_routine_definition" -> values.add("A routine call whose routineId resolves to this preset's routine registry.");
            case "unreachable_node", "dead_entry" -> values.add("A control-flow path reachable from START or another valid entry.");
            case "missing_event_call_name", "missing_event_function_name", "missing_event_target",
                 "missing_preset", "missing_preset_target", "missing_start_target" -> values.add("A non-empty parameter value resolving to an existing target.");
            case "variable_type_mismatch" -> values.add("Assignments whose inferred value traits are accepted by every variable consumer.");
            default -> values.add("A graph satisfying the node contract and Pathmind's runtime validator.");
        }
        return values;
    }

    private static void addMissingSlots(JsonArray values, String nodeId, NodeGraphData graph) {
        Node runtime = runtimeNode(nodeId, graph);
        if (runtime == null) {
            values.add("All required parameter slots must have compatible attached values.");
            return;
        }
        for (int slot = 0; slot < runtime.getParameterSlotCount(); slot++) {
            if (!runtime.isParameterSlotRequired(slot) || runtime.getAttachedParameter(slot) != null) continue;
            List<String> traits = runtime.getAcceptedTraitsForParameterSlot(slot).stream()
                .map(NodeValueTrait::name).sorted().toList();
            values.add("slot " + slot + " (" + runtime.getParameterSlotLabel(slot) + ") accepts " + traits);
        }
        if (values.isEmpty()) values.add("All required parameter slots must have compatible attached values.");
    }

    private static JsonArray suggestions(String code) {
        JsonArray values = new JsonArray();
        switch (code) {
            case "missing_action_attachment" -> values.add("attach_action");
            case "missing_sensor_attachment" -> values.add("attach_sensor");
            case "missing_parameter_slot" -> values.add("attach_parameter");
            case "multiple_inputs", "multiple_connections", "invalid_input_socket", "invalid_output_socket" -> {
                values.add("disconnect"); values.add("connect");
            }
            case "unpaired_attachment" -> {
                values.add("detach_action"); values.add("detach_sensor"); values.add("detach_parameter");
                values.add("attach_action"); values.add("attach_sensor"); values.add("attach_parameter");
            }
            case "incompatible_parameter_attachment", "incompatible_routine_argument",
                 "ordering_non_numeric_operand", "boolean_operator_non_boolean_operand",
                 "comparison_boolean_type_mismatch" -> {
                values.add("detach_parameter"); values.add("attach_parameter");
            }
            case "missing_start" -> { values.add("add_sequence"); values.add("connect"); }
            case "unreachable_node", "dead_entry" -> { values.add("connect"); values.add("remove_node"); }
            case "missing_routine_entry" -> values.add("create_routine");
            case "missing_routine_definition" -> { values.add("add_routine_call"); values.add("remove_node"); }
            case "missing_event_call_name", "missing_event_function_name", "missing_event_target",
                 "missing_preset", "missing_preset_target", "missing_start_target" -> values.add("set_parameter");
            case "variable_type_mismatch" -> { values.add("detach_parameter"); values.add("attach_parameter"); }
            default -> { values.add("set_parameter"); values.add("connect"); values.add("remove_node"); }
        }
        return values;
    }

    private static String actual(NodeGraphData.NodeData node) {
        if (node == null) return "Graph-level issue or node unavailable.";
        StringBuilder value = new StringBuilder(node.getType().name());
        if (present(node.getAttachedSensorId())) value.append(" sensor=").append(node.getAttachedSensorId());
        if (present(node.getAttachedActionId())) value.append(" action=").append(node.getAttachedActionId());
        if (node.getParameterAttachments() != null && !node.getParameterAttachments().isEmpty()) {
            value.append(" parameterSlots=");
            value.append(node.getParameterAttachments().stream().filter(java.util.Objects::nonNull)
                .map(item -> item.getSlotIndex() + ":" + item.getParameterNodeId()).toList());
        }
        return value.toString();
    }

    private static JsonArray relatedIds(String message, String primary, Set<String> known) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (present(primary) && known.contains(primary)) ids.add(primary);
        if (message != null) {
            Matcher matcher = QUOTED.matcher(message);
            while (matcher.find() && ids.size() < 8) if (known.contains(matcher.group(1))) ids.add(matcher.group(1));
        }
        JsonArray result = new JsonArray();
        ids.forEach(result::add);
        return result;
    }

    private static String firstKnownQuoted(String message, Set<String> known) {
        if (message == null) return null;
        Matcher matcher = QUOTED.matcher(message);
        while (matcher.find()) if (known.contains(matcher.group(1))) return matcher.group(1);
        return null;
    }

    private static Node runtimeNode(String nodeId, NodeGraphData graph) {
        if (!present(nodeId) || graph == null) return null;
        try {
            for (Node node : NodeGraphPersistence.convertToNodes(graph)) if (nodeId.equals(node.getId())) return node;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static Map<String, NodeGraphData.NodeData> nodes(NodeGraphData graph) {
        Map<String, NodeGraphData.NodeData> result = new HashMap<>();
        if (graph != null && graph.getNodes() != null) for (NodeGraphData.NodeData node : graph.getNodes()) {
            if (node != null && present(node.getId())) result.put(node.getId(), node);
        }
        return result;
    }

    private static Map<String, String> aliasesById(Map<String, String> references) {
        Map<String, String> result = new HashMap<>();
        if (references != null) references.forEach((alias, id) -> result.putIfAbsent(id, alias));
        return result;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
