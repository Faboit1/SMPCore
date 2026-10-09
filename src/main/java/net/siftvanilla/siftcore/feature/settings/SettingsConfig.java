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
 * @param pageSize        settings per page of a settings group
 * @param skipSingleGroup when only one group has settings for a player, open it directly instead of the group list
 * @param defaults        server defaults by setting id (what players who never changed a setting read)
 * @param locked          forced values by setting id (players can't change them)
 * @param hidden          setting ids kept out of the dialog and commands; everyone reads their lock or default
 */
record SettingsConfig(int pageSize, boolean skipSingleGroup, Map<String, String> defaults, Map<String, String> locked,
                      Set<String> hidden) {

    SettingsConfig {
        defaults = Map.copyOf(defaults);
        locked = Map.copyOf(locked);
        hidden = Set.copyOf(hidden);
    }

    static SettingsConfig parse(ConfigReader r) {
        Set<String> hidden = new LinkedHashSet<>();
        for (String id : r.optionalStringList("hidden")) {
            hidden.add(id.strip().toLowerCase(Locale.ROOT));
        }
        return new SettingsConfig(
            r.integer("page-size", 1, 20, 8),
            r.bool("skip-single-group", true),
            entries(r, "defaults"),
            entries(r, "locked"),
            hidden);
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

    /** The overrides as the settings registry takes them. */
    Overrides overrides() {
        return new Overrides(this.defaults, this.locked, this.hidden);
    }
}
