package net.siftvanilla.siftcore.feature.settings;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingOptions;

/**
 * Rows stored under a setting's old ids ({@link SettingOptions#legacy}), as the staff tools and the API see a player
 * who has not logged in since the setting got its new id. Core moves such a row to the new id when the player next
 * loads; until then it is the player's value of the setting.
 * <p>
 * Core's writes and resets for players who are not loaded delete the old rows along with the change (in the same
 * write), so a change written then is never undone at the next login by an old row moving over. Settings whose old id
 * is still registered as a setting of its own ({@link Registry.Entry#superseded()}) have no legacy rows: the old id is
 * just another setting.
 */
final class LegacyRows {

    /**
     * A player's rows with legacy rows read as their settings.
     *
     * @param rows  every row by id, a setting with no row of its own taking the value its first readable legacy row maps
     *              to (in stored form), and the legacy rows used that way left out
     * @param moved setting id to the old id its value was read from
     */
    record Resolved(Map<String, String> rows, Map<String, String> moved) {
        Resolved {
            rows = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rows));
            moved = Map.copyOf(moved);
        }
    }

    private LegacyRows() {
    }

    /**
     * Reads legacy rows as their settings, the way core moves them when the player loads: for each setting without a
     * row of its own, the first old id with a row whose mapped value the setting reads.
     */
    static Resolved resolve(Map<String, String> rows, Registry registry) {
        Map<String, String> resolved = new LinkedHashMap<>(rows);
        Map<String, String> moved = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Registry.Entry<?> entry : registry.byId().values()) {
            if (entry.superseded() || rows.containsKey(entry.id())) {
                continue;
            }
            for (SettingOptions.Legacy legacy : entry.options().legacy()) {
                String old = rows.get(legacy.oldId());
                String value = old == null ? null : mapped(entry.setting(), legacy, old);
                if (value != null) {
                    resolved.put(entry.id(), value);
                    moved.put(entry.id(), legacy.oldId());
                    used.add(legacy.oldId());
                    break;
                }
            }
        }
        used.forEach(resolved::remove);
        return new Resolved(resolved, moved);
    }

    private static <T> String mapped(PlayerSetting<T> setting, SettingOptions.Legacy legacy, String stored) {
        String mapped;
        try {
            mapped = legacy.mapValue().apply(stored);
        } catch (RuntimeException e) {
            return null;
        }
        T value = mapped == null ? null : setting.decodeOrNull(mapped);
        return value == null ? null : setting.encode(value);
    }
}
