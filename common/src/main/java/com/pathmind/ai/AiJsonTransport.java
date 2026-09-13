package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Injectable asynchronous JSON transport; errors deliberately exclude URLs, bodies, and secrets. */
@FunctionalInterface
interface AiJsonTransport {
    CompletableFuture<JsonObject> post(String endpoint, Map<String, String> headers, JsonObject body);

    static AiJsonTransport http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return (endpoint, headers, body) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(75))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            headers.forEach(builder::header);
            var network = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            var parsed = network.handle((response, failure) -> {
                if (failure != null) throw new IllegalStateException("AI provider network request failed.");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
                }
                try {
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("AI provider returned invalid JSON.");
                }
            });
            parsed.whenComplete((result, failure) -> { if (parsed.isCancelled()) network.cancel(true); });
            return parsed;
        };
    }
}
