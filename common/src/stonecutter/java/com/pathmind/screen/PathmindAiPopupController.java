package com.pathmind.screen;

import com.pathmind.ai.AiPresetService;
import com.pathmind.ai.AiProviderRegistry;
import com.pathmind.ai.AiProviderType;
import com.pathmind.ui.control.PathmindWorkspaceChrome;
import com.pathmind.ui.control.PathmindDropdownRenderer;
import com.pathmind.ui.control.PathmindPopupRenderer;
import com.pathmind.ui.animation.AnimatedValue;
import com.pathmind.ui.animation.AnimationHelper;
import com.pathmind.ui.theme.UIStyleHelper;
import com.pathmind.ui.theme.UITheme;
import com.pathmind.util.DropdownLayoutHelper;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** A resizable, non-modal AI workspace that stays alongside the graph editor. */
final class PathmindAiPopupController {
    interface Host {
        void requestAiProposal(AiProviderType provider, String prompt, Consumer<AiPresetService.Proposal> success, Consumer<String> failure);
        String createAndWriteAiPreset(AiPresetService.Proposal proposal);
        void showAiError(String message);
    }
    private enum View { CHAT, SETTINGS }
    private enum Field { NONE, KEY, PROMPT }
    private enum ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private static final int MIN_WIDTH = 185, MIN_HEIGHT = 220, DEFAULT_WIDTH = MIN_WIDTH, DEFAULT_HEIGHT = 310, HEADER = 22, COMPOSER_LINES = 3;
    private final Host host;
    private final AnimatedValue modelDropdownAnimation = AnimatedValue.forHover();
    private boolean visible, dragging, resizing, requesting, replaceOnType, modelDropdownOpen;
    private int x = -1, y = -1, width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT, dragOffsetX, dragOffsetY, resizeStartX, resizeStartY, resizeStartWidth, resizeStartHeight;
    private ResizeCorner resizeCorner;
    private View view = View.CHAT;
    private Field activeField = Field.NONE;
    private AiProviderType provider = AiProviderType.OPENAI;
    private String apiKey = "", model = provider.defaultModel(), prompt = "", status = "";
    private Font currentFont;
    private int promptCursor, promptScrollLine, promptAnchor, promptDragAnchor, thinkingScrollOffset;
    private boolean promptSelecting;
    private long requestStartedAt;

    PathmindAiPopupController(Host host) { this.host = host; }
    boolean isVisible() { return visible; }
    void open(int screenWidth, int screenHeight) {
        if (x < 0 || y < 0) { x = Math.max(10, screenWidth - width - 55); y = 28; }
        clampToScreen(screenWidth, screenHeight);
        view = AiProviderRegistry.hasConfiguredProvider() ? View.CHAT : View.SETTINGS;
        model = configuredModel(); visible = true;
    }
    void close() { visible = false; dragging = false; resizing = false; modelDropdownOpen = false; activeField = Field.NONE; }

    void render(GuiGraphics c, Font font, int mouseX, int mouseY, int accent) {
        if (!visible) return;
        currentFont = font;
        UIStyleHelper.drawBeveledPanel(c, x, y, width, height, UITheme.BACKGROUND_SECONDARY, UITheme.BORDER_DEFAULT, UITheme.PANEL_INNER_BORDER);
        c.fill(x + 1, y + 1, x + width - 1, y + HEADER, UITheme.BACKGROUND_SECTION);
        if (view == View.CHAT) renderChatHeader(c, font, mouseX, mouseY, accent); else renderSettingsHeader(c, font, mouseX, mouseY, accent);
        c.hLine(x + 1, x + width - 2, y + HEADER, UITheme.BORDER_SUBTLE);
        if (view == View.CHAT) renderChat(c, font, mouseX, mouseY, accent); else renderSettings(c, font, mouseX, mouseY, accent);
        renderCornerHandles(c);
    }

    private void renderChatHeader(GuiGraphics c, Font f, int mouseX, int mouseY, int accent) {
        int tabX = x + 9;
        for (AiProviderType candidate : supportedProviders()) {
            int tabWidth = f.width(tabLabel(candidate)) + 14;
            boolean selected = candidate == provider;
            drawTab(c, f, tabLabel(candidate), tabX, y + 3, tabWidth, selected, mouseX, mouseY, accent);
            tabX += tabWidth + 2;
        }
        PathmindWorkspaceChrome.drawSettingsIcon(c, x + width - 38, y + 2, 18, UITheme.TEXT_PRIMARY);
        c.drawString(f, Component.literal("×"), x + width - 14, y + 8, UITheme.TEXT_PRIMARY);
    }

