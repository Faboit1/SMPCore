package net.siftvanilla.siftcore.feature.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last hit each player took from another player, used for kill credit: whoever hit the victim last within the
 * combat window gets the kill, even when the final damage came from a fall, lava or the void. Thread-safe; entries
 * are dropped on death and quit and pruned once they are older than the window, so it only holds players who were
 * hit recently.
 *
 * @param <W> what is remembered about the weapon (an item stack in the plugin, anything in tests)
 */
final class HitLog<W> {

    /** One hit: who dealt it, when, and with what (may be null). */
    record Hit<W>(UUID attacker, long at, W weapon) {
    }

    private final Map<UUID, Hit<W>> last = new ConcurrentHashMap<>();

    /** Remembers a hit; an older hit never replaces a newer one (hits can arrive from different threads). */
    void record(UUID victim, UUID attacker, long at, W weapon) {
        Hit<W> hit = new Hit<>(attacker, at, weapon);
        this.last.merge(victim, hit, (old, fresh) -> fresh.at() >= old.at() ? fresh : old);
    }

    /** The last hit on {@code victim} if it is younger than {@code windowMillis}, otherwise null. */
    Hit<W> within(UUID victim, long now, long windowMillis) {
        Hit<W> hit = this.last.get(victim);
        if (hit == null || now - hit.at() >= windowMillis) {
            return null;
        }
        return hit;
    }

    /** The attacker of the last hit within the window, or null. */
    UUID attackerWithin(UUID victim, long now, long windowMillis) {
        Hit<W> hit = within(victim, now, windowMillis);
        return hit == null ? null : hit.attacker();
    }

    void forget(UUID victim) {
        this.last.remove(victim);
    }

    /** Drops every hit older than {@code oldest} (epoch millis). */
    void prune(long oldest) {
        this.last.values().removeIf(hit -> hit.at() < oldest);
    }

    int size() {
        return this.last.size();
    }
}
