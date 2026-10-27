package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Fired before an auction listing is bought. Cancelling stops the purchase. */
public final class AuctionPurchaseEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long listing;
    private final UUID buyer;
    private final UUID seller;
    private final ItemStack item;
    private final long price;

    public AuctionPurchaseEvent(long listing, UUID buyer, UUID seller, ItemStack item, long price) {
        this.listing = listing;
        this.buyer = buyer;
        this.seller = seller;
        this.item = item.clone();
        this.price = price;
    }

    public long listing() {
        return this.listing;
    }

    public UUID buyer() {
        return this.buyer;
    }

    public UUID seller() {
        return this.seller;
    }

    /** A copy of the item. */
    public ItemStack item() {
        return this.item.clone();
    }

    public long price() {
        return this.price;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
