package net.siftvanilla.siftcore.feature.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The once-a-second combat timer, without the server: given every tag's end time it says who sees "In combat 12s"
 * and whose tag just ended (they hear "You are no longer in combat." exactly once). A player who died or left is
 * forgotten so they get no end message.
 */
final class TagTicker {

    /** A player still in combat and the whole seconds left (rounded up, so the last second shows 1s). */
    record Show(UUID player, long secondsLeft) {
    }

    /** What one tick produced. */
    record Update(List<Show> shows, List<UUID> ended) {
    }

    private final Set<UUID> shown = ConcurrentHashMap.newKeySet();

    /** Whole seconds left until {@code until}, rounded up; 0 when over. */
    static long secondsLeft(long until, long now) {
        long millis = until - now;
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }

    /**
     * Advances the timer. {@code untils} maps every tagged player to the end of their tag; expired entries may be
     * included (they count as ended).
     */
    Update tick(Map<UUID, Long> untils, long now) {
        List<Show> shows = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : untils.entrySet()) {
            long left = secondsLeft(entry.getValue(), now);
            if (left > 0) {
                shows.add(new Show(entry.getKey(), left));
                this.shown.add(entry.getKey());
            }
        }
        List<UUID> ended = new ArrayList<>();
        for (UUID player : List.copyOf(this.shown)) {
            Long until = untils.get(player);
            if ((until == null || secondsLeft(until, now) == 0) && this.shown.remove(player)) {
                ended.add(player);
            }
        }
        return new Update(shows, ended);
    }

    /** Marks a player as shown right away (their tag started between two ticks). */
    void shown(UUID player) {
        this.shown.add(player);
    }

    /** Forgets a player without an end message (death, quit). */
    void forget(UUID player) {
        this.shown.remove(player);
    }

    int size() {
        return this.shown.size();
    }
}
