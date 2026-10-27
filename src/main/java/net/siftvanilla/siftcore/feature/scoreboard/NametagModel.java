package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which nametag team every player belongs to: one team per rank (its position in the rank order plus its label),
 * the same on every player's board. Team names start with the rank position, so the client's tab list, which sorts
 * by team name, lists higher ranks first. Not thread-safe: used on the global region thread only. Pure.
 */
public final class NametagModel {

    /** Prefix of every team this model names; teams with other names on a board are left alone. */
    public static final String PREFIX = "sift_r";

    /** A rank's team: its position in the rank order (0 is the highest) and its label. */
    public record Key(int order, String label) {
    }

    /** A team as a board should show it: its rank and its members (player names). */
    public record Team(Key key, Set<String> members) {
    }

    private final Map<String, Key> members = new HashMap<>();
    private final Map<Key, String> names = new HashMap<>();
    private long version;
    private Map<String, Team> snapshot;

    /** Puts a player in the team of a rank, or in none with a null key. Returns whether anything changed. */
    public boolean set(String player, Key key) {
        Key previous = key == null ? this.members.remove(player) : this.members.put(player, key);
        if (java.util.Objects.equals(previous, key)) {
            return false;
        }
        changed();
        return true;
    }

    /** Takes a player out of every team. */
    public boolean remove(String player) {
        return set(player, null);
    }

    /** Takes everyone out of every team (nametags turned off). */
    public void clear() {
        if (!this.members.isEmpty()) {
            this.members.clear();
            changed();
        }
    }

    /** The team a player is in, or null. */
    public Key keyOf(String player) {
        return this.members.get(player);
    }

    /** Goes up on every change; boards compare it with the version they show. */
    public long version() {
        return this.version;
    }

    /** Forces every board to compare its teams again (after a change of the prefixes' text). */
    public void touch() {
        changed();
    }

    private void changed() {
        this.version++;
        this.snapshot = null;
    }

    /** The team name of a rank. Stable for the life of the model, at most 16 characters (old clients' limit). */
    public String name(Key key) {
        return this.names.computeIfAbsent(key, k -> PREFIX + String.format(java.util.Locale.ROOT, "%02d", Math.min(99, k.order()))
            + Integer.toString(this.names.size(), 36));
    }

    /** Every team with at least one member, by team name (sorted). Cached until the next change. */
    public Map<String, Team> teams() {
        if (this.snapshot == null) {
            Map<Key, Set<String>> byKey = new HashMap<>();
            for (Map.Entry<String, Key> entry : this.members.entrySet()) {
                byKey.computeIfAbsent(entry.getValue(), k -> new TreeSet<>()).add(entry.getKey());
            }
            Map<String, Team> teams = new TreeMap<>();
            for (Map.Entry<Key, Set<String>> entry : byKey.entrySet()) {
                teams.put(name(entry.getKey()), new Team(entry.getKey(), Collections.unmodifiableSet(entry.getValue())));
            }
            this.snapshot = Collections.unmodifiableMap(teams);
        }
        return this.snapshot;
    }

    /** How many players are in a team. */
    public int size() {
        return this.members.size();
    }
}
