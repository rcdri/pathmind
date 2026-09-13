package com.pathmind.ai;

import java.util.concurrent.CompletableFuture;

/** Official OpenAI Responses API adapter. Generic compatible endpoints retain the legacy adapter. */
public final class OpenAiResponsesProvider implements AiProvider {
    private final String endpoint, apiKey;
    private final boolean storeConversation;
    private final AiJsonTransport transport;

    public OpenAiResponsesProvider(String endpoint, String apiKey) { this(endpoint, apiKey, false); }
    public OpenAiResponsesProvider(String endpoint, String apiKey, boolean storeConversation) {
        this(endpoint, apiKey, storeConversation, AiJsonTransport.http());
    }
    OpenAiResponsesProvider(String endpoint, String apiKey, boolean storeConversation, AiJsonTransport transport) {
        this.endpoint = responsesEndpoint(endpoint);
        this.apiKey = apiKey;
        this.storeConversation = storeConversation;
        this.transport = transport;
    }
    @Override public AiProviderCapabilities capabilities() { return AiProviderCapabilities.NATIVE_TOOLS; }
    @Override public String providerId() { return "openai"; }
    @Override public AiProviderSession openSession() {
        return new AiNativeProviderSession(AiNativeProviderSession.Dialect.OPENAI, endpoint, apiKey, transport, storeConversation);
    }
    @Override public CompletableFuture<String> generate(AiPresetRequest request) {
        AiProviderSession session = openSession();
        return session.generate(request, null).thenApply(AiModelTurn::action).whenComplete((value, failure) -> session.close());
    }
    static String responsesEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return AiProviderType.OPENAI.defaultEndpoint();
        String normalized = endpoint.trim();
        // Migrate the saved official default only, never rewrite a user's custom proxy URL.
        return normalized.equals("https://api.openai.com/v1/chat/completions")
            ? "https://api.openai.com/v1/responses" : normalized;
    }
}
