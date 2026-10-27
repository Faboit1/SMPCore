package net.siftvanilla.siftcore.feature.kits;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ids and plain text as {@code features/kits.yml} writes them. Pure logic. */
final class PlainText {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern TAG = Pattern.compile("<[a-zA-Z!/#?][^>]*>");
    private static final Pattern LEGACY = Pattern.compile("(?s).*&[0-9a-fk-orA-FK-OR].*");

    private PlainText() {
    }

    /**
     * Normalizes {@code diamond}, {@code minecraft:diamond} or {@code DIAMOND} to {@code minecraft:diamond}.
     * Returns null for text that cannot be an id.
     */
    static String id(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        int colon = text.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : text.substring(0, colon);
        String path = colon < 0 ? text : text.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            return null;
        }
        return namespace + ":" + path;
    }

    /**
     * Why a piece of config text is not plain text, or null when it is. Names, lore and descriptions are shown exactly
     * as written, so formatting tags and legacy colour codes would appear literally.
     */
    static String problem(String text) {
        if (text.isBlank()) {
            return "is empty";
        }
        if (text.indexOf('§') >= 0 || text.indexOf('&') >= 0 && LEGACY.matcher(text).matches()) {
            return "uses colour codes; write plain text (the design system colours it)";
        }
        if (TAG.matcher(text).find()) {
            return "uses formatting tags; write plain text (the design system colours it)";
        }
        return null;
    }

    /** {@code supporter} becomes {@code Supporter}, {@code daily_food} becomes {@code Daily food}. */
    static String capitalize(String id) {
        String text = id.replace('_', ' ').replace('-', ' ').strip();
        if (text.isEmpty()) {
            return id;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
