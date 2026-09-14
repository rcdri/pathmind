package com.pathmind.ai;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiParameterContractsTest {
    @Test void existingSparseHostReturnsAttachmentContextAndCanBeEditedWithoutChangingItsItem() {
        var created = AiGraphCommandEngine.apply(empty(), commands("""
            [{"kind":"add_node","ref":"craft","nodeType":"CRAFT"},
             {"kind":"add_node","ref":"item","nodeType":"PARAM_ITEM"},
             {"kind":"set_parameter","ref":"item","parameterId":"item","value":"minecraft:oak_planks"},
             {"kind":"set_parameter","ref":"craft","parameterId":"amount","value":"4"},
             {"kind":"attach_parameter","host":"craft","child":"item","slotIndex":0}]
            """), Map.of(), true, true);
        assertTrue(created.success(), created.message());
        JsonObject source = created.graph().deepCopy();
        for (var element : source.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (!"CRAFT".equals(node.get("type").getAsString())) continue;
            JsonArray sparse = new JsonArray();
            for (var parameter : node.getAsJsonArray("parameters")) {
                JsonObject field = parameter.getAsJsonObject();
                if ("amount".equals(field.get("id").getAsString())) {
                    field.remove("id"); // legacy serialized parameters use the display name
                    sparse.add(field);
                }
            }
            node.add("parameters", sparse);
        }
        var rejected = AiGraphCommandEngine.apply(source, commands("""
            [{"kind":"set_parameter","ref":"craft","parameterId":"item","value":"minecraft:oak_planks"}]
            """), created.references(), true, true);
        assertEquals("unknown_instance_parameter", rejected.errorCode());
        assertTrue(rejected.recoverable());
        var inspected = AiGraphQueryEngine.inspectSubgraph(source, commands("[\"craft\"]"), 0, created.references());
        assertTrue(inspected.toString().contains("sourceParameters"));
        assertTrue(inspected.toString().contains("minecraft:oak_planks"));
        var edited = AiGraphCommandEngine.apply(source, commands("""
            [{"kind":"configure_node","ref":"craft","mode":null,"parameterValues":[{"parameterId":"amount","value":"8"}]}]
            """), created.references(), true, true);
        assertTrue(edited.success(), edited.message());
        assertTrue(edited.actualValues().toString().contains("\"value\":\"8\""));
        assertTrue(edited.graph().toString().contains("minecraft:oak_planks"));
        assertEquals("4", source.getAsJsonArray("nodes").get(0).getAsJsonObject()
            .getAsJsonArray("parameters").get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test void overriddenLiteralRequiresAnExplicitEditToItsAttachedSource() {
        var created = AiGraphCommandEngine.apply(empty(), commands("""
            [{"kind":"add_node","ref":"host","nodeType":"CRAFT"},
             {"kind":"add_node","ref":"source","nodeType":"PARAM_ITEM"},
             {"kind":"set_parameter","ref":"source","parameterId":"item","value":"minecraft:oak_planks"},
             {"kind":"attach_parameter","host":"host","child":"source","slotIndex":0}]
            """), Map.of(), true, true);
        var rejected = AiGraphCommandEngine.apply(created.graph(), commands("""
            [{"kind":"set_parameter","ref":"host","parameterId":"item","value":"minecraft:birch_planks"}]
            """), created.references(), true, true);
        assertEquals("parameter_overridden", rejected.errorCode());
        assertTrue(rejected.message().contains(created.references().get("source")));
        var edited = AiGraphCommandEngine.apply(created.graph(), commands("""
            [{"kind":"set_parameter","ref":"source","parameterId":"item","value":"minecraft:birch_planks"}]
            """), created.references(), true, true);
        assertTrue(edited.success(), edited.message());
    }
    private static JsonObject empty() {
        return JsonParser.parseString("{\"nodes\":[],\"connections\":[],\"routines\":[]}").getAsJsonObject();
    }
    private static JsonArray commands(String text) { return JsonParser.parseString(text).getAsJsonArray(); }

    @Test void genericConfigurationPreservesParametersAcrossCompatibleModes() {
        var result = AiGraphCommandEngine.apply(empty(), commands("""
            [{"kind":"add_node","ref":"wait","nodeType":"WAIT"},
             {"kind":"configure_node","ref":"wait","mode":"WAIT_SECONDS","parameterValues":[{"parameterId":"duration","value":"5"}]},
             {"kind":"configure_node","ref":"wait","mode":"WAIT_TICKS","parameterValues":[]}]
            """), Map.of(), true, true);
        assertTrue(result.success(), result.message());
        assertTrue(result.actualValues().toString().contains("\"value\":\"5\""));
    }

    @Test void invalidNumericConfigurationRollsBackTheWholeBatch() {
        JsonObject source = empty();
        var result = AiGraphCommandEngine.apply(source, commands("""
            [{"kind":"add_node","ref":"distance","nodeType":"PARAM_DISTANCE"},
             {"kind":"configure_node","ref":"distance","mode":null,"parameterValues":[{"parameterId":"distance","value":"NaN"}]}]
            """), Map.of(), true, true);
        assertFalse(result.success());
        assertEquals("invalid_parameter_value", result.errorCode());
        assertTrue(source.getAsJsonArray("nodes").isEmpty());
    }

    @Test void contractDocumentationIncludesDomainAndUnits() {
        String craft = AiPresetContextBuilder.nodeContract(NodeType.CRAFT).toString();
        assertTrue(craft.contains("resource_identifier"));
        assertTrue(craft.contains("output items"));
        assertTrue(AiPresetContextBuilder.nodeContract(NodeType.WALK).toString().contains("blocks"));
    }

    @Test void attachedLiteralReadbackOverridesHostLiteral() {
        var result = AiGraphCommandEngine.apply(empty(), commands("""
            [{"kind":"add_node","ref":"craft","nodeType":"CRAFT"},
             {"kind":"add_node","ref":"item","nodeType":"PARAM_ITEM"},
             {"kind":"set_parameter","ref":"item","parameterId":"item","value":"minecraft:oak_planks"},
             {"kind":"attach_parameter","host":"craft","child":"item","slotIndex":0}]
            """), Map.of(), true, true);
        assertTrue(result.success(), result.message());
        NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(result.graph().toString());
        var host = graph.getNodes().stream().filter(n -> n.getId().equals(result.references().get("craft"))).findFirst().orElseThrow();
        var readback = AiConfiguredValues.read(graph, host, "item");
        assertEquals("stick", readback.literal());
        assertEquals("minecraft:oak_planks", readback.effective());
        assertTrue(readback.staticallyKnown());
    }
}
