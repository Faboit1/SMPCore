package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.kyori.adventure.text.Component;

/**
 * Joins names for chat lines and dialog bodies: "Alex", "Alex and Bob", "Alex, Bob and Cara", and past {@code max}
 * names "Alex, Bob and 2 more". The words come from the lang file; the names are already built components (plain
 * text, often clickable). Pure.
 */
public final class NameList {

    private NameList() {
    }

    /**
     * @param names the names in order
     * @param max   names shown before the rest is counted (at least 1)
     * @param and   the word joining the last two parts ("and")
     * @param more  "N more" for a count
     */
    public static Component join(List<Component> names, int max, String and, IntFunction<String> more) {
        if (names.isEmpty()) {
            return Component.empty();
        }
        int shown = Math.min(names.size(), Math.max(1, max));
        List<Component> parts = new ArrayList<>(names.subList(0, shown));
        if (names.size() > shown) {
            parts.add(Component.text(more.apply(names.size() - shown)));
        }
        Component result = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                result = result.append(Component.text(i == parts.size() - 1 ? " " + and + " " : ", "));
            }
            result = result.append(parts.get(i));
        }
        return result;
    }
}
