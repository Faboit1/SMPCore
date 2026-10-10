package net.siftvanilla.siftcore.feature.integrations;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;

/**
 * One store purchase delivered (or, for a rank, being delivered) under a reference, and what became of it. Stored in
 * {@code store_deliveries}, one row per reference.
 *
 * @param amount      money, shards or keys; 0 for a rank
 * @param duration    a rank's length in seconds, 0 for permanent ranks and everything else
 * @param until       a rank's end in epoch seconds, 0 for permanent ranks and everything else
 * @param actor       who ran the delivery ({@code console} or a staff UUID)
 * @param time        when it was recorded, epoch milliseconds
 * @param revokeUntil for a rank being revoked: -1 leaves timed grants alone, 0 removes them, more cuts them to that
 *                    epoch second
 * @param note        why it was revoked and what was taken back, or null
 */
record Delivery(String ref, StoreDeliveryEvent.Kind kind, UUID player, String item, long amount, long duration,
                State state, String actor, long time, long until, long revokeUntil, String note) {

    /**
     * A rank is recorded as {@link #PENDING} before LuckPerms is changed, then marked {@link #DONE}. A revoked rank goes
     * through {@link #REVOKING} the same way; everything else is revoked in one step.
     */
    enum State {
        DONE,
        PENDING,
        REVOKING,
        REVOKED;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static State byId(String id) {
            for (State state : values()) {
                if (state.id().equalsIgnoreCase(id)) {
                    return state;
                }
            }
            return DONE;
        }
    }

    Delivery {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(player);
        Objects.requireNonNull(item);
        Objects.requireNonNull(state);
    }

    /** A new delivery in the given state. */
    static Delivery of(String ref, StoreDeliveryEvent.Kind kind, UUID player, String item, long amount, long duration, State state,
                       String actor, long time, long until) {
        return new Delivery(ref, kind, player, item, amount, duration, state, actor, time, until, -1, null);
    }

    Delivery done() {
        return with(State.DONE, this.revokeUntil, this.note);
    }

    Delivery revoking(long plan, String note) {
        return with(State.REVOKING, plan, note);
    }

    Delivery revoked(String note) {
        return with(State.REVOKED, this.revokeUntil, note);
    }

    private Delivery with(State state, long revokeUntil, String note) {
        return new Delivery(this.ref, this.kind, this.player, this.item, this.amount, this.duration, state, this.actor, this.time,
            this.until, revokeUntil, note);
    }

    boolean pending() {
        return this.state == State.PENDING;
    }

    /** Whether the delivery was (or is being) taken back. */
    boolean revoked() {
        return this.state == State.REVOKED || this.state == State.REVOKING;
    }

    /** Whether it still waits for LuckPerms (a rank being delivered or revoked). */
    boolean unfinished() {
        return this.state == State.PENDING || this.state == State.REVOKING;
    }

    /** Whether a rank delivery is permanent. */
    boolean permanent() {
        return this.kind == StoreDeliveryEvent.Kind.RANK && this.until == 0;
    }
}
