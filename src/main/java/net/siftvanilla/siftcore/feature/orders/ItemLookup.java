package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Finds an item type from what a player typed: its key ({@code minecraft:diamond_block}, {@code diamond_block}), the
 * key with spaces ({@code diamond block}) or its English name ({@code Block of Diamond}), ignoring case and extra
 * spaces. Pure: the item registry is passed in as a map of keys to names.
 */
final class ItemLookup {

    private static final String MINECRAFT = "minecraft:";

    private final Map<String, String> byKey;
    private final Map<String, String> byName;
    private final TreeMap<String, String> paths;

    /**
     * @param names every orderable item: full key ({@code minecraft:diamond}) to English name ({@code Diamond})
     */
    ItemLookup(Map<String, String> names) {
        this.byKey = new HashMap<>();
        this.byName = new HashMap<>();
        this.paths = new TreeMap<>();
        names.forEach((key, name) -> {
            String full = key.toLowerCase(Locale.ROOT);
            this.byKey.put(full, full);
            this.paths.put(path(full), full);
            String normalized = normalize(name);
            if (!normalized.isEmpty()) {
                this.byName.putIfAbsent(normalized, full);
            }
        });
    }

    /** Lowercase, single spaces, trimmed. */
    static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The key without the {@code minecraft:} namespace. */
    static String path(String key) {
        return key.startsWith(MINECRAFT) ? key.substring(MINECRAFT.length()) : key;
    }

    /** The full key for what the player typed, or empty when nothing matches. */
    Optional<String> find(String input) {
        String text = normalize(input);
        if (text.isEmpty() || text.length() > 96) {
            return Optional.empty();
        }
        String key = text.replace(' ', '_');
        String full = key.contains(":") ? key : MINECRAFT + key;
        if (this.byKey.containsKey(full)) {
            return Optional.of(full);
        }
        return Optional.ofNullable(this.byName.get(text));
    }

    /** Key paths starting with {@code prefix} (for command suggestions), sorted, at most {@code limit}. */
    List<String> suggest(String prefix, int limit) {
        String start = path(normalize(prefix).replace(' ', '_'));
        List<String> result = new ArrayList<>();
        for (String path : this.paths.tailMap(start, true).keySet()) {
            if (!path.startsWith(start) || result.size() >= limit) {
                break;
            }
            result.add(path);
        }
        return Collections.unmodifiableList(result);
    }
}
