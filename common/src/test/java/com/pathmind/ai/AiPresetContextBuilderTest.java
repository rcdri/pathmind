package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPresetContextBuilderTest {
    @Test
    void publishesModesSlotsTraitsAndGenericAttachmentRules() {
        String prompt = AiPresetContextBuilder.systemPrompt(true, true);
        JsonObject contract = JsonParser.parseString(prompt.substring(prompt.lastIndexOf('\n') + 1)).getAsJsonObject();
        JsonArray nodes = contract.getAsJsonArray("availableNodes");

        assertFalse(nodes.isEmpty());
        for (var element : nodes) {
            JsonObject node = element.getAsJsonObject();
            assertTrue(node.has("defaultMode"));
            assertTrue(node.has("modes"));
            assertTrue(node.has("parameters"));
            assertTrue(node.has("parameterSlots"));
            assertTrue(node.has("providedTraits"));
            assertTrue(node.has("acceptsSensor"));
            assertTrue(node.has("acceptsAction"));
            assertTrue(node.has("sensorRequired"));
            assertTrue(node.has("actionRequired"));
        }
        assertTrue(contract.getAsJsonObject("attachmentRules").has("sensor"));
        assertTrue(contract.getAsJsonObject("attachmentRules").has("action"));
        assertTrue(contract.getAsJsonObject("attachmentRules").has("parameter"));
        assertFalse(prompt.contains("walk-then-jump"));
    }
}
