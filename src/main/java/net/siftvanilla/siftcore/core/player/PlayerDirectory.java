package net.siftvanilla.siftcore.core.player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every player who ever joined: UUID to name, name to UUID (case-insensitive, latest owner wins) and a salted hash
 * of their last IP (used only to stop alt-account farming; the IP itself is never stored). Held in memory, which is
 * bounded by the number of unique players.
 */
public final class PlayerDirectory {

    /** What we know about a player. */
    public record Known(UUID uuid, String name, long firstJoin, long lastSeen, String ipHash) {
    }

    private final Database database;
    private final byte[] salt;
    private final Map<UUID, Known> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    public PlayerDirectory(Database database, byte[] salt) {
        this.database = database;
        this.salt = salt.clone();
    }

    public void load() throws Exception {
        List<Known> rows = this.database.read(c -> {
            List<Known> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, name, first_join, last_seen, ip_hash FROM players ORDER BY last_seen ASC")) {
                while (rs.next()) {
                    list.add(new Known(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5)));
                }
            }
            return list;
        }).get();
        this.byUuid.clear();
        this.byName.clear();
        for (Known known : rows) {
            put(known);
        }
    }

    private void put(Known known) {
        Known previous = this.byUuid.put(known.uuid(), known);
        if (previous != null && !previous.name().equalsIgnoreCase(known.name())) {
            this.byName.remove(previous.name().toLowerCase(Locale.ROOT), known.uuid());
        }
        this.byName.put(known.name().toLowerCase(Locale.ROOT), known.uuid());
    }

    /** Records a join; returns true if this is the player's first join. */
    public boolean recordJoin(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        Known previous = this.byUuid.get(uuid);
        String hash = ip == null ? (previous == null ? null : previous.ipHash()) : hashIp(ip);
        Known known = new Known(uuid, name, previous == null ? now : previous.firstJoin(), now, hash);
        put(known);
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("players",
                new String[] {"uuid"}, new String[] {"name", "name_lower", "first_join", "last_seen", "ip_hash"}))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setLong(4, known.firstJoin());
                ps.setLong(5, now);
                ps.setString(6, hash);
                ps.executeUpdate();
            }
            return null;
        });
        return previous == null;
    }

    /** Updates last-seen on quit. */
    public void recordQuit(UUID uuid) {
        Known known = this.byUuid.get(uuid);
        if (known == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.byUuid.put(uuid, new Known(uuid, known.name(), known.firstJoin(), now, known.ipHash()));
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, now);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Known> get(UUID uuid) {
        return Optional.ofNullable(this.byUuid.get(uuid));
    }

    public Optional<UUID> uuid(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(this.byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The last known name, or the shortened UUID for unknown players. */
    public String name(UUID uuid) {
        Known known = this.byUuid.get(uuid);
        return known == null ? uuid.toString().substring(0, 8) : known.name();
    }

    public String ipHash(UUID uuid) {
        Known known = this.byUuid.get(uuid);
        return known == null ? null : known.ipHash();
    }

    /** True when both players were last seen from the same IP. */
    public boolean sameIp(UUID a, UUID b) {
        String ha = ipHash(a);
        return ha != null && ha.equals(ipHash(b));
    }

    /** Names starting with {@code prefix} (case-insensitive), at most {@code limit}. */
    public List<String> namesStartingWith(String prefix, int limit) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Known known : this.byUuid.values()) {
            if (known.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(known.name());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public int size() {
        return this.byUuid.size();
    }

    /** A snapshot of every known player's UUID and last name. */
    public Map<UUID, String> names() {
        Map<UUID, String> names = new java.util.HashMap<>(this.byUuid.size() * 2);
        this.byUuid.forEach((uuid, known) -> names.put(uuid, known.name()));
        return names;
    }

    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(this.salt);
            digest.update(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
