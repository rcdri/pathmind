package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Runs model-selected tools against an isolated graph draft, never the live editor graph. */
public final class AiPresetAgent {
    static final int MAX_TURNS = 14;
    private static final int MAX_TRANSCRIPT_CHARS = 140_000;
    private static final Gson GSON = new Gson();

    private AiPresetAgent() {
    }

    public static CompletableFuture<AiPresetService.Proposal> run(AiProvider provider, String model, String userPrompt,
                                                                   String conversation, NodeGraphData activeGraph,
                                                                   String activePresetName, boolean baritoneAvailable,
                                                                   boolean uiUtilsAvailable) {
        State state = new State(provider, model, userPrompt, conversation, activeGraph, activePresetName,
            baritoneAvailable, uiUtilsAvailable);
        return next(state);
    }

    private static CompletableFuture<AiPresetService.Proposal> next(State state) {
        if (state.turn >= MAX_TURNS) {
            return CompletableFuture.failedFuture(new IllegalStateException("AI stopped after " + MAX_TURNS + " tool turns without producing a valid proposal."));
        }
        state.turn++;
        AiPresetRequest request = new AiPresetRequest(systemPrompt(state.provider.capabilities()), state.prompt(), state.model,
            "pathmind_agent_action", AiAgentTurnSchema.create());
        return state.provider.generate(request).thenCompose(content -> {
            try {
                JsonObject action = parseObject(content);
                state.record("ASSISTANT_ACTION", action);
                ToolResult result = execute(state, action);
                if (result.proposal != null) return CompletableFuture.completedFuture(result.proposal);
                state.record("TOOL_RESULT", result.payload);
                return next(state);
            } catch (RuntimeException exception) {
                state.record("TOOL_ERROR", error(exception.getMessage()));
                return next(state);
            }
        });
    }

    private static ToolResult execute(State state, JsonObject action) {
        String tool = string(action, "tool", "");
        return switch (tool) {
            case "select_target" -> selectTarget(state, action);
            case "inspect_preset" -> inspectPreset(state);
            case "list_node_types" -> listNodeTypes(state);
            case "describe_node_types" -> describeNodes(state, action);
            case "list_examples" -> listExamples();
            case "inspect_example" -> inspectExample(action);
            case "apply_graph_patch" -> applyPatch(state, action);
            case "validate_graph" -> validateGraph(state);
            case "preview_execution" -> previewExecution(state);
            case "finish" -> finish(state, action);
            default -> ToolResult.more(error("Unknown tool '" + tool + "'."));
        };
    }

    private static ToolResult selectTarget(State state, JsonObject action) {
        if (state.target != null) return ToolResult.more(error("The target is already selected as '" + state.target + "'."));
        String target = nullableString(action, "target");
        if (!"new".equals(target) && !"current".equals(target) && !"inspect".equals(target)) {
            return ToolResult.more(error("Select target new, current, or inspect."));
        }
        if ("current".equals(target) && state.activeGraph == null) {
            return ToolResult.more(error("There is no open graph to edit."));
        }
        state.target = target;
        if ("current".equals(target)) state.workingGraph = graphJson(state.activeGraph);
        else if ("new".equals(target)) state.workingGraph = emptyGraph();
        JsonObject result = ok("Target selected: " + target + ".");
        result.addProperty("draftRevision", state.draftRevision);
        result.addProperty("next", "Inspect the preset or node contracts, then patch and validate. Inspection targets may inspect and finish.");
        return ToolResult.more(result);
    }

    private static ToolResult inspectPreset(State state) {
        state.inspected = true;
        JsonObject result = ok("Returned the open preset and current draft.");
        result.addProperty("draftRevision", state.draftRevision);
        result.add("activePreset", AiExecutionPreview.inspect(state.activePresetName, state.activeGraph));
        if (state.workingGraph != null) result.add("workingDraft", AiExecutionPreview.inspect(
            "current".equals(state.target) ? state.activePresetName : "New preset", parseGraph(state.workingGraph)));
        return ToolResult.more(result);
    }

