package net.siftvanilla.siftcore.feature.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Who is left off the money leaderboard ({@code hide-from-leaderboards}, for staff and test accounts with
 * {@code siftcore.stats.hide}). Most accounts are offline, so each leaderboard rebuild reads the setting's rows from the
 * {@code settings} table; online players read their loaded value with their permissions applied. An offline player's
 * stored choice counts until they join again, even if they lost the permission meanwhile.
 */
final class HiddenAccounts {

    /** How long a rebuild waits for the read before it keeps the last answer. */
    private static final long READ_SECONDS = 10;

    private final Database database;
    private final PlayerSettings settings;

    HiddenAccounts(Database database, PlayerSettings settings) {
        this.database = database;
        this.settings = settings;
    }

    /** Who is hidden now. Blocks on one database read: call it off the world threads (or at startup). */
    Predicate<UUID> load() throws Exception {
        Toggle setting = SharedSettings.HIDE_FROM_LEADERBOARDS;
        boolean fallback = this.settings.defaultValue(setting);
        boolean fixed = this.settings.locked(setting) || this.settings.hidden(setting);
        Map<UUID, Boolean> online = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.put(player.getUniqueId(), this.settings.get(player, setting));
        }
        if (fixed) {
            return decide(Map.of(), online, fallback, true);
        }
        Map<UUID, Boolean> stored = this.database.read(c -> {
            Map<UUID, Boolean> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, value FROM settings WHERE setting = ?")) {
                ps.setString(1, setting.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = uuid(rs.getString(1));
                        Boolean value = Toggle.parse(rs.getString(2));
                        if (uuid != null && value != null) {
                            rows.put(uuid, value);
                        }
                    }
                }
            }
            return rows;
        }).get(READ_SECONDS, TimeUnit.SECONDS);
        return decide(stored, online, fallback, false);
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
    static Predicate<UUID> decide(Map<UUID, Boolean> stored, Map<UUID, Boolean> online, boolean fallback, boolean fixed) {
        Map<UUID, Boolean> onlineCopy = Map.copyOf(online);
        Map<UUID, Boolean> storedCopy = fixed ? Map.of() : Map.copyOf(stored);
        return uuid -> {
            Boolean now = onlineCopy.get(uuid);
            if (now != null) {
                return now;
            }
            Boolean chosen = storedCopy.get(uuid);
            return chosen != null ? chosen : fallback;
        };
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
