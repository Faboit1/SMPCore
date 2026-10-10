package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One sell booster: who it is from, how much it raises prices, how long it runs and how much of that is left. Stored
 * in {@code boosters}, one row each. Immutable; the queue replaces it as time passes.
 *
 * @param id        the row id
 * @param kind      {@code sell}
 * @param percent   how much it raises sell prices (1 to 50)
 * @param seconds   its whole length
 * @param remaining milliseconds it still runs; counts down only while it runs and the server is up
 * @param state     where it stands
 * @param owner     the buyer of a store booster, or the staff member who started it; null for the server (console,
 *                  community goals)
 * @param source    where it came from
 * @param ref       the store reference, or null
 * @param reason    why staff started it, or null
 * @param actor     who ran the command ({@code console} or a staff UUID)
 * @param created   when it was added, epoch millis
 * @param started   when it started running, epoch millis (0 until then)
 * @param ended     when it stopped running, epoch millis (0 until then)
 */
record Booster(long id, String kind, int percent, long seconds, long remaining, State state, UUID owner, Source source, String ref,
               String reason, String actor, long created, long started, long ended) {

    /** Where a booster stands. */
    enum State {
        /** Waiting for the boosters before it. */
        QUEUED,
        /** Running now. */
        ACTIVE,
        /** Ran its whole length. */
        ENDED,
        /** Ended or taken out of the queue early by staff. */
        STOPPED,
        /** Taken back by a store refund or chargeback. */
        REVOKED;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether the booster is over (it will never run again). */
        boolean over() {
            return this != QUEUED && this != ACTIVE;
        }

        static State byId(String id) {
            for (State state : values()) {
                if (state.id().equalsIgnoreCase(id)) {
                    return state;
                }
            }
            return ENDED;
        }
    }

    /** Who started it. */
    enum Source {
        /** Bought in the store ({@code /sift store booster}). */
        STORE,
        /** Started by staff or the console ({@code /sift booster start}). */
        STAFF;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Source byId(String id) {
            return "store".equalsIgnoreCase(id) ? STORE : STAFF;
        }
    }

    Booster {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(state);
        Objects.requireNonNull(source);
        if (percent < 1 || seconds < 1) {
            throw new IllegalArgumentException("A booster raises prices by at least 1% for at least a second");
        }
        remaining = Math.clamp(remaining, 0, Math.multiplyExact(seconds, 1000L));
    }

    /** A new booster waiting to run. */
    static Booster queued(long id, String kind, int percent, Duration duration, UUID owner, Source source, String ref, String reason,
                          String actor, long now) {
        long seconds = Math.max(1, duration.toSeconds());
        return new Booster(id, kind, percent, seconds, seconds * 1000L, State.QUEUED, owner, source, ref, reason, actor, now, 0, 0);
    }

    /** Its whole length. */
    Duration duration() {
        return Duration.ofSeconds(this.seconds);
    }

    /** Time it still runs. */
    Duration left() {
        return Duration.ofMillis(this.remaining);
    }

    /** How much of it is left, from 1 (untouched) to 0 (over). */
    float progress() {
        return (float) Math.clamp((double) this.remaining / (this.seconds * 1000.0), 0.0, 1.0);
    }

    Booster started(long now) {
        return new Booster(this.id, this.kind, this.percent, this.seconds, this.remaining, State.ACTIVE, this.owner, this.source, this.ref,
            this.reason, this.actor, this.created, now, 0);
    }

    Booster withRemaining(long millis) {
        return new Booster(this.id, this.kind, this.percent, this.seconds, millis, this.state, this.owner, this.source, this.ref,
            this.reason, this.actor, this.created, this.started, this.ended);
    }

    /** The booster once it is over, in {@code state}. */
    Booster over(State state, long now) {
        if (!state.over()) {
            throw new IllegalArgumentException(state + " is not an end state");
        }
        return new Booster(this.id, this.kind, this.percent, this.seconds, state == State.ENDED ? 0 : this.remaining, state, this.owner,
            this.source, this.ref, this.reason, this.actor, this.created, this.started, now);
    }

    /** The booster waiting in line again, as if it never started (its remaining time is kept). */
    Booster requeued() {
        return new Booster(this.id, this.kind, this.percent, this.seconds, this.remaining, State.QUEUED, this.owner, this.source, this.ref,
            this.reason, this.actor, this.created, 0, 0);
    }

    /** The booster running again (taking back a revoke that could not be stored). */
    Booster resumed() {
        return new Booster(this.id, this.kind, this.percent, this.seconds, this.remaining, State.ACTIVE, this.owner, this.source, this.ref,
            this.reason, this.actor, this.created, this.started, 0);
    }
}
