package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.object.ObjectContents;
import org.junit.jupiter.api.Test;

class TextMetricsTest {

    @Test
    void glyphWidthsFollowTheDefaultFont() {
        assertEquals(List.of(6 * 5), TextMetrics.lineWidths(Component.text("Hello".replace('l', 'a'))));
        assertEquals(List.of(2 + 3 + 4), TextMetrics.lineWidths(Component.text("il ")));
        assertEquals(List.of(7), TextMetrics.lineWidths(Component.text("@")));
    }

    @Test
    void lineBreaksSplitLinesAcrossComponents() {
        Component text = Component.join(JoinConfiguration.newlines(),
            Component.text("aa"), Component.empty(), Component.text("a").append(Component.text("b")));
        assertEquals(List.of(12, 0, 12), TextMetrics.lineWidths(text));
    }

    @Test
    void spritesCountEightPixels() {
        Component icon = Component.object(ObjectContents.sprite(Key.key("minecraft:items"), Key.key("item/gold_ingot")));
        assertEquals(List.of(8 + 4 + 6), TextMetrics.lineWidths(Component.text().append(icon).append(Component.text(" a")).build()));
    }

    @Test
    void clickBoxMatchesTheClientGeometry() {
        Component text = Component.join(JoinConfiguration.newlines(), Component.text("aaaaaaaaaa"), Component.text("aa"));
        TextMetrics.Box box = TextMetrics.clickBox(text, 200, 1.0f);
        assertEquals((60 + 2) * 0.025 + 0.1, box.width(), 1e-4);
        assertEquals((2 * 10 + 1) * 0.025 + 0.1, box.height(), 1e-4);
        TextMetrics.Box doubled = TextMetrics.clickBox(text, 200, 2.0f);
        assertEquals((60 + 2) * 0.05 + 0.1, doubled.width(), 1e-4);
    }

    @Test
    void longLinesWrapAtTheLineWidth() {
        Component text = Component.text("a".repeat(50));
        TextMetrics.Box box = TextMetrics.clickBox(text, 100, 1.0f);
        assertEquals((100 + 2) * 0.025 + 0.1, box.width(), 1e-4, "never wider than the line width");
        assertEquals((3 * 10 + 1) * 0.025 + 0.1, box.height(), 1e-4, "300 pixels wrap into three lines");
    }

    @Test
    void anEmptyTextStillGetsAUsableBox() {
        TextMetrics.Box box = TextMetrics.clickBox(Component.empty(), 200, 1.0f);
        assertTrue(box.width() > 0 && box.height() > 0);
    }
}
