package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Structural behavior grader, not a claim that Minecraft/world behavior has been simulated. */
final class AiBehaviorEvalGrader {
    private AiBehaviorEvalGrader() { }

    static Grade gradeRun(AiBehaviorEvalCase test, AiRunReport run) {
        var grade = grade(test, run.proposal());
        var failures = new ArrayList<>(grade.failures());
        for (var group : array(test.expected(), "requiredToolGroups")) {
            var names = new HashSet<String>(); for (var name : group.getAsJsonArray()) names.add(name.getAsString());
            if (run.trace().stream().noneMatch(t -> t.success() && names.contains(t.tool()))) failures.add("Missing successful inspection tool from " + names);
        }
        return new Grade(grade.validationPassed(), grade.behaviorPresent() && failures.size() == grade.failures().size(),
            grade.unexpectedNodeCount(), grade.nodeBudgetExceeded(), List.copyOf(failures));
    }

    static Grade grade(AiBehaviorEvalCase test, AiPresetService.Proposal proposal) {
        List<String> failures = new ArrayList<>();
        if (proposal == null) return new Grade(false, false, 0, false, List.of("No proposal produced."));
        boolean behavior = test.target().equals(proposal.target());
        if (!behavior) failures.add("Expected target " + test.target() + "; got " + proposal.target() + ".");
        AiPresetService.Validation validation = AiPresetService.validateProposal(proposal, "Eval fixture", true, true);
        boolean valid = validation.valid();
        if (!valid) validation.issues().forEach(issue -> failures.add("Validation: " + issue.message()));
        if (test.expected().has("outcome") && !proposal.outcome().name().equals(test.expected().get("outcome").getAsString())) {
            behavior = false; failures.add("Wrong completion outcome: " + proposal.outcome());
        }
        if (proposal.response().length() < integer(test.expected(), "minResponseChars", 0)) {
            behavior = false; failures.add("Response shorter than required complete explanation.");
        }
        int minimumResponse = integer(test.expected(), "minResponseChars", 0);
        int maximumResponse = integer(test.expected(), "maxResponseChars", minimumResponse > 0 ? Math.max(4_000, minimumResponse) : 600);
        if (proposal.response().length() > maximumResponse) {
            behavior = false; failures.add("Response exceeds concise answer budget: " + proposal.response().length() + " > " + maximumResponse + ".");
        }
        if (test.target().equals("inspect")) {
            if (!test.expected().has("outcome") && proposal.outcome() != AiCompletionOutcome.ANSWER) { behavior = false; failures.add("Inspection did not complete with an answer."); }
            if (proposal.graph() != null) { behavior = false; failures.add("Inspection returned a graph mutation."); }
            for (JsonElement text : array(test.expected(), "responseContains")) {
                if (!proposal.response().toLowerCase(Locale.ROOT).contains(text.getAsString().toLowerCase(Locale.ROOT))) {
                    behavior = false;
                    failures.add("Response missing concept: " + text.getAsString());
                }
            }
            return new Grade(valid, behavior, 0, false, List.copyOf(failures));
        }
        if (proposal.graph() == null) return new Grade(false, false, 0, false, List.of("Graph missing."));
        JsonObject expected = test.expected();
        List<ScopedNode> reachable = reachable(proposal.graph());
        List<ScopedNode> all = allNodes(proposal.graph());
        int priorFailures = failures.size();
        if (expected.has("preserveFixture") && expected.get("preserveFixture").getAsBoolean()) {
            var original = test.activeGraph();
            for (var old : original.getNodes()) {
                var retained = proposal.graph().getNodes().stream().filter(n -> old.getId().equals(n.getId())).findFirst();
                if (retained.isEmpty() || !semanticNode(old).equals(semanticNode(retained.get()))) failures.add("Original node changed or removed: " + old.getId());
            }
            for (var edge : original.getConnections()) {
                if (proposal.graph().getConnections().stream().noneMatch(e -> new com.google.gson.Gson().toJson(e).equals(new com.google.gson.Gson().toJson(edge))))
                    failures.add("Original flow connection removed.");
            }
        }
        for (var element : array(expected, "sequences")) {
            List<String> types = new ArrayList<>(); for (var type : element.getAsJsonArray()) types.add(type.getAsString());
            if (reachable.stream().noneMatch(node -> sequenceMatches(node.graph(), node.node(), types, 0))) failures.add("Missing ordered flow: " + types);
        }
        for (var element : array(expected, "sharedVariables")) {
            var dependency = element.getAsJsonObject();
            boolean shared = reachable.stream().filter(n -> typeMatches(n, dependency, "writer"))
                .anyMatch(writer -> reachable.stream().filter(n -> n.graph() == writer.graph() && typeMatches(n, dependency, "readerHost"))
                    .anyMatch(reader -> sameVariable(writer.node(), reader.node(), writer.graph(), integer(dependency, "readerSlot", 0))));
            if (!shared) failures.add("Missing shared variable write/read dependency: " + dependency);
        }
        if (expected.has("requiredNodes")) for (var count : expected.getAsJsonObject("requiredNodes").entrySet()) {
            long actual = reachable.stream().filter(node -> node.node().getType().name().equals(count.getKey())).count();
            if (actual < count.getValue().getAsInt()) failures.add("Missing reachable " + count.getKey() + ": wanted " + count.getValue() + ", got " + actual);
        }
        for (JsonElement element : array(expected, "parameters")) {
            JsonObject parameter = element.getAsJsonObject();
            long matches = reachable.stream().filter(node -> typeMatches(node, parameter, "type"))
                .filter(node -> parameterMatches(node.node(), parameter.get("name").getAsString(), parameter.get("value").getAsString())).count();
            if (matches < integer(parameter, "count", 1)) failures.add("Missing parameter behavior: " + parameter);
        }
        for (JsonElement element : array(expected, "modes")) {
            JsonObject mode = element.getAsJsonObject();
            if (reachable.stream().noneMatch(node -> typeMatches(node, mode, "type") && node.node().getMode() != null
                && node.node().getMode().name().equals(mode.get("mode").getAsString()))) failures.add("Missing mode: " + mode);
        }
        for (JsonElement element : array(expected, "relations")) {
            JsonObject relation = element.getAsJsonObject();
            long matches = reachable.stream().filter(node -> typeMatches(node, relation, "host"))
                .filter(node -> relationshipMatches(node, relation)).count();
            if (matches < integer(relation, "count", 1)) failures.add("Missing structural relationship: " + relation);
        }
        for (JsonElement element : array(expected, "messageContains")) {
            String text = element.getAsString().toLowerCase(Locale.ROOT);
            if (reachable.stream().noneMatch(node -> node.node().getMessageLines() != null
                && node.node().getMessageLines().stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(text)))) {
                failures.add("Missing reachable message text: " + text);
            }
        }
        List<NodeGraphData.RoutineDefinitionData> routines = proposal.graph().getRoutines();
        if (expected.has("routineCount") && routines.size() != expected.get("routineCount").getAsInt()) failures.add("Wrong routine definition count.");
        for (JsonElement element : array(expected, "routineInputKinds")) {
            if (routines.stream().flatMap(routine -> routine.getInputs().stream())
                .noneMatch(input -> element.getAsString().equals(input.getValueKind()))) failures.add("Missing typed routine argument: " + element);
        }
        behavior &= failures.size() == priorFailures;
        Set<String> allowed = new HashSet<>();
        for (JsonElement element : array(expected, "allowedNodes")) allowed.add(element.getAsString());
        int unexpected = 0;
        Set<ScopedNode> live = new HashSet<>(reachable);
        for (ScopedNode node : all) {
            if ((!allowed.isEmpty() && !allowed.contains(node.node().getType().name())) || !live.contains(node)) unexpected++;
        }
        if (unexpected > 0) failures.add(unexpected + " unexpected or disconnected node(s).");
        boolean overBudget = all.size() > integer(expected, "maxNodes", Integer.MAX_VALUE);
        if (overBudget) failures.add("Node budget exceeded: " + all.size());
        return new Grade(valid, behavior, unexpected, overBudget, List.copyOf(failures));
    }

    private static boolean parameterMatches(NodeGraphData.NodeData node, String name, String value) {
        if (node.getParameters() == null) return false;
        String key = NodeParameter.createDefaultId(name);
        return node.getParameters().stream().anyMatch(parameter -> parameter != null
            && (key.equals(NodeParameter.createDefaultId(parameter.getId() == null ? "" : parameter.getId()))
                || key.equals(NodeParameter.createDefaultId(parameter.getName() == null ? "" : parameter.getName())))
            && equivalent(value, parameter.getValue()));
    }

    private static JsonObject semanticNode(NodeGraphData.NodeData node) {
        var json = new com.google.gson.Gson().toJsonTree(node).getAsJsonObject(); json.remove("x"); json.remove("y"); return json;
    }
    private static boolean sequenceMatches(NodeGraphData graph, NodeGraphData.NodeData node, List<String> types, int index) {
        if (types.isEmpty() || node.getType() == null || !types.get(index).equals(node.getType().name())) return false;
        if (index == types.size() - 1) return true;
        return graph.getConnections().stream().filter(e -> node.getId().equals(e.getOutputNodeId()))
            .anyMatch(e -> graph.getNodes().stream().filter(n -> e.getInputNodeId().equals(n.getId())).anyMatch(n -> sequenceMatches(graph, n, types, index + 1)));
    }
    private static boolean sameVariable(NodeGraphData.NodeData writer, NodeGraphData.NodeData reader, NodeGraphData graph, int readerSlot) {
        String writeName = variableName(writer, graph, 0), readName = variableName(reader, graph, readerSlot);
        return writeName != null && writeName.equals(readName);
    }
    private static String variableName(NodeGraphData.NodeData host, NodeGraphData graph, int slot) {
        if (host.getParameterAttachments() == null) return null;
        for (var attachment : host.getParameterAttachments()) if (attachment.getSlotIndex() == slot) {
            for (var node : graph.getNodes()) if (attachment.getParameterNodeId().equals(node.getId()) && node.getType() == NodeType.VARIABLE) {
                for (var parameter : node.getParameters()) if ("variable".equals(NodeParameter.createDefaultId(parameter.getId()))) return parameter.getValue();
            }
        }
        return null;
    }

    private static boolean equivalent(String expected, String actual) {
        if (actual == null) return false;
        if (expected.equalsIgnoreCase(actual)) return true;
        try { return new java.math.BigDecimal(expected).compareTo(new java.math.BigDecimal(actual)) == 0; }
        catch (NumberFormatException exception) { return false; }
    }

    private static boolean relationshipMatches(ScopedNode scoped, JsonObject relation) {
        NodeGraphData.NodeData node = scoped.node();
        String kind = relation.get("kind").getAsString();
        if (kind.equals("flow")) return scoped.graph().getConnections().stream().anyMatch(edge -> edge != null
            && node.getId().equals(edge.getOutputNodeId()) && childType(scoped.graph(), edge.getInputNodeId(), relation)
            && (!relation.has("socket") || relation.get("socket").getAsInt() == edge.getOutputSocket())
            && (!relation.has("inputSocket") || relation.get("inputSocket").getAsInt() == edge.getInputSocket()));
        if (kind.equals("action")) return childType(scoped.graph(), node.getAttachedActionId(), relation);
        if (kind.equals("sensor")) return childType(scoped.graph(), node.getAttachedSensorId(), relation);
        if (kind.equals("parameter")) return node.getParameterAttachments().stream().anyMatch(attachment -> attachment != null
            && childType(scoped.graph(), attachment.getParameterNodeId(), relation)
            && (!relation.has("slot") || relation.get("slot").getAsInt() == attachment.getSlotIndex()));
        return false;
    }

    private static boolean childType(NodeGraphData graph, String id, JsonObject relation) {
        return id != null && graph.getNodes().stream().anyMatch(child -> child != null && id.equals(child.getId())
            && relation.get("child").getAsString().equals(child.getType().name()));
    }

    private static boolean typeMatches(ScopedNode node, JsonObject expected, String field) {
        return node.node().getType() != null && node.node().getType().name().equals(expected.get(field).getAsString());
    }

    private static List<ScopedNode> reachable(NodeGraphData root) {
        List<ScopedNode> nodes = new ArrayList<>();
        Set<ScopedNode> visited = new HashSet<>();
        Map<String, NodeGraphData.RoutineDefinitionData> routines = new HashMap<>();
        root.getRoutines().forEach(routine -> routines.put(routine.getId(), routine));
        visit(root, Set.of(NodeType.START, NodeType.START_CHAIN), routines, visited, nodes);
        return nodes;
    }

    private static void visit(NodeGraphData graph, Set<NodeType> entryTypes,
                              Map<String, NodeGraphData.RoutineDefinitionData> routines,
                              Set<ScopedNode> visited, List<ScopedNode> result) {
        Map<String, NodeGraphData.NodeData> byId = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        graph.getNodes().forEach(node -> { if (node != null) { byId.put(node.getId(), node); if (entryTypes.contains(node.getType())) queue.add(node.getId()); } });
        while (!queue.isEmpty()) {
            NodeGraphData.NodeData node = byId.get(queue.removeFirst());
            if (node == null) continue;
            ScopedNode scoped = new ScopedNode(graph, node);
            if (!visited.add(scoped)) continue;
            result.add(scoped);
            for (String child : new String[] {node.getAttachedActionId(), node.getAttachedSensorId()}) if (child != null) queue.add(child);
            if (node.getParameterAttachments() != null) node.getParameterAttachments().forEach(attachment -> {
                if (attachment != null && attachment.getParameterNodeId() != null) queue.add(attachment.getParameterNodeId());
            });
            graph.getConnections().forEach(edge -> { if (edge != null && node.getId().equals(edge.getOutputNodeId())) queue.add(edge.getInputNodeId()); });
            if (node.getType() == NodeType.ROUTINE_CALL && routines.containsKey(node.getRoutineId())) {
                visit(routines.get(node.getRoutineId()).getGraph(), Set.of(NodeType.ROUTINE_ENTRY), routines, visited, result);
            }
        }
    }

    private static List<ScopedNode> allNodes(NodeGraphData graph) {
        List<ScopedNode> nodes = new ArrayList<>();
        if (graph.getNodes() != null) graph.getNodes().forEach(node -> { if (node != null) nodes.add(new ScopedNode(graph, node)); });
        graph.getRoutines().forEach(routine -> nodes.addAll(allNodes(routine.getGraph())));
        return nodes;
    }

    private static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
    private static int integer(JsonObject object, String key, int fallback) { return object.has(key) ? object.get(key).getAsInt() : fallback; }
    private record ScopedNode(NodeGraphData graph, NodeGraphData.NodeData node) { }

    record Grade(boolean validationPassed, boolean behaviorPresent, int unexpectedNodeCount,
                 boolean nodeBudgetExceeded, List<String> failures) {
        boolean passed() { return validationPassed && behaviorPresent && unexpectedNodeCount == 0 && !nodeBudgetExceeded; }
    }
}
