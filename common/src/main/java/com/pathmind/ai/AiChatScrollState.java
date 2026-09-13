package com.pathmind.ai;

import java.util.List;
import com.pathmind.util.ScrollbarHelper;

/** Keep a stable message/character anchor during reflow; follow new messages only when at the end. */
public final class AiChatScrollState {
    private List<AiChatLayout.Row> rows = List.of();
    private int offset, viewport = 1, rowHeight = 1;
    private boolean followEnd = true;
    public int top() { return offset / rowHeight; }
    public int max() { return (maxPixels() + rowHeight - 1) / rowHeight; }
    public int offsetPixels() { return offset; }
    public int maxPixels() { return Math.max(0, rows.size() * rowHeight - viewport); }
    public boolean atEnd() { return followEnd; }
    public void update(List<AiChatLayout.Row> replacement, int visibleRows) {
        updatePixels(replacement, Math.max(1, visibleRows), 1);
    }
    public void updatePixels(List<AiChatLayout.Row> replacement, int viewportHeight, int lineHeight) {
        int oldTop = top();
        var anchor = oldTop < rows.size() ? rows.get(oldTop) : null;
        double fraction = (offset % rowHeight) / (double) rowHeight;
        boolean changed = rows != replacement;
        int previousRowHeight = rowHeight;
        rows = replacement; viewport = Math.max(1, viewportHeight); rowHeight = Math.max(1, lineHeight);
        if (followEnd) offset = maxPixels();
        else if (changed && anchor != null) {
            int found = -1;
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (row.entry() == anchor.entry() && row.offset() <= anchor.offset()) found = i;
                if (row.entry() > anchor.entry()) { if (found < 0) found = i; break; }
            }
            offset = ScrollbarHelper.clampScroll((found < 0 ? oldTop : found) * rowHeight + (int) (fraction * rowHeight), maxPixels());
        } else offset = ScrollbarHelper.clampScroll(previousRowHeight == rowHeight ? offset : oldTop * rowHeight + (int) (fraction * rowHeight), maxPixels());
    }
    public void scroll(int delta) { seekPixels(offset + delta * rowHeight); }
    public void wheel(double amount) { seekPixels(ScrollbarHelper.applyWheel(offset, amount, 16, maxPixels())); }
    public void seek(int row) { seekPixels(row * rowHeight); }
    public void seekPixels(int pixels) { offset = ScrollbarHelper.clampScroll(pixels, maxPixels()); followEnd = offset == maxPixels(); }
    public void end() { followEnd = true; offset = maxPixels(); }
    public void reset() { rows = List.of(); offset = 0; viewport = rowHeight = 1; followEnd = true; }
}
