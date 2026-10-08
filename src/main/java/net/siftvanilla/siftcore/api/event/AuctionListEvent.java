package net.siftvanilla.siftcore.api.event;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired before a player lists an item on the auction house, before the item leaves their inventory. Cancelling it
 * stops the listing and nothing changes.
 */
public final class AuctionListEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID seller;
    private final ItemStack item;
    private final long price;
    private final Duration duration;

    public AuctionListEvent(UUID seller, ItemStack item, long price, Duration duration) {
        this.seller = seller;
        this.item = item.clone();
        this.price = price;
        this.duration = duration;
    }

    public UUID seller() {
        return this.seller;
    }

    /** A copy of the item stack being listed (with the listed amount). */
    public ItemStack item() {
        return this.item.clone();
    }

    /** The price of the whole listing. */
    public long price() {
        return this.price;
    }

    /** How long the listing will stay up. */
    public Duration duration() {
        return this.duration;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
