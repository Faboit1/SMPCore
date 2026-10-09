package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Who a player is talking to in private messages. After a message both players reply to each other with
 * {@code /r} (players who chose {@link ReplyTarget#LAST_RECEIVED} answer whoever last wrote to them instead); a
 * reply target expires after a while without messages. It also remembers who last wrote to whom,
 * because a player who wrote to you may get an answer even if they turned private messages off or are hidden.
 * <p>
 * The console takes part as {@link #CONSOLE}. Thread-safe; the clock is injected for tests.
 */
final class Conversations {

    /** The console's id in conversations. */
    static final UUID CONSOLE = new UUID(0L, 0L);

    private record Partner(UUID other, long at) {
    }

    private final LongSupplier clock;
    private final Supplier<Duration> expiry;
    /** Player to the one {@code /r} answers. */
    private final Map<UUID, Partner> replyTo = new ConcurrentHashMap<>();
    /** Player to who last wrote to them. */
    private final Map<UUID, Partner> lastFrom = new ConcurrentHashMap<>();

    Conversations(LongSupplier clock, Supplier<Duration> expiry) {
        this.clock = clock;
        this.expiry = expiry;
    }

    /** Records a delivered message from {@code from} to {@code to}. */
    void record(UUID from, UUID to) {
        long now = this.clock.getAsLong();
        this.replyTo.put(from, new Partner(to, now));
        this.replyTo.put(to, new Partner(from, now));
        this.lastFrom.put(to, new Partner(from, now));
    }

    /** Who {@code /r} answers, if the conversation hasn't expired. */
    Optional<UUID> replyTarget(UUID player) {
        Partner partner = this.replyTo.get(player);
        return partner != null && fresh(partner) ? Optional.of(partner.other()) : Optional.empty();
    }

    /**
     * Who last wrote to {@code player}, if within the reply window: the {@code /r} target of players who answer
     * whoever last messaged them ({@link ReplyTarget#LAST_RECEIVED}), even after they wrote to someone else.
     */
    Optional<UUID> lastReceived(UUID player) {
        Partner partner = this.lastFrom.get(player);
        return partner != null && fresh(partner) ? Optional.of(partner.other()) : Optional.empty();
    }

    /** Who {@code /r} answers for a player who chose {@code target}. */
    Optional<UUID> replyTarget(UUID player, ReplyTarget target) {
        return target == ReplyTarget.LAST_RECEIVED ? lastReceived(player) : replyTarget(player);
    }

    /** Whether {@code other} sent {@code player} a message recently (within the reply window). */
    boolean wroteTo(UUID other, UUID player) {
        Partner partner = this.lastFrom.get(player);
        return partner != null && partner.other().equals(other) && fresh(partner);
    }

    private boolean fresh(Partner partner) {
        return this.clock.getAsLong() - partner.at() < this.expiry.get().toMillis();
    }

    /** Drops expired entries. */
    void sweep() {
        this.replyTo.values().removeIf(partner -> !fresh(partner));
        this.lastFrom.values().removeIf(partner -> !fresh(partner));
    }

    int size() {
        return this.replyTo.size() + this.lastFrom.size();
    }
}
