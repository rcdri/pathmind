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

/** Anthropic Messages API adapter. */
public final class AnthropicProvider implements AiProvider {
    private final String endpoint, apiKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public AnthropicProvider(String endpoint, String apiKey) { this.endpoint = endpoint; this.apiKey = apiKey; }
    @Override public CompletableFuture<String> generate(AiPresetRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model()); body.addProperty("max_tokens", 4096); body.addProperty("system", request.systemPrompt());
        JsonArray messages = new JsonArray(); JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.addProperty("content", request.userPrompt()); messages.add(user); body.add("messages", messages);
        HttpRequest http = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(75))
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return client.sendAsync(http, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        });
    }
}
