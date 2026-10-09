package net.siftvanilla.siftcore.feature.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.player.Overrides;

/**
 * Parsed {@code features/settings.yml}. The overrides are kept as text: they are checked against the settings
 * registry once every feature registered its settings ({@link SettingsOverrides}).
 *
 * @param pageSize         settings per page of a settings group
 * @param skipSingleGroup  when only one group has settings for a player, open it directly instead of the group list
 * @param showDescriptions one line per setting saying what it does above the inputs (false: compact pages)
 * @param defaults         server defaults by setting id (what players who never changed a setting read)
 * @param locked           forced values by setting id (players can't change them)
 * @param hidden           setting ids kept out of the dialog and commands; everyone reads their lock or default
 * @param categories       per group id: a different place in the group list and a different icon
 */
record SettingsConfig(int pageSize, boolean skipSingleGroup, boolean showDescriptions, Map<String, String> defaults,
                      Map<String, String> locked, Set<String> hidden, Map<String, CategoryOverride> categories) {

    /**
     * A group's look in the dialog, changed by the server.
     *
     * @param order its place in the group list (lower first), or null for the built-in place
     * @param icon  an {@code icons.yml} name, or null for the built-in icon
     */
    record CategoryOverride(Integer order, String icon) {
    }

    /** The shipped settings: 8 per page, the only group opens directly, descriptions shown, no overrides. */
    static final SettingsConfig DEFAULTS = new SettingsConfig(8, true, true, Map.of(), Map.of(), Set.of(), Map.of());

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
            r.integer("page-size", 1, 20, 8),
            r.bool("skip-single-group", true),
            r.has("show-descriptions") ? r.bool("show-descriptions", true) : true,
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

    /** {@code categories: { <group id>: { order: 15, icon: star } }}; both keys are optional. */
    private static Map<String, CategoryOverride> categories(ConfigReader r) {
        if (r.has("categories") && !r.isSection("categories")) {
            r.problem("categories", "must be a section of '<group id>: { order: <number>, icon: <icon name> }' entries");
            return Map.of();
        }
        Map<String, CategoryOverride> map = new LinkedHashMap<>();
        r.children("categories").forEach((id, section) -> {
            Integer order = section.has("order") ? section.integer("order", -1_000_000, 1_000_000, 0) : null;
            String icon = section.has("icon") ? section.string("icon", "").strip().toLowerCase(Locale.ROOT) : null;
            map.put(id.strip().toLowerCase(Locale.ROOT), new CategoryOverride(order, icon == null || icon.isEmpty() ? null : icon));
        });
        return map;
    }

    /** The overrides as the settings registry takes them. */
    Overrides overrides() {
        return new Overrides(this.defaults, this.locked, this.hidden);
    }
}
