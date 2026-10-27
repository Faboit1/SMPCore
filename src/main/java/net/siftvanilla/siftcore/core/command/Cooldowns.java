package net.siftvanilla.siftcore.core.command;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cooldowns keyed by a short name. Bounded: entries are dropped when a player quits and expired entries
 * are swept periodically.
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> until = new ConcurrentHashMap<>();

    /** Remaining cooldown, or {@link Duration#ZERO} if ready. Does not start the cooldown. */
    public Duration remaining(UUID player, String key) {
        Map<String, Long> map = this.until.get(player);
        if (map == null) {
            return Duration.ZERO;
        }
        Long end = map.get(key);
        long left = end == null ? 0 : end - System.currentTimeMillis();
        return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
    }

    /** Starts (or restarts) a cooldown. */
    public void start(UUID player, String key, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        this.until.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(key, System.currentTimeMillis() + duration.toMillis());
    }

    /** Atomically checks and starts a cooldown; returns the remaining time if not ready. */
    public Duration tryUse(UUID player, String key, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return Duration.ZERO;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> map = this.until.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long[] left = {0};
        map.compute(key, (k, end) -> {
            if (end != null && end > now) {
                left[0] = end - now;
                return end;
            }
            return now + duration.toMillis();
        });
        return left[0] > 0 ? Duration.ofMillis(left[0]) : Duration.ZERO;
    }

    public void clear(UUID player, String key) {
        Map<String, Long> map = this.until.get(player);
        if (map != null) {
            map.remove(key);
        }
    }

    public void forget(UUID player) {
        this.until.remove(player);
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        this.until.values().forEach(map -> map.values().removeIf(end -> end <= now));
        this.until.values().removeIf(Map::isEmpty);
    }
}
