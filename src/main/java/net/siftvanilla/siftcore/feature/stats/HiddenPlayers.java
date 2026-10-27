package net.siftvanilla.siftcore.feature.stats;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Who is left off every leaderboard ({@code hide-from-leaderboards}, for staff and test accounts with
 * {@code siftcore.stats.hide}). Most accounts are offline, so each rebuild reads the setting's rows from the
 * {@code settings} table; online players read their loaded value with their permissions applied. An offline player's
 * stored choice counts until they join again, even if they lost the permission meanwhile.
 */
final class HiddenPlayers {

    /**
     * Who is hidden at one rebuild.
     *
     * @param test  whether an account is hidden
     * @param count how many accounts are known to be hidden ({@link Integer#MAX_VALUE} when everyone is unless they
     *              chose otherwise), so a board can fetch that many more rows
     */
    record Hidden(Predicate<UUID> test, int count) {

        static final Hidden NONE = new Hidden(uuid -> false, 0);
    }

    private final Database database;
    private final PlayerSettings settings;

    HiddenPlayers(Database database, PlayerSettings settings) {
        this.database = database;
        this.settings = settings;
    }

    /** Reads who is hidden now (off the main threads; the online players' values come from memory). */
    CompletableFuture<Hidden> load() {
        Toggle setting = SharedSettings.HIDE_FROM_LEADERBOARDS;
        boolean fallback = this.settings.defaultValue(setting);
        boolean fixed = this.settings.locked(setting) || this.settings.hidden(setting);
        Map<UUID, Boolean> online = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.put(player.getUniqueId(), this.settings.get(player, setting));
        }
        if (fixed) {
            return CompletableFuture.completedFuture(decide(Map.of(), online, fallback, true));
        }
        return this.database.read(c -> {
            Map<UUID, Boolean> stored = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, value FROM settings WHERE setting = ?")) {
                ps.setString(1, setting.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = uuid(rs.getString(1));
                        Boolean value = Toggle.parse(rs.getString(2));
                        if (uuid != null && value != null) {
                            stored.put(uuid, value);
                        }
                    }
                }
            }
            return decide(stored, online, fallback, false);
        });
    }

    /**
     * Who is hidden: an online player's loaded value, else (unless the server fixed the value for everyone) their
     * stored choice, else the server's default.
     *
     * @param stored   stored choices of the setting (rows that are not a switch value are left out)
     * @param online   the online players' values with their permissions applied
     * @param fallback the value of players who never chose (the server's lock or default, else off)
     * @param fixed    the server locked or hid the setting, so stored choices don't count
     */
    static Hidden decide(Map<UUID, Boolean> stored, Map<UUID, Boolean> online, boolean fallback, boolean fixed) {
        Map<UUID, Boolean> onlineCopy = Map.copyOf(online);
        Map<UUID, Boolean> storedCopy = fixed ? Map.of() : Map.copyOf(stored);
        Predicate<UUID> test = uuid -> {
            Boolean now = onlineCopy.get(uuid);
            if (now != null) {
                return now;
            }
            Boolean chosen = storedCopy.get(uuid);
            return chosen != null ? chosen : fallback;
        };
        if (fallback) {
            return new Hidden(test, Integer.MAX_VALUE);
        }
        Set<UUID> known = new HashSet<>(storedCopy.keySet());
        known.addAll(onlineCopy.keySet());
        int count = 0;
        for (UUID uuid : known) {
            if (test.test(uuid)) {
                count++;
            }
        }
        return new Hidden(test, count);
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
