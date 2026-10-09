package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.Toggle;

/**
 * Checks the server's settings overrides against the registry (pure, unit tested): ids nobody registered, values a
 * setting can't take, and settings both defaulted and locked. Problems are reported as warnings and by the self-test;
 * bad entries are simply not applied.
 */
final class SettingsOverrides {

    private SettingsOverrides() {
    }

    /** Every problem with the overrides, as lines like {@code locked.death-messages: 'maybe' is not a value of it (...)}. */
    static List<String> problems(Registry registry, Overrides overrides) {
        List<String> problems = new ArrayList<>();
        check(registry, "defaults", overrides.defaults(), problems);
        check(registry, "locked", overrides.locked(), problems);
        for (String id : new TreeSet<>(overrides.hidden())) {
            if (registry.entry(id) == null) {
                problems.add("hidden: " + id + " is not a setting on this server");
            }
        }
        for (String id : new TreeSet<>(overrides.defaults().keySet())) {
            if (overrides.locked().containsKey(id)) {
                problems.add("defaults." + id + ": the setting is also locked, so the lock wins");
            }
        }
        return problems;
    }

    private static void check(Registry registry, String section, Map<String, String> entries, List<String> problems) {
        for (String id : new TreeSet<>(entries.keySet())) {
            Registry.Entry<?> entry = registry.entry(id);
            if (entry == null) {
                problems.add(section + "." + id + ": there is no setting called " + id + " on this server");
            } else if (PlayerSettings.configValue(entry, entries.get(id)) == null) {
                problems.add(section + "." + id + ": '" + entries.get(id) + "' is not a value of it (use " + allowed(entry.setting()) + ")");
            }
        }
    }

    /** The values a setting takes, for messages. */
    static String allowed(PlayerSetting<?> setting) {
        return switch (setting) {
            case Toggle toggle -> "true or false";
            case Choice<?> choice -> String.join(", ", choice.optionIds());
            case NumberSetting number -> "a whole number from " + number.min() + " to " + number.max()
                + (number.step() == 1 ? "" : " in steps of " + number.step());
        };
    }
}
