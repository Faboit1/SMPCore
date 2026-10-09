package net.siftvanilla.siftcore.feature.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.player.Overrides;

/**
 * Parsed {@code features/settings.yml}. The overrides are kept as text: they are checked against the settings
 * registry once every feature registered its settings ({@link SettingsOverrides}).
 *
 * @param skipSingleGroup when only one group has settings for a player, open it directly instead of the group list
 * @param defaults        server defaults by setting id (what players who never changed a setting read)
 * @param locked          forced values by setting id (players can't change them)
 * @param hidden          setting ids kept out of the dialog and commands; everyone reads their lock or default
 * @param categories      per group id: a different place in the group list, a different icon or button colour
 */
record SettingsConfig(boolean skipSingleGroup, Map<String, String> defaults, Map<String, String> locked, Set<String> hidden,
                      Map<String, CategoryOverride> categories) {

    /**
     * A group's look in the dialog, changed by the server.
     *
     * @param order its place in the group list (lower first), or null for the built-in place
     * @param icon  an {@code icons.yml} name, or null for the built-in icon
     * @param color the colour of its button, or null for the built-in colour
     */
    record CategoryOverride(Integer order, String icon, TextColor color) {

        /** A place and an icon, the built-in colour. */
        CategoryOverride(Integer order, String icon) {
            this(order, icon, null);
        }
    }

    /** The shipped settings: the only group opens directly, no overrides. */
    static final SettingsConfig DEFAULTS = new SettingsConfig(true, Map.of(), Map.of(), Set.of(), Map.of());

    SettingsConfig {
        defaults = Map.copyOf(defaults);
        locked = Map.copyOf(locked);
        hidden = Set.copyOf(hidden);
        categories = Map.copyOf(categories);
    }

    static SettingsConfig parse(ConfigReader r) {
        Set<String> hidden = new LinkedHashSet<>();
        for (String id : r.optionalStringList("hidden")) {
            hidden.add(id.strip().toLowerCase(Locale.ROOT));
        }
        return new SettingsConfig(
            r.bool("skip-single-group", true),
            entries(r, "defaults"),
            entries(r, "locked"),
            hidden,
            categories(r));
    }

    /** {@code setting-id: value} pairs of a section; YAML booleans and numbers are read as their text. */
    private static Map<String, String> entries(ConfigReader r, String path) {
        if (r.has(path) && !r.isSection(path)) {
            r.problem(path, "must be a section of 'setting-id: value' lines");
            return Map.of();
        }
        ConfigReader section = r.section(path, false);
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : section.keys()) {
            String value = section.string(key, null);
            if (value != null) {
                map.put(key.strip().toLowerCase(Locale.ROOT), value.strip());
            }
        }
        return map;
    }

    /** {@code categories: { <group id>: { order: 15, icon: star, color: "#55FFFF" } }}; every key is optional. */
    private static Map<String, CategoryOverride> categories(ConfigReader r) {
        if (r.has("categories") && !r.isSection("categories")) {
            r.problem("categories", "must be a section of '<group id>: { order: <number>, icon: <icon name>, color: <#hex> }' entries");
            return Map.of();
        }
        Map<String, CategoryOverride> map = new LinkedHashMap<>();
        r.children("categories").forEach((id, section) -> {
            Integer order = section.has("order") ? section.integer("order", -1_000_000, 1_000_000, 0) : null;
            String icon = section.has("icon") ? section.string("icon", "").strip().toLowerCase(Locale.ROOT) : null;
            TextColor color = section.has("color") ? section.custom("color", v -> {
                TextColor parsed = TextColor.fromHexString(v.strip());
                if (parsed == null) {
                    throw new IllegalArgumentException("is not a hex colour");
                }
                return parsed;
            }, "a hex colour like #55FFFF", null) : null;
            map.put(id.strip().toLowerCase(Locale.ROOT), new CategoryOverride(order, icon == null || icon.isEmpty() ? null : icon, color));
        });
        return map;
    }

    /** The overrides as the settings registry takes them. */
    Overrides overrides() {
        return new Overrides(this.defaults, this.locked, this.hidden);
    }
}
