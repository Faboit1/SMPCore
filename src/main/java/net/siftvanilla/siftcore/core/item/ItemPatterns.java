package net.siftvanilla.siftcore.core.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A list of item type patterns such as {@code minecraft:barrier} or {@code minecraft:*_spawn_egg}. {@code *} matches
 * any run of characters; a pattern without a namespace means {@code minecraft:}. Matching is case-insensitive.
 */
public final class ItemPatterns {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_.*/-]+(:[a-z0-9_.*/-]+)?");

    private final List<String> sources;
    private final List<Pattern> patterns;

    private ItemPatterns(List<String> sources, List<Pattern> patterns) {
        this.sources = List.copyOf(sources);
        this.patterns = List.copyOf(patterns);
    }

    public static ItemPatterns none() {
        return new ItemPatterns(List.of(), List.of());
    }

    /**
     * Compiles the entries.
     *
     * @throws IllegalArgumentException naming the first invalid entry
     */
    public static ItemPatterns compile(List<String> entries) {
        List<String> sources = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : entries) {
            String normalized = normalize(entry);
            if (!VALID.matcher(normalized).matches()) {
                throw new IllegalArgumentException("'" + entry + "' is not an item id like minecraft:barrier or minecraft:*_spawn_egg");
            }
            StringBuilder regex = new StringBuilder();
            for (String part : normalized.split("\\*", -1)) {
                if (!regex.isEmpty()) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            sources.add(normalized);
            patterns.add(Pattern.compile(regex.toString()));
        }
        return new ItemPatterns(sources, patterns);
    }

    private static String normalize(String entry) {
        String lower = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /** True when the item type key matches any pattern. */
    public boolean matches(String typeKey) {
        if (typeKey == null) {
            return false;
        }
        String key = normalize(typeKey);
        for (Pattern pattern : this.patterns) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    public List<String> entries() {
        return this.sources;
    }

    public int size() {
        return this.patterns.size();
    }
}