    private static ToolResult listNodeTypes(State state) {
        JsonArray contracts = AiPresetContextBuilder.availableNodeContracts(state.baritoneAvailable, state.uiUtilsAvailable);
        JsonArray resultNodes = new JsonArray();
        for (JsonElement element : contracts) {
            JsonObject contract = element.getAsJsonObject();
            JsonObject summary = new JsonObject();
            summary.add("type", contract.get("type"));
            summary.add("name", contract.get("name"));
            summary.add("description", contract.get("description"));
            summary.add("category", contract.get("category"));
            resultNodes.add(summary);
        }
        JsonObject result = ok("Returned " + resultNodes.size() + " available node types.");
        result.add("nodes", resultNodes);
        return ToolResult.more(result);
    }

    private static ToolResult describeNodes(State state, JsonObject action) {
        if (!action.has("nodeTypes") || !action.get("nodeTypes").isJsonArray() || action.getAsJsonArray("nodeTypes").isEmpty()) {
            return ToolResult.more(error("describe_node_types requires one or more nodeTypes."));
        }
        if (action.getAsJsonArray("nodeTypes").size() > 12) return ToolResult.more(error("Describe at most 12 node types per call."));
        JsonArray contracts = new JsonArray();
        java.util.Set<NodeType> requested = new java.util.LinkedHashSet<>();
        for (JsonElement element : action.getAsJsonArray("nodeTypes")) {
            try {
                NodeType type = NodeType.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
                JsonObject contract = AiPresetContextBuilder.nodeContract(type);
                if (contract == null || !containsAvailableType(state, type)) {
                    return ToolResult.more(error("Node type " + type + " is unavailable in this installation."));
                }
                requested.add(type);
                contracts.add(contract);
            } catch (IllegalArgumentException exception) {
                return ToolResult.more(error("Unknown node type '" + element + "'. Use list_node_types."));
            }
        }
        JsonObject result = ok("Returned " + contracts.size() + " exact node contract(s).");
        result.add("contracts", contracts);
        result.add("relevantExamples", AiGoldenGraphLibrary.listMatching(requested));
        return ToolResult.more(result);
    }

    private static ToolResult listExamples() {
        JsonArray examples = AiGoldenGraphLibrary.list();
        JsonObject result = ok("Returned " + examples.size() + " curated graph examples.");
        result.add("examples", examples);
        return ToolResult.more(result);
    }

    private static ToolResult inspectExample(JsonObject action) {
        String exampleId = nullableString(action, "exampleId");
        return AiGoldenGraphLibrary.find(exampleId).map(entry -> {
            JsonObject result = ok("Returned a bundled serialized graph example.");
            result.add("metadata", AiGoldenGraphLibrary.summary(entry));
            result.add("graph", graphJson(entry.graph()));
            return ToolResult.more(result);
        }).orElseGet(() -> ToolResult.more(error("Unknown example id. Use list_examples.")));
    }

    private static ToolResult applyPatch(State state, JsonObject action) {
        if (!state.changesGraph()) return ToolResult.more(error("Select target new or current before editing."));
        Integer requestedRevision = nullableInteger(action, "draftRevision");
        if (requestedRevision == null || requestedRevision != state.draftRevision) {
            JsonObject result = error("Draft revision mismatch; inspect the draft and retry against the current revision.");
            result.addProperty("draftRevision", state.draftRevision);
            return ToolResult.more(result);
        }
        JsonArray operations = action.has("operations") && action.get("operations").isJsonArray()
            ? action.getAsJsonArray("operations") : new JsonArray();
        AiGraphPatchEngine.Result patch = AiGraphPatchEngine.apply(state.workingGraph, operations);
        if (!patch.success()) return ToolResult.more(error(patch.message()));
        try {
            parseGraph(patch.graph());
        } catch (RuntimeException exception) {
            return ToolResult.more(error("Patch did not produce a readable graph: " + exception.getMessage()));
        }
        state.workingGraph = patch.graph();
        state.validated = false;
        state.previewed = false;
        state.draftRevision++;
        JsonObject result = ok(patch.message());
        result.addProperty("draftRevision", state.draftRevision);
        result.addProperty("nodeCount", state.workingGraph.getAsJsonArray("nodes").size());
        result.addProperty("connectionCount", state.workingGraph.getAsJsonArray("connections").size());
        return ToolResult.more(result);
    }

