package net.siftvanilla.siftcore.feature.crates;

import java.util.Locale;
import java.util.regex.Pattern;

/** Namespaced ids as config writes them, and their plain names. Pure logic. */
final class Ids {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern TAG = Pattern.compile("<[a-zA-Z!/#?][^>]*>");

    private Ids() {
    }

    /**
     * Normalizes {@code diamond}, {@code minecraft:diamond} or {@code DIAMOND} to {@code minecraft:diamond}.
     * Returns null for text that cannot be an id.
     */
    static String normalize(String raw) {
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

    /** The plain name of an id: {@code minecraft:diamond_sword} is {@code diamond sword}. */
    static String plainName(String id) {
        String path = id.indexOf(':') < 0 ? id : id.substring(id.indexOf(':') + 1);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        return path.replace('_', ' ');
    }

    /**
     * Why a piece of config text is not plain text, or null when it is. Names, lore and display lines are shown
     * exactly as written, so formatting tags and legacy colour codes would appear literally.
     */
    static String plainTextProblem(String text) {
        if (text.indexOf('§') >= 0 || text.indexOf('&') >= 0 && text.matches("(?s).*&[0-9a-fk-orA-FK-OR].*")) {
            return "uses colour codes; write plain text (the design system colours it)";
        }
        if (TAG.matcher(text).find()) {
            return "uses formatting tags; write plain text (the design system colours it)";
        }
        if (text.isBlank()) {
            return "is empty";
        }
        return null;
    }
}
