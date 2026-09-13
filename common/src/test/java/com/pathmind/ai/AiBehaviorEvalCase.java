package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Public, versioned behavioral requests used only by the evaluation harness, never prompt routing. */
record AiBehaviorEvalCase(String id, String prompt, String difficulty, String target, String fixture, JsonObject expected, JsonObject context) {
    AiBehaviorEvalCase(String id, String prompt, String difficulty, String target, String fixture, JsonObject expected) {
        this(id, prompt, difficulty, target, fixture, expected, new JsonObject());
    }
    static List<AiBehaviorEvalCase> load() {
        List<AiBehaviorEvalCase> cases = new ArrayList<>();
        for (String resource : List.of("behavior-cases.json", "lifecycle-cases.json")) {
        try (var stream = AiBehaviorEvalCase.class.getResourceAsStream("/ai-evals/" + resource)) {
            if (stream == null) throw new IllegalStateException("Missing behavior corpus.");
            for (var element : JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject item = element.getAsJsonObject();
                cases.add(new AiBehaviorEvalCase(item.get("id").getAsString(), item.get("prompt").getAsString(),
                    item.get("difficulty").getAsString(), item.get("target").getAsString(),
                    item.has("fixture") ? item.get("fixture").getAsString() : "", item.getAsJsonObject("expected"),
                    item.has("context") ? item.getAsJsonObject("context") : new JsonObject()));
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read behavior corpus.", exception);
        }
        }
        return List.copyOf(cases);
    }

    NodeGraphData activeGraph() {
        if (fixture.isBlank()) return null;
        if (fixture.equals("reported-walk-jump")) return AiReportedWorkflowFixture.original();
        if (fixture.equals("jump")) {
            var start = new NodeGraphData.NodeData("eval-start", NodeType.START, null, 0, 0, new ArrayList<>());
            var jump = new NodeGraphData.NodeData("eval-jump", NodeType.JUMP, null, 200, 0, new ArrayList<>());
            return new NodeGraphData(new ArrayList<>(List.of(start, jump)),
                new ArrayList<>(List.of(new NodeGraphData.ConnectionData(start.getId(), jump.getId(), 0, 0))));
        }
        var entry = AiGoldenGraphLibrary.find(fixture).orElseThrow(() -> new IllegalArgumentException("Unknown eval fixture " + fixture));
        // Each run gets its own fixture; never share or alter a cached library graph.
        return NodeGraphPersistence.parseNodeGraphData(new com.google.gson.Gson().toJson(entry.graph()));
    }
}