    private void renderSettingsHeader(GuiGraphics c, Font f, int mouseX, int mouseY, int accent) {
        boolean backHovered = contains(mouseX, mouseY, x + 7, y + 2, 48, 18);
        c.drawString(f, Component.literal("← Back"), x + 9, y + 8, backHovered ? UITheme.TEXT_HEADER : accent);
        c.drawString(f, Component.literal("AI settings"), x + width / 2 - f.width("AI settings") / 2, y + 8, UITheme.TEXT_HEADER);
        c.drawString(f, Component.literal("×"), x + width - 14, y + 8, UITheme.TEXT_PRIMARY);
    }

    private void renderChat(GuiGraphics c, Font f, int mouseX, int mouseY, int accent) {
        boolean connected = AiProviderRegistry.configured(provider).isPresent();
        int composerHeight = COMPOSER_LINES * (f.lineHeight + 1) + 12;
        int composerY = y + height - composerHeight - 12;
        String headline = connected ? "Describe the preset you want to build" : "Configure " + provider.displayName() + " to begin";
        if (!requesting) drawCenteredWrapped(c, f, headline, x + width / 2, y + HEADER + 24, width - 24, 2, UITheme.TEXT_TERTIARY);
        if (requesting || requestStartedAt > 0) renderThinking(c, f, composerY - 10);
        else if (!status.isBlank()) renderActivity(c, f, status.startsWith("Error") ? "Error" : "Result", status, composerY - 18, status.startsWith("Error") ? UITheme.STATE_ERROR : UITheme.TEXT_SECONDARY);
        c.fill(x + 10, composerY, x + width - 10, composerY + composerHeight, UITheme.BACKGROUND_PRIMARY);
        DrawBorder(c, x + 10, composerY, width - 20, composerHeight, activeField == Field.PROMPT ? accent : UITheme.BORDER_DEFAULT);
        String fieldText = prompt.isBlank() && activeField != Field.PROMPT ? "Ask " + provider.displayName() + " to create a preset…" : prompt;
        int textWidth = Math.max(30, width - 78);
        java.util.List<TextLine> promptLines = promptLines(f, fieldText, textWidth);
        if (activeField == Field.PROMPT) ensurePromptCursorVisible(promptLines);
        int lineY = composerY + 6;
        if (activeField == Field.PROMPT && promptAnchor != promptCursor) renderPromptSelection(c, f, promptLines, lineY);
        for (int i = 0; i < COMPOSER_LINES && promptScrollLine + i < promptLines.size(); i++) c.drawString(f, Component.literal(promptLines.get(promptScrollLine + i).text()), x + 17, lineY + i * (f.lineHeight + 1), prompt.isBlank() ? UITheme.TEXT_TERTIARY : UITheme.TEXT_PRIMARY);
        if (activeField == Field.PROMPT && ((System.currentTimeMillis() / 300L) & 1L) == 0L) {
            int lineIndex = promptLineIndex(promptLines, promptCursor);
            TextLine cursorLine = promptLines.get(lineIndex);
            int caretY = lineY + (lineIndex - promptScrollLine) * (f.lineHeight + 1);
            String beforeCursor = cursorLine.text().substring(0, Math.max(0, Math.min(promptCursor - cursorLine.start(), cursorLine.text().length())));
            c.vLine(Math.min(x + width - 54, x + 17 + f.width(beforeCursor)), caretY, caretY + f.lineHeight, UITheme.CARET_COLOR);
        }
        String action = requesting ? "…" : "Send";
        int actionX = x + width - 48;
        UIStyleHelper.drawBeveledPanel(c, actionX, composerY + 5, 32, composerHeight - 10, accent, UITheme.BORDER_HIGHLIGHT, UITheme.PANEL_INNER_BORDER);
        c.drawCenteredString(f, Component.literal(action), actionX + 16, composerY + composerHeight / 2 - f.lineHeight / 2, UITheme.TEXT_HEADER);
    }

