package net.siftvanilla.siftcore.feature.spawners;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The second click before a player adds their spawners to someone else's stack (Confirm stacking onto others'
 * spawners). The first click arms a short window and asks; a separate click on the same spawner within the window
 * gives. Holding the use key repeats clicks every few ticks: those keep the window armed without confirming, so only
 * releasing and clicking again gives. Bukkit-free; one entry per player at most, forgotten when they quit.
 */
final class GiveConfirms {

    /** How long the second click may take. */
    static final Duration WINDOW = Duration.ofSeconds(5);
    /**
     * Clicks closer together than this come from holding the use key (the client repeats them every 4 ticks, 200 ms):
     * they don't count as a second click.
     */
    static final Duration HELD = Duration.ofMillis(350);

    /** What a click does. */
    enum Step {
        /** The first click: ask for a second one. */
        ASK,
        /** A repeat from holding the use key: nothing happens. */
        WAIT,
        /** The second click: give. */
        GIVE
    }

    private record Armed(long spawner, long armedAt, long lastClick) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Armed> armed = new ConcurrentHashMap<>();

    GiveConfirms(LongSupplier clock) {
        this.clock = clock;
    }

    /** A click of {@code player} that would add spawners to {@code spawner} (someone else's). */
    Step click(UUID player, long spawner) {
        long now = this.clock.getAsLong();
        Armed current = this.armed.get(player);
        if (current == null || current.spawner() != spawner || now - current.armedAt() > WINDOW.toMillis()) {
            this.armed.put(player, new Armed(spawner, now, now));
            return Step.ASK;
        }
        if (now - current.lastClick() < HELD.toMillis()) {
            this.armed.put(player, new Armed(spawner, current.armedAt(), now));
            return Step.WAIT;
        }
        this.armed.remove(player);
        return Step.GIVE;
    }

    /** Forgets a player who left. */
    void forget(UUID player) {
        this.armed.remove(player);
    }

    /** Players with an armed window (for tests). */
    int size() {
        return this.armed.size();
    }
}
