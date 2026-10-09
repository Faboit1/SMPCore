package net.siftvanilla.siftcore.feature.settings;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Rows stored under a setting's old ids ({@link SettingOptions#legacy}), as the staff tools and the API see a player
 * who has not logged in since the setting got its new id. Core moves such a row to the new id when the player next
 * loads; until then it is the player's value of the setting.
 * <p>
 * Core's writes for players who are not loaded touch only the new id, so a change written then (above all one back to
 * the default, which deletes the row) would be undone at the next login by the old row moving over. The staff tools
 * and the API therefore delete the old rows first ({@link #forget}). Settings whose old id is still registered as a
 * setting of its own ({@link Registry.Entry#superseded()}) have no legacy rows: the old id is just another setting.
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

    /** The old ids whose rows still stand in for a setting (none while it is superseded). */
    static List<String> oldIds(Registry.Entry<?> entry) {
        if (entry.superseded()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(entry.options().legacy().size());
        for (SettingOptions.Legacy legacy : entry.options().legacy()) {
            ids.add(legacy.oldId());
        }
        return ids;
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

    /**
     * Deletes a player's rows under a setting's old ids, queued in the writer's order. Call it before core writes or
     * deletes the setting for a player who may not be loaded, so a login read queued in between sees neither. Does
     * nothing (a completed future) for a setting without old ids.
     */
    static CompletableFuture<Void> forget(Database database, UUID player, Registry.Entry<?> entry) {
        List<String> ids = oldIds(entry);
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM settings WHERE uuid = ? AND setting = ?")) {
                for (String id : ids) {
                    ps.setString(1, player.toString());
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }
}
