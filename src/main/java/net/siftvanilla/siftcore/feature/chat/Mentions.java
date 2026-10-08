package net.siftvanilla.siftcore.feature.chat;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the players a chat message mentions: {@code @name}, or (when enabled) a player's name written as a whole
 * word. Names compare ignoring case. A name inside a longer word ({@code alexander} for {@code Alex}) or after a
 * letter ({@code mail@alex}) is not a mention. Pure and thread-safe.
 */
final class Mentions {

    private Mentions() {
    }

    /**
     * The mentioned names, as spelled in {@code names}, in the order they first appear.
     *
     * @param names          the names that can be mentioned (online players)
     * @param plainNames     whether a bare name (without {@code @}) mentions too
     * @param minPlainLength bare names shorter than this never mention (so a player called {@code ok} isn't pinged
     *                       all day); {@code @name} always works
     */
    static Set<String> find(String text, Collection<String> names, boolean plainNames, int minPlainLength) {
        Set<String> found = new LinkedHashSet<>();
        if (text.isEmpty() || names.isEmpty()) {
            return found;
        }
        Map<String, String> byLower = new HashMap<>();
        for (String name : names) {
            byLower.put(name.toLowerCase(Locale.ROOT), name);
        }
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (!isNameChar(c) && c != '@') {
                i++;
                continue;
            }
            boolean at = c == '@';
            if (at) {
                boolean afterWord = i > 0 && isNameChar(text.charAt(i - 1));
                i++;
                if (afterWord) {
                    while (i < length && isNameChar(text.charAt(i))) {
                        i++;
                    }
                    continue;
                }
            }
            int start = i;
            while (i < length && isNameChar(text.charAt(i))) {
                i++;
            }
            if (i == start) {
                continue;
            }
            String word = text.substring(start, i);
            String name = byLower.get(word.toLowerCase(Locale.ROOT));
            if (name != null && (at || (plainNames && word.length() >= minPlainLength))) {
                found.add(name);
            }
        }
        return found;
    }

    /** Characters allowed in Minecraft names. */
    static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
