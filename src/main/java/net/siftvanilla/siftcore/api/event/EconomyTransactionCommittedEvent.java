package net.siftvanilla.siftcore.api.event;

import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Posting;
import org.bukkit.event.HandlerList;

/** Fired (asynchronously) after an economy transaction was durably stored. Not cancellable. */
public final class EconomyTransactionCommittedEvent extends SiftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID transaction;
    private final String kind;
    private final String actor;
    private final List<Posting> postings;
    private final long[] balancesAfter;

    public EconomyTransactionCommittedEvent(UUID transaction, String kind, String actor, List<Posting> postings, long[] balancesAfter) {
        this.transaction = transaction;
        this.kind = kind;
        this.actor = actor;
        this.postings = List.copyOf(postings);
        this.balancesAfter = balancesAfter.clone();
    }

    public UUID transaction() {
        return this.transaction;
    }

    public String kind() {
        return this.kind;
    }

    public String actor() {
        return this.actor;
    }

    public List<Posting> postings() {
        return this.postings;
    }

    /** The balance of each posting's account right after that posting. */
    public long balanceAfter(int postingIndex) {
        return this.balancesAfter[postingIndex];
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
