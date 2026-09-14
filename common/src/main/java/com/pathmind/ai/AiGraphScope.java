package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import java.util.Map;

/** Explicit graph scope prevents accidental cross-routine edits and connections. */
final class AiGraphScope {
    private AiGraphScope() { }
    static NodeGraphData resolve(NodeGraphData root, String ref, Map<String, String> aliases) {
        if (ref == null || ref.isBlank() || "root".equals(ref)) return root;
        String id = aliases.getOrDefault(ref, ref);
        NodeGraphData found = routine(root, id, 0);
        if (found == null) throw new IllegalArgumentException("Unknown routine graph '" + ref + "'. Inspect the preset for routine IDs; graphRef=null selects root.");
        return found;
    }
    private static NodeGraphData routine(NodeGraphData graph, String id, int depth) {
        if (graph == null || graph.getRoutines() == null || depth > 16) return null;
        for (var routine : graph.getRoutines()) {
            if (routine == null) continue;
            if (id.equals(routine.getId())) return routine.getGraph();
            var nested = routine(routine.getGraph(), id, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }
    static NodeGraphData containing(NodeGraphData graph, String nodeId) { return containing(graph, nodeId, 0); }
    private static NodeGraphData containing(NodeGraphData graph, String id, int depth) {
        if (graph == null || depth > 16) return null;
        if (graph.getNodes() != null && graph.getNodes().stream().anyMatch(n -> n != null && id.equals(n.getId()))) return graph;
        if (graph.getRoutines() != null) for (var routine : graph.getRoutines()) {
            if (routine == null) continue;
            var found = containing(routine.getGraph(), id, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
