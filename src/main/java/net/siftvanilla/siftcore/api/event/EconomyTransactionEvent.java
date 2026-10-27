package net.siftvanilla.siftcore.api.event;

import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Posting;
import org.bukkit.event.HandlerList;

/**
 * Fired before every economy transaction (money and shards): payments, sales, purchases, taxes, refunds, rewards
 * and admin changes. Cancelling it aborts the transaction with nothing changed. Higher-level events (for example
 * {@link AuctionPurchaseEvent}) fire before this one with more context.
 */
public final class EconomyTransactionEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID transaction;
    private final String kind;
    private final String actor;
    private final List<Posting> postings;

    public EconomyTransactionEvent(UUID transaction, String kind, String actor, List<Posting> postings) {
        this.transaction = transaction;
        this.kind = kind;
        this.actor = actor;
        this.postings = List.copyOf(postings);
    }

    public UUID transaction() {
        return this.transaction;
    }

    /** The main reason, e.g. {@code pay}, {@code sell}, {@code ah_sale}. */
    public String kind() {
        return this.kind;
    }

    /** A player UUID, {@code console}, {@code system} or a plugin name. */
    public String actor() {
        return this.actor;
    }

    public List<Posting> postings() {
        return this.postings;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
