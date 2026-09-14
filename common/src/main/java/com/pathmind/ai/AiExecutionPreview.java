package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces a bounded structural execution preview without running world actions. */
public final class AiExecutionPreview {
    private static final int MAX_PATHS = 8;
    private static final int MAX_DEPTH = 36;
    private static final int MAX_INLINE_DEPTH = 14;

    private AiExecutionPreview() { }

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
            result.add("variableDependencies", new JsonArray());
            return result;
        }
        Map<String, NodeGraphData.RoutineDefinitionData> routines = new LinkedHashMap<>();
        for (NodeGraphData.RoutineDefinitionData routine : graph.getRoutines()) {
            if (routine != null && present(routine.getId())) routines.put(routine.getId(), routine);
        }
        Context context = context(graph, routines);
        for (String root : roots(context, NodeType.START)) {
            collect(root, context, new ArrayList<>(), new HashSet<>(), new HashSet<>(), paths, 0);
        }
        JsonArray dependencies = variableDependencies(context);
        result.add("variableDependencies", dependencies);
        result.addProperty("summary", paths.isEmpty() ? "No control-flow path could be traced."
            : paths.size() + " structural path(s) traced across branches, attachments, and routines; "
                + dependencies.size() + " variable dependency group(s); no world actions were executed.");
        return result;
    }

    private static void collect(String id, Context context, List<String> path, Set<String> visiting,
                                Set<String> routineStack, JsonArray paths, int depth) {
        if (paths.size() >= MAX_PATHS) return;
        NodeGraphData.NodeData node = context.nodes().get(id);
        if (node == null) return;
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(label(node, context, new HashSet<>(), new HashSet<>(routineStack), 0));
        if (depth >= MAX_DEPTH) {
            nextPath.add("…depth limit");
            paths.add(String.join(" → ", nextPath));
            return;
        }
        if (!visiting.add(id)) {
            nextPath.add("↻ " + baseLabel(node));
            paths.add(String.join(" → ", nextPath));
            return;
        }
        List<NodeGraphData.ConnectionData> next = context.outgoing().getOrDefault(id, List.of());
        if (next.isEmpty()) paths.add(String.join(" → ", nextPath));
        else for (NodeGraphData.ConnectionData edge : next) {
            List<String> branch = new ArrayList<>(nextPath);
            if (next.size() > 1) branch.add(branchName(node, edge.getOutputSocket()));
            collect(edge.getInputNodeId(), context, branch, new HashSet<>(visiting),
                new HashSet<>(routineStack), paths, depth + 1);
        }
    }

    private static String label(NodeGraphData.NodeData node, Context context, Set<String> visiting,
                                Set<String> routineStack, int depth) {
        String result = baseLabel(node, context);
        if (depth >= MAX_INLINE_DEPTH || !visiting.add(node.getId())) return result + " ↻";
        if (present(node.getAttachedSensorId())) result += " ? { "
            + attachedValue(node.getAttachedSensorId(), context, visiting, routineStack, depth + 1) + " }";
        if (node.getParameterAttachments() != null && !node.getParameterAttachments().isEmpty()) {
            List<String> values = new ArrayList<>();
            node.getParameterAttachments().stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(NodeGraphData.ParameterAttachmentData::getSlotIndex))
                .forEach(attachment -> values.add("slot " + attachment.getSlotIndex() + ": "
                    + attachedValue(attachment.getParameterNodeId(), context, new HashSet<>(visiting),
                        new HashSet<>(routineStack), depth + 1)));
            if (!values.isEmpty()) result += " (" + String.join("; ", values) + ")";
        }
        if (present(node.getAttachedActionId())) result += " { "
            + inlineFlow(node.getAttachedActionId(), context, new HashSet<>(visiting),
                new HashSet<>(routineStack), depth + 1) + " }";
        if (node.getType() == NodeType.ROUTINE_CALL && present(node.getRoutineId())) result += " { routine: "
            + routineFlow(node.getRoutineId(), context.routines(), routineStack, depth + 1) + " }";
        return result;
    }

    private static String inlineFlow(String id, Context context, Set<String> visiting,
                                     Set<String> routineStack, int depth) {
        NodeGraphData.NodeData node = context.nodes().get(id);
        if (node == null) return "missing [" + id + "]";
        if (depth >= MAX_INLINE_DEPTH || visiting.contains(id)) return baseLabel(node) + " ↻";
        String current = label(node, context, visiting, routineStack, depth);
        List<NodeGraphData.ConnectionData> next = context.outgoing().getOrDefault(id, List.of());
        if (next.isEmpty()) return current;
        if (next.size() == 1) return current + " → " + inlineFlow(next.get(0).getInputNodeId(), context,
            new HashSet<>(visiting), new HashSet<>(routineStack), depth + 1);
        List<String> branches = new ArrayList<>();
        for (NodeGraphData.ConnectionData edge : next) branches.add(branchName(node, edge.getOutputSocket()) + ": "
            + inlineFlow(edge.getInputNodeId(), context, new HashSet<>(visiting), new HashSet<>(routineStack), depth + 1));
        return current + " → { " + String.join("; ", branches) + " }";
    }

    private static String routineFlow(String routineId, Map<String, NodeGraphData.RoutineDefinitionData> routines,
                                      Set<String> routineStack, int depth) {
        NodeGraphData.RoutineDefinitionData routine = routines.get(routineId);
        if (routine == null) return "missing routine [" + routineId + "]";
        if (!routineStack.add(routineId)) return routine.getName() + " ↻";
        if (routine.getGraph() == null) return routine.getName() + " { empty }";
        Context routineContext = context(routine.getGraph(), routines);
        List<String> entries = roots(routineContext, NodeType.ROUTINE_ENTRY);
        if (entries.isEmpty()) return routine.getName() + " { missing entry }";
        return routine.getName() + " [" + routineId + "] → " + inlineFlow(entries.get(0), routineContext,
            new HashSet<>(), routineStack, depth + 1);
    }

    private static String attachedValue(String id, Context context, Set<String> visiting,
                                        Set<String> routineStack, int depth) {
        NodeGraphData.NodeData attached = context.nodes().get(id);
        return attached == null ? "missing [" + id + "]" : label(attached, context, visiting, routineStack, depth);
    }

    private static JsonArray variableDependencies(Context context) {
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        for (NodeGraphData.NodeData variable : context.nodes().values()) {
            if (variable.getType() != NodeType.VARIABLE) continue;
            String name = parameter(variable, "variable");
            if (name.isBlank()) name = "unnamed";
            Dependency dependency = dependencies.computeIfAbsent(name, ignored -> new Dependency());
            NodeGraphData.NodeData host = context.nodes().get(variable.getParentParameterHostId());
            if (host == null) continue;
            int slot = parameterSlot(host, variable.getId());
            if (host.getType() == NodeType.SET_VARIABLE && slot == 0) {
                dependency.assignments().add(host.getId());
                NodeGraphData.NodeData source = context.nodes().get(parameterNode(host, 1));
                if (source != null) dependency.valueSources().add(baseLabel(source));
            } else dependency.consumers().add(host.getId() + " (slot " + slot + ")");
        }
        JsonArray result = new JsonArray();
        dependencies.forEach((name, dependency) -> {
            JsonObject item = new JsonObject();
            item.addProperty("variable", name);
            item.add("assignedBy", strings(dependency.assignments()));
            item.add("valueSources", strings(dependency.valueSources()));
            item.add("consumedBy", strings(dependency.consumers()));
            result.add(item);
        });
        return result;
    }

    private static Context context(NodeGraphData graph, Map<String, NodeGraphData.RoutineDefinitionData> routines) {
        Map<String, NodeGraphData.NodeData> nodes = new LinkedHashMap<>();
        if (graph.getNodes() != null) for (NodeGraphData.NodeData node : graph.getNodes()) {
            if (node != null && present(node.getId())) nodes.put(node.getId(), node);
        }
        Map<String, List<NodeGraphData.ConnectionData>> outgoing = new HashMap<>();
        Set<String> incoming = new HashSet<>();
        if (graph.getConnections() != null) for (NodeGraphData.ConnectionData edge : graph.getConnections()) {
            if (edge == null) continue;
            outgoing.computeIfAbsent(edge.getOutputNodeId(), ignored -> new ArrayList<>()).add(edge);
            incoming.add(edge.getInputNodeId());
        }
        outgoing.values().forEach(edges -> edges.sort(Comparator.comparingInt(NodeGraphData.ConnectionData::getOutputSocket)
            .thenComparing(NodeGraphData.ConnectionData::getInputNodeId)));
        return new Context(nodes, outgoing, incoming, routines);
    }

    private static List<String> roots(Context context, NodeType preferred) {
        List<String> roots = new ArrayList<>();
        for (NodeGraphData.NodeData node : context.nodes().values()) if (node.getType() == preferred) roots.add(node.getId());
        if (roots.isEmpty()) for (NodeGraphData.NodeData node : context.nodes().values()) {
            if (!context.incoming().contains(node.getId()) && !present(node.getParentControlId())
                && !present(node.getParentActionControlId()) && !present(node.getParentParameterHostId())) roots.add(node.getId());
        }
        return roots;
    }

    private static String baseLabel(NodeGraphData.NodeData node) {
        return baseLabel(node, null);
    }

    private static String baseLabel(NodeGraphData.NodeData node, Context context) {
        String label = node.getType() == null ? "Unknown" : NodeCatalog.displayName(node.getType());
        if (node.getMode() != null) label += " (" + node.getMode().getDisplayName() + ")";
        if (node.getParameters() != null) {
            List<String> parameters = new ArrayList<>();
            NodeGraphData scope = new NodeGraphData();
            scope.setNodes(context == null ? List.of(node) : new ArrayList<>(context.nodes().values()));
            node.getParameters().forEach(value -> {
                if (value != null && present(value.getValue())) {
                    var configured = context == null ? null : AiConfiguredValues.read(scope, node, AiConfiguredValues.parameterId(value));
                    parameters.add((value.getName() == null ? value.getId() : value.getName()) + "="
                        + (configured == null ? value.getValue() : configured.staticallyKnown() ? configured.effective() : "runtime-dependent"));
                }
            });
            if (!parameters.isEmpty()) label += " (" + String.join(", ", parameters) + ")";
        }
        String stableType = node.getType() == null ? "UNKNOWN" : node.getType().name();
        return label + " <" + stableType + "> [" + node.getId() + "]";
    }

    private static String branchName(NodeGraphData.NodeData node, int socket) {
        if (node.getType() == NodeType.CONTROL_IF_ELSE) return socket == 0 ? "true" : "false";
        return "output[" + socket + "]";
    }

    private static String parameter(NodeGraphData.NodeData node, String id) {
        if (node.getParameters() == null) return "";
        return node.getParameters().stream().filter(java.util.Objects::nonNull)
            .filter(value -> id.equalsIgnoreCase(value.getId()) || id.equalsIgnoreCase(value.getName()))
            .map(NodeGraphData.ParameterData::getValue).filter(java.util.Objects::nonNull).findFirst().orElse("");
    }

    private static int parameterSlot(NodeGraphData.NodeData host, String childId) {
        if (host == null || host.getParameterAttachments() == null) return -1;
        return host.getParameterAttachments().stream().filter(java.util.Objects::nonNull)
            .filter(value -> childId.equals(value.getParameterNodeId())).map(NodeGraphData.ParameterAttachmentData::getSlotIndex)
            .findFirst().orElse(-1);
    }

    private static String parameterNode(NodeGraphData.NodeData host, int slot) {
        if (host == null || host.getParameterAttachments() == null) return null;
        return host.getParameterAttachments().stream().filter(java.util.Objects::nonNull)
            .filter(value -> value.getSlotIndex() == slot).map(NodeGraphData.ParameterAttachmentData::getParameterNodeId)
            .findFirst().orElse(null);
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private record Context(Map<String, NodeGraphData.NodeData> nodes,
                           Map<String, List<NodeGraphData.ConnectionData>> outgoing,
                           Set<String> incoming,
                           Map<String, NodeGraphData.RoutineDefinitionData> routines) { }

    private record Dependency(Set<String> assignments, Set<String> valueSources, Set<String> consumers) {
        private Dependency() { this(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>()); }
    }
}
