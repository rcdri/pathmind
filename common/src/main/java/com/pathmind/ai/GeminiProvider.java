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

/** Gemini generateContent API adapter. */
public final class GeminiProvider implements AiProvider {
    private final String endpoint, apiKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public GeminiProvider(String endpoint, String apiKey) { this.endpoint = endpoint; this.apiKey = apiKey; }
    @Override public CompletableFuture<String> generate(AiPresetRequest request) {
        JsonObject body = new JsonObject();
        JsonObject system = new JsonObject(); system.add("parts", parts(request.systemPrompt())); body.add("systemInstruction", system);
        JsonArray contents = new JsonArray(); JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.add("parts", parts(request.userPrompt())); contents.add(user); body.add("contents", contents);
        String url = endpoint.replace("{model}", request.model()) + (endpoint.contains("?") ? "&" : "?") + "key=" + apiKey;
        HttpRequest http = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(75)).header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return client.sendAsync(http, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
        });
    }
    private static JsonArray parts(String text) { JsonObject part = new JsonObject(); part.addProperty("text", text); JsonArray parts = new JsonArray(); parts.add(part); return parts; }
}
