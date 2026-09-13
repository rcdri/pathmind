package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Fresh request-time editor facts. Node details remain available through focused tools. */
public final class AiWorkspaceContext {
    private AiWorkspaceContext() { }
    public static String attach(String conversation, String preset, NodeGraphData graph, Collection<String> selected) {
        JsonObject context = new JsonObject();
        context.add("conversation", conversation == null || conversation.isBlank() ? new JsonObject() : JsonParser.parseString(conversation));
        JsonObject workspace = new JsonObject();
        workspace.addProperty("authority", "Fresh editor facts for this request. Selection is a focus hint, not permission or a restriction on necessary related edits. Resolve 'that part' from selectedNodeIds; inspect_subgraph for relationships. Ask clarification only if materially different targets remain after using selection and available context; otherwise proceed with a stated reasonable assumption. Historical graph descriptions are not current state.");
        workspace.addProperty("presetName", preset);
        workspace.addProperty("fingerprint", AiPresetService.graphFingerprint(preset, graph));
        workspace.addProperty("nodeCount", graph == null ? 0 : graph.getNodes().size());
        workspace.addProperty("routineCount", graph == null || graph.getRoutines() == null ? 0 : graph.getRoutines().size());
        Set<String> ids = graph == null ? Set.of() : graph.getNodes().stream().map(NodeGraphData.NodeData::getId).collect(Collectors.toSet());
        var valid = selected.stream().filter(ids::contains).distinct().sorted().toList();
        JsonArray selection = new JsonArray(); valid.stream().limit(64).forEach(selection::add);
        workspace.add("selectedNodeIds", selection);
        workspace.addProperty("additionalSelectedNodes", Math.max(0, valid.size() - 64));
        context.add("workspace", workspace);
        return context.toString();
    }
}
