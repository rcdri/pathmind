package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** OpenAI Chat Completions-compatible adapter; all network work stays off the game thread. */
public final class OpenAiCompatibleProvider implements AiProvider {
    private final String endpoint;
    private final String apiKey;
    private final AiProviderCapabilities capabilities;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public OpenAiCompatibleProvider(String endpoint, String apiKey) {
        this(endpoint, apiKey, AiProviderCapabilities.TEXT_ONLY);
    }

    public OpenAiCompatibleProvider(String endpoint, String apiKey, AiProviderCapabilities capabilities) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.capabilities = capabilities == null ? AiProviderCapabilities.TEXT_ONLY : capabilities;
    }

    public static OpenAiResponsesProvider officialOpenAi(String endpoint, String apiKey) {
        return new OpenAiResponsesProvider(endpoint, apiKey);
    }

    @Override
    public AiProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public CompletableFuture<String> generate(AiPresetRequest request) {
        return generateMeasured(request).thenApply(AiModelTurn::action);
    }

    @Override
    public String providerId() { return "openai_compatible"; }

    @Override
    public AiProviderSession openSession() { return (request, previousToolResult) -> generateMeasured(request); }

    private CompletableFuture<AiModelTurn> generateMeasured(AiPresetRequest request) {
        JsonObject body = requestBody(request, capabilities);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(75))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
            JsonObject usage = json.getAsJsonObject("usage");
            JsonObject details = usage == null ? null : usage.getAsJsonObject("prompt_tokens_details");
            return new AiModelTurn(content, new AiTokenUsage(AiTokenUsage.number(usage, "prompt_tokens"),
                AiTokenUsage.number(usage, "completion_tokens"), AiTokenUsage.number(details, "cached_tokens"), 0L));
        });
    }

    static JsonObject requestBody(AiPresetRequest request, AiProviderCapabilities capabilities) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("temperature", 0.2);
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.systemPrompt()));
        messages.add(message("user", request.userPrompt()));
        body.add("messages", messages);
        if (capabilities != null && capabilities.structuredOutput()) {
            JsonObject jsonSchema = new JsonObject();
            jsonSchema.addProperty("name", request.outputSchemaName());
            jsonSchema.addProperty("strict", true);
            jsonSchema.add("schema", request.outputSchema());
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_schema");
            responseFormat.add("json_schema", jsonSchema);
            body.add("response_format", responseFormat);
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
