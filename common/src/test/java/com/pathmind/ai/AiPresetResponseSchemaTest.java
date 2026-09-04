package com.pathmind.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPresetResponseSchemaTest {
    @Test
    void schemaIsClosedAndCoversTheFullSerializedGraph() {
        JsonObject schema = AiPresetResponseSchema.create();
        JsonObject definitions = schema.getAsJsonObject("$defs");

        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertTrue(schema.getAsJsonArray("required").toString().contains("graph"));
        assertTrue(definitions.has("graph"));
        assertTrue(definitions.has("node"));
        assertTrue(definitions.has("routine"));
        assertTrue(definitions.getAsJsonObject("node").getAsJsonObject("properties").has("parameterAttachments"));
        assertTrue(definitions.getAsJsonObject("node").getAsJsonObject("properties").has("templateGraph"));
    }

    @Test
    void officialOpenAiRequestsEnableStrictStructuredOutput() {
        AiPresetRequest request = new AiPresetRequest("system", "user", "model");
        JsonObject body = OpenAiCompatibleProvider.requestBody(request, AiProviderCapabilities.STRUCTURED_OUTPUT);
        JsonObject format = body.getAsJsonObject("response_format");

        assertEquals("json_schema", format.get("type").getAsString());
        assertTrue(format.getAsJsonObject("json_schema").get("strict").getAsBoolean());
        assertFalse(OpenAiCompatibleProvider.requestBody(request, AiProviderCapabilities.TEXT_ONLY).has("response_format"));
    }

    @Test
    void officialOpenAiRequestsUseTheSchemaSelectedByTheAgentTurn() {
        AiPresetRequest request = new AiPresetRequest("system", "user", "model",
            "pathmind_agent_action", AiAgentTurnSchema.create());

        JsonObject schema = OpenAiCompatibleProvider.requestBody(request, AiProviderCapabilities.STRUCTURED_OUTPUT)
            .getAsJsonObject("response_format").getAsJsonObject("json_schema");

        assertEquals("pathmind_agent_action", schema.get("name").getAsString());
        assertTrue(schema.getAsJsonObject("schema").getAsJsonObject("properties").has("tool"));
        assertFalse(schema.getAsJsonObject("schema").get("additionalProperties").getAsBoolean());
        assertTrue(schema.getAsJsonObject("schema").getAsJsonObject("properties").has("exampleId"));
        assertTrue(schema.getAsJsonObject("schema").getAsJsonObject("properties").has("draftRevision"));
        assertTrue(schema.getAsJsonObject("schema").getAsJsonObject("properties").has("nodeTypes"));
    }
}
