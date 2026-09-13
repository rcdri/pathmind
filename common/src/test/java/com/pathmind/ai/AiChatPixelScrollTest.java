package com.pathmind.ai;

import com.pathmind.util.ScrollbarHelper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiChatPixelScrollTest {
    private static List<AiChatLayout.Row> rows(int count) {
        var result = new java.util.ArrayList<AiChatLayout.Row>();
        for (int i = 0; i < count; i++) result.add(new AiChatLayout.Row("Row " + i, i, 0, AiChatLayout.Kind.TEXT, AiChatHistoryStore.Role.USER));
        return List.copyOf(result);
    }
    @Test void usesSettingsWheelScaleIncludingFractionalTrackpadDeltas() {
        var scroll = new AiChatScrollState(); scroll.updatePixels(rows(100), 101, 12); scroll.seekPixels(200);
        scroll.wheel(.25); assertEquals(196, scroll.offsetPixels());
        scroll.wheel(.01); assertEquals(195, scroll.offsetPixels());
        scroll.wheel(-1); assertEquals(211, scroll.offsetPixels());
        assertEquals(17, scroll.top()); assertFalse(scroll.atEnd());
    }
    @Test void partialRowsAndSharedThumbMappingReachBothEndsExactly() {
        var scroll = new AiChatScrollState(); scroll.updatePixels(rows(100), 101, 12);
        assertEquals(1099, scroll.maxPixels()); assertEquals(1099, scroll.offsetPixels());
        var metrics = ScrollbarHelper.metrics(10, 20, 4, 101, scroll.maxPixels(), scroll.offsetPixels(), 20);
        assertEquals(101, metrics.viewportHeight()); assertEquals(20, metrics.thumbHeight());
        scroll.seekPixels(ScrollbarHelper.scrollFromThumb(metrics, -1000)); assertEquals(0, scroll.offsetPixels());
        scroll.seekPixels(ScrollbarHelper.scrollFromThumb(metrics, 10000)); assertTrue(scroll.atEnd());
        assertEquals(scroll.maxPixels(), scroll.offsetPixels());
    }
    @Test void preservesSubRowReadingPositionAcrossReflowAndResizeWithoutFollowingNewOutput() {
        var scroll = new AiChatScrollState(); scroll.updatePixels(rows(100), 101, 12); scroll.seekPixels(247);
        scroll.updatePixels(rows(110), 149, 12); assertEquals(247, scroll.offsetPixels()); assertFalse(scroll.atEnd());
        scroll.updatePixels(rows(110), 149, 24); assertEquals(494, scroll.offsetPixels());
        scroll.end(); assertEquals(scroll.maxPixels(), scroll.offsetPixels());
        scroll.updatePixels(rows(120), 155, 24); assertEquals(scroll.maxPixels(), scroll.offsetPixels());
        scroll.reset(); scroll.updatePixels(rows(2), 155, 12); assertEquals(0, scroll.maxPixels()); assertEquals(0, scroll.offsetPixels());
    }
}