    private void renderSettings(GuiGraphics c, Font f, int mouseX, int mouseY, int accent) {
        modelDropdownAnimation.animateTo(modelDropdownOpen ? 1f : 0f, UITheme.TRANSITION_ANIM_MS, AnimationHelper::easeOutQuad);
        modelDropdownAnimation.tick();
        int tabX = x + 12;
        for (AiProviderType candidate : supportedProviders()) {
            int tabWidth = f.width(tabLabel(candidate)) + 12;
            drawTab(c, f, tabLabel(candidate), tabX, y + HEADER + 8, tabWidth, candidate == provider, mouseX, mouseY, accent);
            tabX += tabWidth + 2;
        }
        int keyY = y + HEADER + 42;
        c.drawString(f, Component.literal(provider.displayName() + " API key"), x + 12, keyY, UITheme.TEXT_SECONDARY);
        input(c, f, masked(apiKey), Field.KEY, x + 12, keyY + 12, width - 24);
        int modelY = keyY + 48;
        c.drawString(f, Component.literal("Model"), x + 12, modelY, UITheme.TEXT_SECONDARY);
        renderModelDropdown(c, f, modelY + 12, mouseX, mouseY, accent);
        drawWrapped(c, f, "Stored encrypted in your local Pathmind settings.", x + 12, modelY + 43, width - 24, 2, UITheme.TEXT_TERTIARY);
        int saveX = x + width - 72, saveY = y + height - 30;
        c.fill(saveX, saveY, saveX + 60, saveY + 18, accent);
        c.drawCenteredString(f, Component.literal("Save"), saveX + 30, saveY + 5, UITheme.TEXT_HEADER);
        renderModelDropdownOptions(c, f, modelY + 12, mouseX, mouseY, accent);
    }

    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!visible || button != 0) return false;
        ResizeCorner corner = resizeCornerAt(mouseX, mouseY);
        if (corner != null) { beginResize(corner); return true; }
        if (!contains(mouseX, mouseY, x, y, width, height)) { activeField = Field.NONE; modelDropdownOpen = false; return false; }
        activeField = Field.NONE;
        if (contains(mouseX, mouseY, x + width - 18, y + 2, 16, 18)) { close(); return true; }
        if (view == View.CHAT) return chatClick(mouseX, mouseY);
        return settingsClick(mouseX, mouseY);
    }
    private boolean chatClick(int mouseX, int mouseY) {
        if (contains(mouseX, mouseY, x + width - 38, y + 2, 18, 18)) { view = View.SETTINGS; modelDropdownOpen = false; return true; }
        int tabX = x + 9;
        for (AiProviderType candidate : supportedProviders()) { int tabWidth = tabLabel(candidate).length() * 6 + 14; if (contains(mouseX, mouseY, tabX, y + 3, tabWidth, HEADER - 4)) { selectProvider(candidate); return true; } tabX += tabWidth + 2; }
        int composerHeight = COMPOSER_LINES * ((currentFont == null ? 9 : currentFont.lineHeight) + 1) + 12;
        int composerY = y + height - composerHeight - 12;
        if (contains(mouseX, mouseY, x + 10, composerY, width - 20, composerHeight)) { if (mouseX >= x + width - 48) activateAction(); else { activeField = Field.PROMPT; replaceOnType = false; promptCursor = promptIndexAt(mouseX, mouseY, composerY); promptAnchor = promptCursor; promptDragAnchor = promptCursor; promptSelecting = true; } return true; }
        dragging = true; dragOffsetX = mouseX - x; dragOffsetY = mouseY - y; return true;
    }
    private boolean settingsClick(int mouseX, int mouseY) {
        if (contains(mouseX, mouseY, x + 7, y + 2, 48, 18)) { view = View.CHAT; modelDropdownOpen = false; return true; }
        int tabX = x + 12;
        for (AiProviderType candidate : supportedProviders()) { int tabWidth = tabLabel(candidate).length() * 6 + 12; if (contains(mouseX, mouseY, tabX, y + HEADER + 8, tabWidth, 16)) { selectProvider(candidate); return true; } tabX += tabWidth + 2; }
        int keyY = y + HEADER + 54;
        if (contains(mouseX, mouseY, x + 12, keyY, width - 24, 20)) { activeField = Field.KEY; replaceOnType = false; return true; }
        int modelY = y + HEADER + 42 + 48;
        if (modelDropdownOpen && contains(mouseX, mouseY, x + 12, modelY + 34, width - 24, modelOptions().length * 20)) {
            int index = (mouseY - (modelY + 34)) / 20;
            model = modelOptions()[index];
            modelDropdownOpen = false;
            return true;
        }
        if (contains(mouseX, mouseY, x + 12, modelY + 12, width - 24, 20)) { modelDropdownOpen = !modelDropdownOpen; return true; }
        modelDropdownOpen = false;
        if (contains(mouseX, mouseY, x + width - 72, y + height - 30, 60, 18)) { saveConfiguration(); return true; }
        dragging = true; dragOffsetX = mouseX - x; dragOffsetY = mouseY - y; return true;
    }
    boolean mouseDragged(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!visible) return false;
        if (resizing) { updateResize(mouseX, mouseY, screenWidth, screenHeight); return true; }
        if (dragging) { x = mouseX - dragOffsetX; y = mouseY - dragOffsetY; clampToScreen(screenWidth, screenHeight); return true; }
        if (promptSelecting) { promptCursor = promptIndexAt(mouseX, mouseY, y + height - (COMPOSER_LINES * ((currentFont == null ? 9 : currentFont.lineHeight) + 1) + 12) - 12); return true; }
        return false;
    }
    boolean mouseReleased() { boolean handled = dragging || resizing || promptSelecting; dragging = false; resizing = false; resizeCorner = null; promptSelecting = false; return handled; }
    boolean keyPressed(int keyCode, int modifiers) {
        if (!visible) return false;
        if (modelDropdownOpen) { modelDropdownOpen = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        boolean shortcut = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (shortcut && activeField == Field.KEY && keyCode == GLFW.GLFW_KEY_A) { replaceOnType = true; return true; }
        if (shortcut && activeField == Field.KEY && keyCode == GLFW.GLFW_KEY_V) { pasteApiKey(); return true; }
        if (shortcut && keyCode == GLFW.GLFW_KEY_A && activeField == Field.PROMPT) { promptAnchor = 0; promptCursor = prompt.length(); return true; }
        if (shortcut && activeField == Field.PROMPT && keyCode == GLFW.GLFW_KEY_C) { copyPromptSelection(); return true; }
        if (shortcut && activeField == Field.PROMPT && keyCode == GLFW.GLFW_KEY_X) { copyPromptSelection(); deletePromptSelection(); return true; }
        if (shortcut && activeField == Field.PROMPT && keyCode == GLFW.GLFW_KEY_V) { insertPromptText(clipboardText()); return true; }
        if (shortcut && keyCode == GLFW.GLFW_KEY_A && activeField != Field.NONE) { replaceOnType = true; return true; }
        if (activeField == Field.PROMPT && currentFont != null) {
            boolean extendingSelection = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (keyCode == GLFW.GLFW_KEY_LEFT) { promptCursor = !extendingSelection && promptAnchor != promptCursor ? Math.min(promptAnchor, promptCursor) : Math.max(0, promptCursor - 1); if (!extendingSelection) promptAnchor = promptCursor; return true; }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) { promptCursor = !extendingSelection && promptAnchor != promptCursor ? Math.max(promptAnchor, promptCursor) : Math.min(prompt.length(), promptCursor + 1); if (!extendingSelection) promptAnchor = promptCursor; return true; }
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) { movePromptCursorVertically(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1); if (!extendingSelection) promptAnchor = promptCursor; return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME) { promptCursor = 0; if (!extendingSelection) promptAnchor = promptCursor; return true; }
            if (keyCode == GLFW.GLFW_KEY_END) { promptCursor = prompt.length(); if (!extendingSelection) promptAnchor = promptCursor; return true; }
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && activeField != Field.NONE) { backspace(); return true; }
        if (keyCode == GLFW.GLFW_KEY_DELETE && activeField == Field.PROMPT) { if (promptAnchor != promptCursor) deletePromptSelection(); else if (promptCursor < prompt.length()) prompt = prompt.substring(0, promptCursor) + prompt.substring(promptCursor + 1); return true; }
        if (keyCode == GLFW.GLFW_KEY_DELETE && activeField != Field.NONE) { clearField(); return true; }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && activeField == Field.PROMPT) { if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) insertPromptText("\n"); else activateAction(); return true; }
        return activeField != Field.NONE;
    }
    boolean charTyped(char character) { if (!visible || activeField == Field.NONE || Character.isISOControl(character)) return visible; type(character); return true; }

    private void activateAction() {
        if (prompt.isBlank()) { reportError("Describe the preset first."); return; }
        if (AiProviderRegistry.configured(provider).isEmpty()) { view = View.SETTINGS; reportError("Configure " + provider.displayName() + " first."); return; }
        requesting = true; status = ""; thinkingScrollOffset = 0; requestStartedAt = System.currentTimeMillis();
        host.requestAiProposal(provider, prompt, value -> { requesting = false; status = host.createAndWriteAiPreset(value); if (status.startsWith("Error")) host.showAiError(status); }, error -> { requesting = false; reportError(error); });
    }
    private void saveConfiguration() {
        if (apiKey.isBlank() && !com.pathmind.ai.AiSecretStore.hasSecret(provider)) { status = "Error: enter an API key."; return; }
        AiProviderRegistry.saveConfiguration(provider, true, model, provider.defaultEndpoint(), apiKey.isBlank() ? null : apiKey);
        apiKey = ""; status = "Saved."; view = View.CHAT;
    }
    private void reportError(String message) { status = "Error: " + (message == null || message.isBlank() ? "AI request failed." : message); host.showAiError(status); }
    private void renderThinking(GuiGraphics c, Font f, int bottomY) {
        java.util.List<ThinkingLine> rows = thinkingLines(f);
        int topY = y + HEADER + 18;
        int rowHeight = f.lineHeight + 3;
        int visibleCount = Math.max(1, (bottomY - topY) / rowHeight);
        int total = rows.size();
        int maxScroll = Math.max(0, total - visibleCount);
        thinkingScrollOffset = clamp(thinkingScrollOffset, 0, maxScroll);
        int newest = total - 1 - thinkingScrollOffset;
        int oldest = Math.max(0, newest - visibleCount + 1);
        c.enableScissor(x + 8, topY, x + width - 8, bottomY);
        for (int i = newest; i >= oldest; i--) {
            int rowFromBottom = newest - i;
            int lineY = bottomY - (rowFromBottom + 1) * rowHeight;
            ThinkingLine row = rows.get(i);
            c.drawString(f, Component.literal(row.text()), x + 16, lineY, row.color());
        }
        c.disableScissor();
        DropdownLayoutHelper.drawScrollBar(c, x + 8, topY, width - 16, bottomY - topY, total, visibleCount, thinkingScrollOffset, maxScroll, UITheme.BACKGROUND_TERTIARY, UITheme.BORDER_HIGHLIGHT);
    }
    private void renderActivity(GuiGraphics c, Font f, String label, String message, int bottomY, int color) { int topY = Math.max(y + HEADER + 58, bottomY - 3 * (f.lineHeight + 3)); c.drawString(f, Component.literal(label), x + 12, topY, color); drawWrapped(c, f, message, x + 16, topY + f.lineHeight + 3, width - 32, 2, color); }
    private record ThinkingLine(String text, int color) { }
    private java.util.List<ThinkingLine> thinkingLines(Font font) { String[] stages = {"· Reading workspace context", "· Planning the preset", "· Building the node graph", "· Validating the preset"}; int current = requesting ? (int) Math.min(stages.length - 1, Math.max(0, (System.currentTimeMillis() - requestStartedAt) / 1200L)) : stages.length - 1; java.util.List<ThinkingLine> result = new java.util.ArrayList<>(); for (int i = 0; i <= current; i++) result.add(new ThinkingLine((requesting && i == current ? "› " + stages[i].substring(2) + "…" : stages[i]), requesting && i == current ? UITheme.TEXT_PRIMARY : UITheme.TEXT_TERTIARY)); if (status.startsWith("Error")) for (TextLine line : lineSegments(font, status, Math.max(24, width - 38))) result.add(new ThinkingLine(line.text(), UITheme.STATE_ERROR)); return result; }
    boolean mouseScrolled(int mouseX, int mouseY, double amount) { if (!visible || requestStartedAt <= 0 || currentFont == null || amount == 0.0) return false; int composerHeight = COMPOSER_LINES * (currentFont.lineHeight + 1) + 12; int topY = y + HEADER + 18, bottomY = y + height - composerHeight - 22; if (!contains(mouseX, mouseY, x + 8, topY, width - 16, Math.max(0, bottomY - topY))) return false; int visibleCount = Math.max(1, (bottomY - topY) / (currentFont.lineHeight + 3)); thinkingScrollOffset = clamp(thinkingScrollOffset + (amount > 0 ? 1 : -1), 0, Math.max(0, thinkingLines(currentFont).size() - visibleCount)); return true; }
    private void selectProvider(AiProviderType next) { provider = next; apiKey = ""; model = configuredModel(); modelDropdownOpen = false; activeField = Field.NONE; replaceOnType = false; }
    private void type(char character) { if (replaceOnType) clearField(); replaceOnType = false; if (activeField == Field.KEY && apiKey.length() < 512) apiKey += character; else if (activeField == Field.PROMPT) insertPromptText(String.valueOf(character)); }
    private void backspace() { if (replaceOnType) { clearField(); return; } if (activeField == Field.KEY && !apiKey.isEmpty()) apiKey = apiKey.substring(0, apiKey.length() - 1); else if (activeField == Field.PROMPT) { if (promptAnchor != promptCursor) deletePromptSelection(); else if (promptCursor > 0) { prompt = prompt.substring(0, promptCursor - 1) + prompt.substring(promptCursor); promptCursor--; promptAnchor = promptCursor; } } }
    private void clearField() { if (activeField == Field.KEY) apiKey = ""; else if (activeField == Field.PROMPT) { prompt = ""; promptCursor = 0; promptScrollLine = 0; } replaceOnType = false; }
    private String configuredModel() { String configured = AiProviderRegistry.config(provider).model; return configured == null || configured.isBlank() ? provider.defaultModel() : configured; }
    private void renderModelDropdown(GuiGraphics c, Font f, int iy, int mouseX, int mouseY, int accent) {
        int ix = x + 12, iw = width - 24;
        boolean hovered = contains(mouseX, mouseY, ix, iy, iw, 20);
        UIStyleHelper.drawToolbarButtonFrame(c, ix, iy, iw, 20, UITheme.BACKGROUND_SECONDARY, modelDropdownOpen || hovered ? accent : UITheme.BORDER_DEFAULT, UITheme.PANEL_INNER_BORDER);
        c.drawString(f, Component.literal(trim(model, Math.max(16, (iw - 30) / 6))), ix + 8, iy + 6, modelDropdownOpen ? accent : UITheme.TEXT_PRIMARY);
        PathmindPopupRenderer.drawDropdownChevron(c, ix + iw - 12, iy + 6, modelDropdownOpen ? accent : UITheme.TEXT_SECONDARY, modelDropdownOpen);
    }
    private void renderModelDropdownOptions(GuiGraphics c, Font f, int iy, int mouseX, int mouseY, int accent) {
        float progress = AnimationHelper.easeOutQuad(modelDropdownAnimation.getValue());
        if (progress <= 0.001f) return;
        int ix = x + 12, iw = width - 24;
        String[] options = modelOptions();
        PathmindDropdownRenderer.renderTextList(c, f, PathmindDropdownRenderer.TextListSpec.builder()
            .bounds(ix, iy + 22, iw)
            .rows(20, options.length, options.length)
            .scroll(0, 0, 0)
            .animation(progress)
            .hoverPoint(mouseX, mouseY)
            .colors(accent, UITheme.TEXT_SECONDARY)
            .textLayout(6, 6, false, true)
            .labels("", index -> options[index])
            .textColors(index -> options[index].equals(model) ? UITheme.TEXT_PRIMARY : UITheme.TEXT_SECONDARY)
            .chrome(UIStyleHelper.getScrollContainerPalette(accent, 1f, true, false), UITheme.BORDER_DEFAULT, UITheme.BORDER_HIGHLIGHT, UITheme.BORDER_DEFAULT)
            .build());
    }
    private String[] modelOptions() { return switch (provider) {
        case OPENAI -> new String[]{provider.defaultModel(), "gpt-5.1", "gpt-5-mini"};
        case ANTHROPIC -> new String[]{provider.defaultModel(), "claude-sonnet-5", "claude-haiku-4-5-20251001"};
        case GEMINI -> new String[]{provider.defaultModel(), "gemini-3.7-flash", "gemini-3.1-pro-preview"};
        default -> new String[]{provider.defaultModel()};
    }; }
    private void input(GuiGraphics c, Font f, String value, Field field, int ix, int iy, int iw) { String visible = trim(value, Math.max(16, (iw - 14) / 6)); c.fill(ix, iy, ix + iw, iy + 20, UITheme.BACKGROUND_PRIMARY); DrawBorder(c, ix, iy, iw, 20, activeField == field ? UITheme.ACCENT_SKY : UITheme.BORDER_DEFAULT); c.drawString(f, Component.literal(visible), ix + 6, iy + 6, UITheme.TEXT_PRIMARY); if (activeField == field && ((System.currentTimeMillis() / 300L) & 1L) == 0L) c.vLine(Math.min(ix + iw - 6, ix + 6 + f.width(visible)), iy + 5, iy + 15, UITheme.CARET_COLOR); }
    private void clampToScreen(int screenWidth, int screenHeight) { width = Math.min(width, Math.max(MIN_WIDTH, screenWidth - 8)); height = Math.min(height, Math.max(MIN_HEIGHT, screenHeight - 8)); x = clamp(x, 0, Math.max(0, screenWidth - width)); y = clamp(y, 0, Math.max(0, screenHeight - height)); }
    Identifier cursorTexture(int mouseX, int mouseY) { if (!visible) return null; if (resizing) return textureFor(resizeCorner); ResizeCorner corner = resizeCornerAt(mouseX, mouseY); if (corner != null) return textureFor(corner); if (dragging) return PathmindCursor.GRABBING_TEXTURE; if (contains(mouseX, mouseY, x, y, width, HEADER)) return PathmindCursor.GRAB_TEXTURE; return null; }
    private Identifier textureFor(ResizeCorner corner) { return switch (corner) { case TOP_LEFT -> PathmindCursor.SCALE_TOP_LEFT_TEXTURE; case TOP_RIGHT -> PathmindCursor.SCALE_TOP_RIGHT_TEXTURE; case BOTTOM_LEFT -> PathmindCursor.SCALE_BOTTOM_LEFT_TEXTURE; case BOTTOM_RIGHT -> PathmindCursor.SCALE_TEXTURE; }; }
    private ResizeCorner resizeCornerAt(int mouseX, int mouseY) { int size = 12; if (contains(mouseX, mouseY, x - size / 2, y - size / 2, size, size)) return ResizeCorner.TOP_LEFT; if (contains(mouseX, mouseY, x + width - size / 2, y - size / 2, size, size)) return ResizeCorner.TOP_RIGHT; if (contains(mouseX, mouseY, x - size / 2, y + height - size / 2, size, size)) return ResizeCorner.BOTTOM_LEFT; if (contains(mouseX, mouseY, x + width - size / 2, y + height - size / 2, size, size)) return ResizeCorner.BOTTOM_RIGHT; return null; }
    private void beginResize(ResizeCorner corner) { resizing = true; resizeCorner = corner; resizeStartX = x; resizeStartY = y; resizeStartWidth = width; resizeStartHeight = height; }
    private void updateResize(int mouseX, int mouseY, int screenWidth, int screenHeight) { int left = resizeStartX, top = resizeStartY, right = resizeStartX + resizeStartWidth, bottom = resizeStartY + resizeStartHeight; switch (resizeCorner) { case TOP_LEFT -> { left = Math.min(mouseX, right - MIN_WIDTH); top = Math.min(mouseY, bottom - MIN_HEIGHT); } case TOP_RIGHT -> { right = Math.max(mouseX, left + MIN_WIDTH); top = Math.min(mouseY, bottom - MIN_HEIGHT); } case BOTTOM_LEFT -> { left = Math.min(mouseX, right - MIN_WIDTH); bottom = Math.max(mouseY, top + MIN_HEIGHT); } case BOTTOM_RIGHT -> { right = Math.max(mouseX, left + MIN_WIDTH); bottom = Math.max(mouseY, top + MIN_HEIGHT); } } x = clamp(left, 0, screenWidth - MIN_WIDTH); y = clamp(top, 0, screenHeight - MIN_HEIGHT); width = Math.min(right - x, screenWidth - x); height = Math.min(bottom - y, screenHeight - y); }
    private void renderCornerHandles(GuiGraphics c) { int color = UITheme.BORDER_HIGHLIGHT; int size = 3; c.fill(x - 1, y - 1, x + size, y + size, color); c.fill(x + width - size, y - 1, x + width + 1, y + size, color); c.fill(x - 1, y + height - size, x + size, y + height + 1, color); c.fill(x + width - size, y + height - size, x + width + 1, y + height + 1, color); }
    private void drawTab(GuiGraphics c, Font f, String label, int tx, int ty, int tw, boolean selected, int mouseX, int mouseY, int accent) { boolean hovered = contains(mouseX, mouseY, tx, ty, tw, HEADER - 4); int fill = selected ? UITheme.BUTTON_ACTIVE_BG : hovered ? UITheme.BUTTON_DEFAULT_HOVER : UITheme.BUTTON_DEFAULT_BG; int border = selected ? accent : hovered ? UITheme.BORDER_HIGHLIGHT : UITheme.BORDER_DEFAULT; c.fill(tx, ty, tx + tw, ty + HEADER - 4, fill); DrawBorder(c, tx, ty, tw, HEADER - 4, border); c.drawString(f, Component.literal(label), tx + 6, ty + 5, selected ? UITheme.TEXT_PRIMARY : UITheme.TEXT_SECONDARY); }
    private static AiProviderType[] supportedProviders() { return new AiProviderType[]{AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.GEMINI}; }
    private static String tabLabel(AiProviderType provider) { return switch (provider) { case OPENAI -> "GPT"; case ANTHROPIC -> "Claude"; case GEMINI -> "Gemini"; default -> provider.displayName(); }; }
    private static void DrawBorder(GuiGraphics c, int bx, int by, int bw, int bh, int color) { c.hLine(bx, bx + bw - 1, by, color); c.hLine(bx, bx + bw - 1, by + bh - 1, color); c.vLine(bx, by, by + bh - 1, color); c.vLine(bx + bw - 1, by, by + bh - 1, color); }
    private static boolean contains(int px, int py, int bx, int by, int bw, int bh) { return px >= bx && px < bx + bw && py >= by && py < by + bh; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String masked(String value) { return value == null || value.isBlank() ? "" : "•".repeat(Math.min(32, value.length())); }
    private static String trim(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private record TextLine(int start, int end, String text) { }
    private java.util.List<TextLine> promptLines(Font font, String value, int maxWidth) { return lineSegments(font, value, maxWidth); }
    private static java.util.List<TextLine> lineSegments(Font font, String value, int maxWidth) {
        java.util.List<TextLine> lines = new java.util.ArrayList<>();
        String source = value == null ? "" : value;
        if (source.isEmpty()) return java.util.List.of(new TextLine(0, 0, ""));
        int start = 0;
        while (start < source.length()) {
            int newline = source.indexOf('\n', start);
            int hardEnd = newline >= 0 ? newline : source.length();
            if (start == hardEnd) { lines.add(new TextLine(start, start, "")); start = hardEnd + 1; continue; }
            int end = start;
            while (end < hardEnd) {
                String candidate = source.substring(start, end + 1);
                if (font.width(candidate) > maxWidth) break;
                end++;
            }
            if (end == start) end = Math.min(start + 1, hardEnd);
            if (end < hardEnd) {
                int wordEnd = end;
                while (wordEnd > start && !Character.isWhitespace(source.charAt(wordEnd - 1))) wordEnd--;
                if (wordEnd > start) end = wordEnd;
            }
            lines.add(new TextLine(start, end, source.substring(start, end)));
            start = end;
            while (start < hardEnd && Character.isWhitespace(source.charAt(start)) && source.charAt(start) != '\n') start++;
            if (start == hardEnd && newline >= 0) start++;
        }
        return lines;
    }
    private static java.util.List<String> wrap(Font font, String value, int maxWidth) { java.util.List<String> lines = new java.util.ArrayList<>(); for (TextLine line : lineSegments(font, value, maxWidth)) { lines.add(line.text()); if (lines.size() == 2) break; } return lines; }
    private int promptLineIndex(java.util.List<TextLine> lines, int cursor) { for (int i = 0; i < lines.size(); i++) if (cursor <= lines.get(i).end()) return i; return lines.size() - 1; }
    private void ensurePromptCursorVisible(java.util.List<TextLine> lines) { int line = promptLineIndex(lines, promptCursor); if (line < promptScrollLine) promptScrollLine = line; if (line >= promptScrollLine + COMPOSER_LINES) promptScrollLine = line - COMPOSER_LINES + 1; }
    private void movePromptCursorVertically(int direction) { java.util.List<TextLine> lines = promptLines(currentFont, prompt, Math.max(30, width - 78)); int current = promptLineIndex(lines, promptCursor); int target = Math.max(0, Math.min(lines.size() - 1, current + direction)); int offset = Math.max(0, promptCursor - lines.get(current).start()); promptCursor = Math.min(lines.get(target).end(), lines.get(target).start() + offset); ensurePromptCursorVisible(lines); }
    private int promptIndexAt(int mouseX, int mouseY, int composerY) { if (currentFont == null) return prompt.length(); java.util.List<TextLine> lines = promptLines(currentFont, prompt, Math.max(30, width - 78)); int line = Math.max(0, Math.min(lines.size() - 1, promptScrollLine + (mouseY - composerY - 6) / (currentFont.lineHeight + 1))); TextLine target = lines.get(line); int relativeX = Math.max(0, mouseX - (x + 17)); int offset = 0; while (offset < target.text().length() && currentFont.width(target.text().substring(0, offset + 1)) <= relativeX) offset++; return target.start() + offset; }
    private void renderPromptSelection(GuiGraphics c, Font f, java.util.List<TextLine> lines, int lineY) { int start = Math.min(promptAnchor, promptCursor), end = Math.max(promptAnchor, promptCursor); for (int i = promptScrollLine; i < lines.size() && i < promptScrollLine + COMPOSER_LINES; i++) { TextLine line = lines.get(i); int selectedStart = Math.max(start, line.start()), selectedEnd = Math.min(end, line.end()); if (selectedEnd <= selectedStart) continue; int left = x + 17 + f.width(line.text().substring(0, selectedStart - line.start())); int right = x + 17 + f.width(line.text().substring(0, selectedEnd - line.start())); int top = lineY + (i - promptScrollLine) * (f.lineHeight + 1); c.fill(left, top - 1, right, top + f.lineHeight + 1, 0x664F86C6); } }
    private void insertPromptText(String value) { if (value == null || value.isEmpty()) return; String cleaned = value.replace("\r", ""); int start = Math.min(promptAnchor, promptCursor), end = Math.max(promptAnchor, promptCursor); int remaining = 2048 - (prompt.length() - (end - start)); if (remaining <= 0) return; String inserted = cleaned.length() > remaining ? cleaned.substring(0, remaining) : cleaned; prompt = prompt.substring(0, start) + inserted + prompt.substring(end); promptCursor = start + inserted.length(); promptAnchor = promptCursor; }
    private void deletePromptSelection() { int start = Math.min(promptAnchor, promptCursor), end = Math.max(promptAnchor, promptCursor); if (start == end) return; prompt = prompt.substring(0, start) + prompt.substring(end); promptCursor = start; promptAnchor = start; }
    private void copyPromptSelection() { int start = Math.min(promptAnchor, promptCursor), end = Math.max(promptAnchor, promptCursor); if (end > start && Minecraft.getInstance() != null) Minecraft.getInstance().keyboardHandler.setClipboard(prompt.substring(start, end)); }
    private String clipboardText() { return Minecraft.getInstance() == null ? "" : Minecraft.getInstance().keyboardHandler.getClipboard(); }
    private void pasteApiKey() { String value = clipboardText().replace("\r", "").replace("\n", "").trim(); if (value.isEmpty()) return; if (replaceOnType) apiKey = ""; replaceOnType = false; int remaining = 512 - apiKey.length(); if (remaining > 0) apiKey += value.substring(0, Math.min(value.length(), remaining)); }
    private static void drawCenteredWrapped(GuiGraphics c, Font f, String value, int centerX, int topY, int maxWidth, int maxLines, int color) { java.util.List<String> lines = wrap(f, value, maxWidth); for (int i = 0; i < lines.size() && i < maxLines; i++) c.drawCenteredString(f, Component.literal(lines.get(i)), centerX, topY + i * (f.lineHeight + 2), color); }
    private static void drawWrapped(GuiGraphics c, Font f, String value, int leftX, int topY, int maxWidth, int maxLines, int color) { java.util.List<String> lines = wrap(f, value, maxWidth); for (int i = 0; i < lines.size() && i < maxLines; i++) c.drawString(f, Component.literal(lines.get(i)), leftX, topY + i * (f.lineHeight + 2), color); }
}
