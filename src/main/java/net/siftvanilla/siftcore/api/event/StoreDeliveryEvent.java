package net.siftvanilla.siftcore.api.event;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a store purchase is delivered ({@code /sift store ...}, usually run by the store's console command),
 * after the delivery was validated and found not delivered before. Cancelling stops it with nothing changed and
 * nothing recorded, so the store may deliver the same reference again later. Fired on the thread that ran the
 * command (the global region thread for the console).
 */
public final class StoreDeliveryEvent extends SiftCancellableEvent {

    /** What is delivered. */
    public enum Kind {
        MONEY,
        SHARDS,
        KEYS,
        RANK,
        /** A server-wide sell booster for everyone online. */
        BOOSTER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final Kind kind;
    private final String item;
    private final long amount;
    private final Duration duration;
    private final String ref;
    private final String actor;

    public StoreDeliveryEvent(UUID player, Kind kind, String item, long amount, Duration duration, String ref, String actor) {
        this.player = player;
        this.kind = kind;
        this.item = item;
        this.amount = amount;
        this.duration = duration;
        this.ref = ref;
        this.actor = actor;
    }

    /** Who receives the purchase; for a booster from the server itself (console), the nil UUID. */
    public UUID player() {
        return this.player;
    }

    public Kind kind() {
        return this.kind;
    }

    /** The currency id ({@code money}, {@code shards}), the crate id, the LuckPerms group or the booster kind ({@code sell}). */
    public String item() {
        return this.item;
    }

    /** Money, shards or keys; a booster's percent; 0 for ranks. */
    public long amount() {
        return this.amount;
    }

    /** How long a rank or a booster lasts; null for permanent ranks and for money, shards and keys. */
    public Duration duration() {
        return this.duration;
    }

    /** The store's reference (order or transaction id); a reference is delivered at most once. */
    public String ref() {
        return this.ref;
    }

    /** Who ran the delivery: {@code console} or a staff member's UUID. */
    public String actor() {
        return this.actor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
