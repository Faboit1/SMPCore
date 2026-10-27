package net.siftvanilla.siftcore.feature.displays;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

/**
 * Estimates the size of a text display's text in blocks, so the click box over a leaderboard covers it.
 * <p>
 * The geometry is the 26.2 client's {@code TextDisplayRenderer}: one text pixel is 0.025 blocks, a line is 10 pixels
 * (9 + 1 spacing), the text grows upwards from the entity position and is centred on it, and lines wrap at the line
 * width. The server has no font, so glyph widths come from the default font's advances (most letters 6 pixels,
 * narrow ones less, sprites 8). Only used for the click box, so a pixel either way does not matter.
 */
final class TextMetrics {

    static final double BLOCKS_PER_PIXEL = 0.025;
    static final int LINE_HEIGHT = 10;
    static final int SPRITE_WIDTH = 8;
    /** Extra blocks around the text so clicks near the edge still count. */
    static final double PADDING = 0.1;

    /** A click box: width and depth, then height, in blocks. */
    record Box(float width, float height) {
    }

    private TextMetrics() {
    }

    /** The width of one glyph in pixels, including the 1 pixel gap after it. */
    static int glyph(int codePoint) {
        return switch (codePoint) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    /** The pixel width of each line of {@code text} (lines split at line breaks, before wrapping). */
    static List<Integer> lineWidths(Component text) {
        List<Integer> widths = new ArrayList<>();
        widths.add(0);
        walk(text, widths);
        return widths;
    }

    private static void walk(Component component, List<Integer> widths) {
        switch (component) {
            case TextComponent text -> add(text.content(), widths);
            case ObjectComponent _ -> widths.set(widths.size() - 1, widths.getLast() + SPRITE_WIDTH);
            case TranslatableComponent translatable -> add(translatable.fallback() != null ? translatable.fallback() : translatable.key(), widths);
            case KeybindComponent keybind -> add(keybind.keybind(), widths);
            default -> {
            }
        }
        for (Component child : component.children()) {
            walk(child, widths);
        }
    }

    private static void add(String content, List<Integer> widths) {
        int width = widths.getLast();
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '\n') {
                widths.set(widths.size() - 1, width);
                widths.add(0);
                width = 0;
            } else {
                width += glyph(codePoint);
            }
        }
        widths.set(widths.size() - 1, width);
    }

    /** The click box covering {@code text} shown with this line width and scale. */
    static Box clickBox(Component text, int lineWidth, float scale) {
        int lines = 0;
        int widest = 0;
        for (int width : lineWidths(text)) {
            int wrapped = Math.max(1, (width + lineWidth - 1) / lineWidth);
            lines += wrapped;
            widest = Math.max(widest, Math.min(width, lineWidth));
        }
        double width = (widest + 2) * BLOCKS_PER_PIXEL * scale + PADDING;
        double height = (lines * LINE_HEIGHT + 1) * BLOCKS_PER_PIXEL * scale + PADDING;
        return new Box((float) width, (float) height);
    }
}
