package net.siftvanilla.siftcore.feature.combat;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each killer last got a counted kill on each victim, for the repeated-pair rule. Loaded from the kill log at
 * startup and updated with every counted kill; entries older than the longest allowed cooldown are pruned, so it
 * holds at most the pairs of the last day. Directional: A killing B and B killing A are different pairs.
 * Thread-safe.
 */
final class RecentPairs {

    private record Pair(UUID killer, UUID victim) {
    }

    private final Map<Pair, Long> counted = new ConcurrentHashMap<>();

    /** Records a counted kill; keeps the newest time when called with an older one. */
    void record(UUID killer, UUID victim, long at) {
        this.counted.merge(new Pair(killer, victim), at, Math::max);
    }

    /** The time of the last counted kill of this pair, if remembered. */
    OptionalLong last(UUID killer, UUID victim) {
        Long at = this.counted.get(new Pair(killer, victim));
        return at == null ? OptionalLong.empty() : OptionalLong.of(at);
    }

    /** Forgets kills older than {@code oldest} (epoch millis). */
    void prune(long oldest) {
        this.counted.values().removeIf(at -> at < oldest);
    }

    int size() {
        return this.counted.size();
    }
}
