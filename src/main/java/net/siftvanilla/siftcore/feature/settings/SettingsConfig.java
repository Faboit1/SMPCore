package net.siftvanilla.siftcore.feature.settings;

import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/settings.yml}.
 *
 * @param pageSize        switches per page of a settings group
 * @param skipSingleGroup when only one group has switches for a player, open it directly instead of the group list
 */
record SettingsConfig(int pageSize, boolean skipSingleGroup) {

    static SettingsConfig parse(ConfigReader r) {
        return new SettingsConfig(
            r.integer("page-size", 1, 20, 8),
            r.bool("skip-single-group", true));
    }
}
