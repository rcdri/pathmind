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
