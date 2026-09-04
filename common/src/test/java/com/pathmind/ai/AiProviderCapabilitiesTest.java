package com.pathmind.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderCapabilitiesTest {
    @Test
    void adaptersDeclareTransportCapabilitiesInsteadOfRegistryFlags() {
        AiProvider official = OpenAiCompatibleProvider.officialOpenAi("https://example.invalid", "key");
        AiProvider compatible = new OpenAiCompatibleProvider("https://example.invalid", "key");
        AiProvider anthropic = new AnthropicProvider("https://example.invalid", "key");
        AiProvider gemini = new GeminiProvider("https://example.invalid/{model}", "key");

        assertTrue(official.capabilities().structuredOutput());
        assertFalse(compatible.capabilities().structuredOutput());
        assertFalse(anthropic.capabilities().structuredOutput());
        assertFalse(gemini.capabilities().structuredOutput());
        assertFalse(official.capabilities().nativeFunctionTools());
    }
}
