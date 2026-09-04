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
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public OpenAiCompatibleProvider(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<String> generate(AiPresetRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("temperature", 0.2);
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.systemPrompt()));
        messages.add(message("user", request.userPrompt()));
        body.add("messages", messages);
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
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
