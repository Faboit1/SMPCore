package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decisions behind join and leave alerts, as pure functions and small thread-safe holders, so the unit tests
 * can drive them with a fake clock. {@link Presence} does the Bukkit side (threads, timers, sending).
 */
public final class PresenceRules {

    private PresenceRules() {
    }

    /**
     * Whether a join is told to friends at all.
     *
     * @param announce     the joiner's "tell friends when I join" setting
     * @param vanished     whether the joiner is vanished when the join is evaluated (after the join delay)
     * @param lastLeave    when the joiner last left (0 when unknown)
     * @param now          the time of the evaluation
     * @param relogGrace   a join this soon after leaving is a relog and is not told
     * @param enabledAt    when the plugin started
     * @param startupQuiet no join is told this soon after the start
     */
    public static boolean announceJoin(boolean announce, boolean vanished, long lastLeave, long now, long relogGrace,
                                       long enabledAt, long startupQuiet) {
        if (!announce || vanished) {
            return false;
        }
        if (lastLeave > 0 && now - lastLeave < relogGrace) {
            return false;
        }
        return now - enabledAt >= startupQuiet;
    }

    /**
     * Whether a viewer hears about a friend's join.
     *
     * @param mode      the viewer's join alerts setting
     * @param favourite whether the viewer marked the joiner as a favourite
     * @param ignores   whether the viewer ignores the joiner
     */
    public static boolean viewerWants(FriendPrefs.JoinAlerts mode, boolean favourite, boolean ignores) {
        if (ignores) {
            return false;
        }
        return switch (mode) {
            case ALL -> true;
            case FAVOURITES -> favourite;
            case OFF -> false;
        };
    }

    /**
     * Whether a leave is told, decided when the leave delay is over.
     *
     * @param announce the leaver's "tell friends" setting when they left
     * @param vanished whether they were vanished when they left
     * @param back     whether they are online again
     */
    public static boolean announceLeave(boolean announce, boolean vanished, boolean back) {
        return announce && !vanished && !back;
    }

    /**
     * When players last left, for the relog grace. Thread-safe.
     */
    public static final class LastLeave {

        private final Map<UUID, Long> times = new ConcurrentHashMap<>();

        public void record(UUID player, long now) {
            this.times.put(player, now);
        }

        /** When the player last left, 0 when unknown. */
        public long get(UUID player) {
            return this.times.getOrDefault(player, 0L);
        }

        /** Forgets entries older than {@code before}. */
        public void prune(long before) {
            this.times.values().removeIf(time -> time < before);
        }

        public int size() {
            return this.times.size();
        }

        public void clear() {
            this.times.clear();
        }
    }

    /**
     * Join alerts collected per viewer: the first join opens a window (the caller schedules one flush on the viewer's
     * thread), later joins inside the window ride along, and the flush takes everything at once. Thread-safe.
     */
    public static final class JoinBatches {

        /** What a flush tells one viewer. */
        public record Batch(List<UUID> joiners, Set<UUID> favourites) {

            /** Whether the batch holds one of the viewer's favourites (it then plays the notify sound). */
            public boolean hasFavourite() {
                return !this.favourites.isEmpty();
            }
        }

        private final Map<UUID, Set<UUID>> joiners = new HashMap<>();
        private final Map<UUID, Set<UUID>> favourites = new HashMap<>();

        /** Adds a join for the viewer; returns true when it opened a new window (schedule a flush). */
        public synchronized boolean add(UUID viewer, UUID joiner, boolean favourite) {
            boolean opened = !this.joiners.containsKey(viewer);
            this.joiners.computeIfAbsent(viewer, k -> new LinkedHashSet<>()).add(joiner);
            if (favourite) {
                this.favourites.computeIfAbsent(viewer, k -> new LinkedHashSet<>()).add(joiner);
            }
            return opened;
        }

        /** Takes the viewer's window (empty when there is none). */
        public synchronized Batch take(UUID viewer) {
            Set<UUID> list = this.joiners.remove(viewer);
            Set<UUID> favs = this.favourites.remove(viewer);
            return new Batch(list == null ? List.of() : new ArrayList<>(list), favs == null ? Set.of() : Set.copyOf(favs));
        }

        public synchronized void forget(UUID viewer) {
            this.joiners.remove(viewer);
            this.favourites.remove(viewer);
        }

        public synchronized void clear() {
            this.joiners.clear();
            this.favourites.clear();
        }

        public synchronized int size() {
            return this.joiners.size();
        }
    }
}
