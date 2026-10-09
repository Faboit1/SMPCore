package net.siftvanilla.siftcore.core.link;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.economy.LedgerTx;

/**
 * Server-wide sell boosters. Implemented by the boosters feature; read by selling (every sale price includes the
 * running booster, and the shop prices itself against the largest booster allowed) and used by store delivery, which
 * starts boosters inside its own ledger transaction so a reference can never start two.
 * <p>
 * Boosters queue one after another and never stack: {@link #percent()} is the one booster running now. Every read is
 * a volatile snapshot, safe and cheap from any thread.
 */
public interface ServerBoosters {

    /** The kind of booster that raises what the server pays for items. */
    String SELL = "sell";
    /** The highest percent any booster may have, and the highest {@code sell.max-percent} the config accepts. */
    int PERCENT_CAP = 50;
    /** The shortest store booster. */
    Duration MIN_LENGTH = Duration.ofMinutes(1);
    /** The longest store booster. */
    Duration MAX_LENGTH = Duration.ofDays(30);

    /** No boosters feature: prices are never raised and store boosters are refused. */
    ServerBoosters NONE = new ServerBoosters() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int percent() {
            return 0;
        }

        @Override
        public int maxPercent() {
            return 0;
        }

        @Override
        public int latestMaxPercent() {
            return 0;
        }

        @Override
        public int paid(int percent) {
            return 0;
        }

        @Override
        public String problem(String kind, int percent, Duration duration) {
            return "unavailable";
        }

        @Override
        public void deliver(LedgerTx.Builder tx, Grant grant) {
            throw new IllegalStateException("There is no boosters feature");
        }

        @Override
        public AtomicReference<Revoked> revoke(LedgerTx.Builder tx, String ref) {
            return new AtomicReference<>(Revoked.OVER);
        }

        @Override
        public Optional<Status> status(String ref) {
            return Optional.empty();
        }
    };

    /**
     * A booster bought in the store.
     *
     * @param kind     {@link #SELL}
     * @param percent  how much it raises prices
     * @param duration how long it runs (time counts only while the server runs)
     * @param owner    the buyer, or null for a booster from the server itself (a community goal)
     * @param ref      the store reference it was delivered under
     * @param actor    who ran the delivery ({@code console} or a staff UUID)
     */
    record Grant(String kind, int percent, Duration duration, UUID owner, String ref, String actor) {
    }

    /** What taking back a store booster did. */
    enum Revoked {
        /** It was running and was ended now; the next one in line started. */
        ENDED,
        /** It was waiting in line and was taken out. */
        REMOVED,
        /** It had already run out (or was stopped by staff), so nothing changed. */
        OVER
    }

    /**
     * Where a store booster stands right now.
     *
     * @param running  true while it runs, false while it waits
     * @param percent  the percent it pays ({@link #paid(int)} of what was bought)
     * @param left     time it still runs (for a waiting one: its whole length)
     * @param position for a waiting one, its place in line (1 is next); 0 while running
     */
    record Status(boolean running, int percent, Duration left, int position) {
    }

    /** Whether boosters exist on this server. */
    boolean available();

    /**
     * How much the running sell booster raises sell prices, in percent: 0 when none runs. Never more than
     * {@link #maxPercent()}, so the shop's arbitrage check (made against that maximum) always holds.
     */
    int percent();

    /** The largest percent a sell booster may apply ({@code sell.max-percent}). */
    int maxPercent();

    /**
     * {@link #maxPercent()} as most recently parsed: during {@code /sift reload} the value about to apply, so the shop
     * is validated against it together with the new sell prices.
     */
    int latestMaxPercent();

    /**
     * What a booster bought for {@code percent} pays right now: never more than {@link #maxPercent()}. A store booster
     * above the current limit is delivered anyway and pays the limit while it is lower.
     */
    int paid(int percent);

    /**
     * Null when a store booster like this may be delivered, otherwise why not: {@code unavailable}, {@code unknown_kind},
     * {@code bad_percent} (outside 1 to {@link #PERCENT_CAP}) or {@code bad_duration} (outside {@link #MIN_LENGTH} to
     * {@link #MAX_LENGTH}). Only these hard limits apply to purchases; {@code sell.max-percent} caps what is paid instead.
     */
    String problem(String kind, int percent, Duration duration);

    /**
     * Adds a booster to a store delivery's transaction: it starts at once when no booster runs, otherwise it waits its
     * turn. The booster row is written in the same transaction. Call {@link #problem} first.
     */
    void deliver(LedgerTx.Builder tx, Grant grant);

    /**
     * Adds taking back the booster delivered under {@code ref} to a revoke transaction. The returned holder says what
     * happened once the transaction applied.
     */
    AtomicReference<Revoked> revoke(LedgerTx.Builder tx, String ref);

    /** The running or waiting booster delivered under {@code ref}; empty once it is over (or for any other reference). */
    Optional<Status> status(String ref);
}
