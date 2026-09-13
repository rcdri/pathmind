package com.pathmind.ai;

import com.pathmind.data.SettingsManager;
import com.pathmind.data.SettingsManager.Settings;
import java.util.Optional;

/** Resolves configured providers without exposing secrets to workspace code. */
public final class AiProviderRegistry {
    private AiProviderRegistry() {
    }

    public static boolean hasConfiguredProvider() {
        for (AiProviderType provider : AiProviderType.values()) {
            Settings.AiProviderSettings config = config(provider);
            if (Boolean.TRUE.equals(config.enabled) && AiSecretStore.hasSecret(provider)) return true;
        }
        return false;
    }

    public static Settings.AiProviderSettings config(AiProviderType provider) {
        Settings settings = SettingsManager.getCurrent();
        return settings.aiProviders.computeIfAbsent(provider.id(), ignored -> {
            Settings.AiProviderSettings config = new Settings.AiProviderSettings();
            config.endpoint = provider.defaultEndpoint();
            return config;
        });
    }

    public static Optional<AiProvider> configured(AiProviderType provider) {
        if (provider == null) return Optional.empty();
        Settings.AiProviderSettings config = config(provider);
        String key = AiSecretStore.read(provider);
        if (!Boolean.TRUE.equals(config.enabled) || key.isBlank()) return Optional.empty();
        String endpoint = config.endpoint == null || config.endpoint.isBlank() ? provider.defaultEndpoint() : config.endpoint.trim();
        if (endpoint.isBlank()) return Optional.empty();
        return Optional.of(switch (provider) {
            case ANTHROPIC -> new AnthropicProvider(endpoint, key);
            case GEMINI -> new GeminiProvider(endpoint, key);
            case OPENAI -> new OpenAiResponsesProvider(endpoint, key, Boolean.TRUE.equals(config.storeConversation));
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(endpoint, key);
        });
    }

    /** Persists non-secret provider choices and encrypts the supplied API key at rest. */
    public static void saveConfiguration(AiProviderType provider, boolean enabled, String model, String endpoint, String apiKey) {
        if (provider == null) return;
        Settings settings = SettingsManager.getCurrent();
        Settings.AiProviderSettings config = config(provider);
        config.enabled = enabled;
        config.model = model == null ? "" : model.trim();
        config.endpoint = endpoint == null || endpoint.isBlank() ? provider.defaultEndpoint() : endpoint.trim();
        SettingsManager.save(settings);
        if (apiKey != null) AiSecretStore.save(provider, apiKey);
    }
}