    private static ToolResult validateGraph(State state) {
        if (!state.changesGraph()) return ToolResult.more(error("Select target new or current before validation."));
        NodeGraphData graph = parseGraph(state.workingGraph);
        AiPresetService.Proposal temporary = new AiPresetService.Proposal(
            "Draft", "", List.of(), graph, state.target);
        AiPresetService.Validation validation = AiPresetService.validateProposal(temporary, state.activePresetName,
            state.baritoneAvailable, state.uiUtilsAvailable);
        state.validated = validation.valid();
        JsonObject result = new JsonObject();
        result.addProperty("ok", validation.valid());
        result.addProperty("message", validation.valid() ? "The draft passes serialized and runtime validation." : "Repair the listed issues, then validate again.");
        JsonArray issues = new JsonArray();
        validation.issues().forEach(issue -> issues.add(GSON.toJsonTree(issue)));
        result.add("issues", issues);
        result.addProperty("draftRevision", state.draftRevision);
        return ToolResult.more(result);
    }

    private static ToolResult previewExecution(State state) {
        if (!state.changesGraph()) return ToolResult.more(error("Select target new or current before previewing."));
        if (!state.validated) return ToolResult.more(error("Validate the current draft before previewing it."));
        state.previewed = true;
        JsonObject result = ok("Generated a structural preview without executing world actions.");
        result.add("preview", AiExecutionPreview.preview(parseGraph(state.workingGraph)));
        return ToolResult.more(result);
    }

    private static ToolResult finish(State state, JsonObject action) {
        if (state.target == null) return ToolResult.more(error("Call select_target before finishing."));
        if ("inspect".equals(state.target)) {
            if (!state.inspected) return ToolResult.more(error("Inspect the preset before finishing an inspection request."));
            return ToolResult.done(proposal(state, action, null, "inspect"));
        }
        if (!state.validated) return ToolResult.more(error("The current draft must pass validate_graph after its last patch."));
        if (!state.previewed) return ToolResult.more(error("Call preview_execution after validation before finishing."));
        return ToolResult.done(proposal(state, action, parseGraph(state.workingGraph), state.target));
    }

    private static AiPresetService.Proposal proposal(State state, JsonObject action, NodeGraphData graph, String target) {
        String title = shortText(nullableString(action, "title"), 80);
        if (title.isBlank()) title = "Untitled AI preset";
        String response = shortText(nullableString(action, "response"), 280);
        List<String> workLog = new ArrayList<>();
        if (action.has("workLog") && action.get("workLog").isJsonArray()) {
            for (JsonElement item : action.getAsJsonArray("workLog")) {
                if (workLog.size() >= 4) break;
                if (item.isJsonPrimitive()) workLog.add(shortText(item.getAsString(), 120));
            }
        }
        String sourceFingerprint = "current".equals(target)
            ? AiPresetService.graphFingerprint(state.activePresetName, state.activeGraph) : "";
        AiProposalReview review = graph == null ? null : AiProposalReview.create(
            "current".equals(target) ? state.activeGraph : null, graph);
        return new AiPresetService.Proposal(title, response, List.copyOf(workLog), graph, target,
            sourceFingerprint, review);
    }

    private static boolean containsAvailableType(State state, NodeType type) {
        for (JsonElement element : AiPresetContextBuilder.availableNodeContracts(state.baritoneAvailable, state.uiUtilsAvailable)) {
            if (type.name().equals(element.getAsJsonObject().get("type").getAsString())) return true;
        }
        return false;
    }

    private static NodeGraphData parseGraph(JsonObject json) {
        NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(json.toString());
        if (graph == null) throw new IllegalArgumentException("Graph JSON could not be parsed.");
        return graph;
    }

    private static JsonObject graphJson(NodeGraphData graph) {
        return GSON.toJsonTree(graph).getAsJsonObject();
    }

    private static JsonObject emptyGraph() {
        JsonObject graph = new JsonObject();
        graph.add("nodes", new JsonArray());
        graph.add("connections", new JsonArray());
        graph.add("customNodeDefinition", null);
        graph.add("routines", new JsonArray());
        return graph;
    }

