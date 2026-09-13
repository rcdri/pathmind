package com.pathmind.ai;

/** Preserve user-visible formatting. Reject oversized output rather than silently discarding its tail. */
public final class AiDisplayText {
    public static final int MAX_MESSAGE_CHARS = 64_000, MAX_DETAIL_CHARS = 16_000;
    private AiDisplayText() { }
    public static String message(String text) { return checked(text, MAX_MESSAGE_CHARS); }
    public static String detail(String text) { return checked(text, MAX_DETAIL_CHARS); }
    public static String diagnostic(String text) {
        String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS)
            + "\n[Diagnostic exceeds the 16,000-character safety limit; remaining detail omitted.]";
    }
    private static String checked(String text, int limit) {
        String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (value.length() > limit) throw new IllegalArgumentException("AI message exceeds the " + limit + "-character display safety limit. Ask for a shorter response; no truncated message was saved.");
        return value;
    }
}
