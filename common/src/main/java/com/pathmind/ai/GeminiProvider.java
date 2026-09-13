package com.pathmind.ai;

import java.util.concurrent.CompletableFuture;

/** Gemini generateContent API adapter. */
public final class GeminiProvider implements AiProvider {
    private final String endpoint, apiKey;
    private final AiJsonTransport transport = AiJsonTransport.http();
    public GeminiProvider(String endpoint, String apiKey) { this.endpoint = endpoint; this.apiKey = apiKey; }
    @Override public AiProviderCapabilities capabilities() { return AiProviderCapabilities.NATIVE_TOOLS; }
    @Override public String providerId() { return "gemini"; }
    @Override public AiProviderSession openSession() {
        return new AiNativeProviderSession(AiNativeProviderSession.Dialect.GEMINI, endpoint, apiKey, transport, false);
    }
    @Override public CompletableFuture<String> generate(AiPresetRequest request) {
        AiProviderSession session = openSession();
        return session.generate(request, null).thenApply(AiModelTurn::action).whenComplete((value, failure) -> session.close());
    }
}