    private static JsonObject parseObject(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (newline >= 0 && closing > newline) value = value.substring(newline + 1, closing).trim();
        }
        JsonElement parsed = JsonParser.parseString(value);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Agent action must be a JSON object.");
        return parsed.getAsJsonObject();
    }

    private static String systemPrompt(AiProviderCapabilities capabilities) {
        String prompt = "You are Pathmind's graph agent. Work through one tool action per response. Never emit a complete graph directly. "
            + "First call select_target. Use inspect for questions/diagnosis, current only for an explicit change to the open preset, and new for a standalone preset. "
            + "Available tools: inspect_preset returns the exact open graph and draft; list_node_types lists creatable types; describe_node_types accepts nodeTypes and returns exact sockets, modes, parameters, attachment contracts, and relevant examples; "
            + "list_examples summarizes curated working graphs and inspect_example returns one exact serialized example; "
            + "apply_graph_patch applies small RFC-6902-style add/remove/replace operations to the isolated draft; validate_graph runs Pathmind's real validators; preview_execution returns bounded structural paths; finish returns the reviewed result. "
            + "Patch paths are JSON Pointers rooted at /nodes, /connections, /routines, or /customNodeDefinition. Append array items with /-. valueJson is a JSON-encoded string and is null only for remove. Every patch must include the latest draftRevision from a tool result. "
            + "Inspect contracts before using unfamiliar nodes. Prefer several small patches. After any patch, validate and repair every issue. After validation succeeds, preview, then finish. "
            + "Never claim a tool succeeded until its result says ok. Do not reveal hidden reasoning; workLog contains only concise user-visible actions. "
            + "Every response must match the action schema; set unused nullable fields to null and unused arrays to [].";
        if (capabilities == null || !capabilities.structuredOutput()) {
            prompt += " This transport cannot enforce the schema, so follow this exact schema:\n" + AiAgentTurnSchema.create();
        }
        return prompt;
    }

    private static JsonObject ok(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", message);
        return result;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("message", message == null || message.isBlank() ? "Tool call failed." : message);
        return result;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String nullableString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static Integer nullableInteger(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : null;
    }

    private static String shortText(String value, int limit) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(1, limit - 1)) + "…";
    }

    private static final class State {
        private final AiProvider provider;
        private final String model;
        private final String userPrompt;
        private final String conversation;
        private final NodeGraphData activeGraph;
        private final String activePresetName;
        private final boolean baritoneAvailable;
        private final boolean uiUtilsAvailable;
        private final StringBuilder transcript = new StringBuilder();
        private String target;
        private JsonObject workingGraph;
        private boolean inspected;
        private boolean validated;
        private boolean previewed;
        private int draftRevision;
        private int turn;

        private State(AiProvider provider, String model, String userPrompt, String conversation,
                      NodeGraphData activeGraph, String activePresetName,
                      boolean baritoneAvailable, boolean uiUtilsAvailable) {
            this.provider = provider;
            this.model = model;
            this.userPrompt = userPrompt == null ? "" : userPrompt;
            this.conversation = conversation == null ? "" : conversation;
            this.activeGraph = activeGraph;
            this.activePresetName = activePresetName == null ? "" : activePresetName;
            this.baritoneAvailable = baritoneAvailable;
            this.uiUtilsAvailable = uiUtilsAvailable;
        }

        private boolean changesGraph() { return "new".equals(target) || "current".equals(target); }

        private String prompt() {
            StringBuilder prompt = new StringBuilder();
            prompt.append("USER_REQUEST:\n").append(userPrompt).append('\n');
            if (!conversation.isBlank()) prompt.append("CONVERSATION_CONTEXT:\n").append(conversation).append('\n');
            prompt.append("TOOL_TRANSCRIPT:\n").append(transcript);
            return prompt.toString();
        }

        private void record(String label, JsonObject payload) {
            transcript.append(label).append('[').append(turn).append("]:").append(payload).append('\n');
            if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
                int remove = transcript.length() - MAX_TRANSCRIPT_CHARS;
                int newline = transcript.indexOf("\n", remove);
                transcript.delete(0, newline < 0 ? remove : newline + 1);
            }
        }
    }

    private record ToolResult(JsonObject payload, AiPresetService.Proposal proposal) {
        static ToolResult more(JsonObject payload) { return new ToolResult(payload, null); }
        static ToolResult done(AiPresetService.Proposal proposal) { return new ToolResult(null, proposal); }
    }
}
