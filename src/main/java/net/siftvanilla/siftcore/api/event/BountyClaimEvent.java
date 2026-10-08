package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a killer is paid the bounty on their victim (after a counted kill). Cancelling it leaves the bounty
 * on the victim's head. Fired on the victim's thread while their death is processed.
 */
public final class BountyClaimEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID killer;
    private final UUID victim;
    private final long total;
    private final long tax;
    private final int contributions;

    public BountyClaimEvent(UUID killer, UUID victim, long total, long tax, int contributions) {
        this.killer = killer;
        this.victim = victim;
        this.total = total;
        this.tax = tax;
        this.contributions = contributions;
    }

    public UUID killer() {
        return this.killer;
    }

    public UUID victim() {
        return this.victim;
    }

    /** The claimed bounty before tax. */
    public long total() {
        return this.total;
    }

    /** The part that is destroyed as tax. */
    public long tax() {
        return this.tax;
    }

    /** What the killer receives. */
    public long payout() {
        return this.total - this.tax;
    }

    /** How many contributions are claimed. */
    public int contributions() {
        return this.contributions;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
