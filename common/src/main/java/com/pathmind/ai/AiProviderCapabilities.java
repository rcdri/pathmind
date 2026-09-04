package com.pathmind.ai;

/** Transport features an adapter can guarantee for every configured model it exposes. */
public record AiProviderCapabilities(boolean structuredOutput, boolean nativeFunctionTools) {
    public static final AiProviderCapabilities TEXT_ONLY = new AiProviderCapabilities(false, false);
    public static final AiProviderCapabilities STRUCTURED_OUTPUT = new AiProviderCapabilities(true, false);
}
