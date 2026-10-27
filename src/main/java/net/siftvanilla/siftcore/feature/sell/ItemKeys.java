package net.siftvanilla.siftcore.feature.sell;

import java.util.Locale;
import java.util.regex.Pattern;

/** Item keys as config and players write them, and their plain names. Pure logic. */
public final class ItemKeys {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private ItemKeys() {
    }

    /**
     * Normalizes {@code diamond}, {@code minecraft:diamond} or {@code DIAMOND} to {@code minecraft:diamond}.
     * Returns null for text that cannot be a key.
     */
    public static String normalize(String raw) {
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

    /** The plain name of an item key: {@code minecraft:diamond_sword} is {@code diamond sword}. */
    public static String name(String key) {
        String path = key.indexOf(':') < 0 ? key : key.substring(key.indexOf(':') + 1);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        return path.replace('_', ' ');
    }

    /** The key without the {@code minecraft:} namespace, for compact display ({@code diamond}). */
    public static String shortKey(String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }
}
