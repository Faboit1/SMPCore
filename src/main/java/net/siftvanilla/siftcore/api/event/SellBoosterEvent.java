package net.siftvanilla.siftcore.api.event;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired when a server-wide sell booster starts running and when it stops (it ran out, staff stopped it, or a store
 * refund took it back). Informational, for example to post it to Discord. Boosters run one at a time, so a
 * {@link Phase#STARTED} event follows the {@link Phase#ENDED} one whenever another booster was waiting. Fired on the
 * global region thread, at most a second after the change, and only once the change is stored for good (a change
 * whose transaction was taken back is never announced).
 */
public final class SellBoosterEvent extends SiftEvent {

    /** What happened. */
    public enum Phase {
        STARTED,
        ENDED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Phase phase;
    private final long id;
    private final int percent;
    private final Duration length;
    private final Duration left;
    private final UUID owner;
    private final String ref;
    private final boolean early;

    /**
     * @param id      the booster's id
     * @param percent how much it raises (raised) sell prices: what sales pay, so never more than {@code sell.max-percent}
     * @param length  its whole length
     * @param left    time it still runs (zero when it ran out)
     * @param owner   the buyer or the staff member who started it; null for the server itself
     * @param ref     the store reference, or null when staff started it
     * @param early   for {@link Phase#ENDED}: true when it was stopped or taken back before running out
     */
    public SellBoosterEvent(Phase phase, long id, int percent, Duration length, Duration left, UUID owner, String ref, boolean early) {
        this.phase = phase;
        this.id = id;
        this.percent = percent;
        this.length = length;
        this.left = left;
        this.owner = owner;
        this.ref = ref;
        this.early = early;
    }

    public Phase phase() {
        return this.phase;
    }

    public long id() {
        return this.id;
    }

    public int percent() {
        return this.percent;
    }

    public Duration length() {
        return this.length;
    }

    public Duration left() {
        return this.left;
    }

    /** The buyer or the staff member who started it; null for a booster from the server itself. */
    public UUID owner() {
        return this.owner;
    }

    /** The store reference, or null when staff started it. */
    public String ref() {
        return this.ref;
    }

    /** True when it ended before running out (stopped by staff, or taken back by a refund). */
    public boolean early() {
        return this.early;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
