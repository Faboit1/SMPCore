package net.siftvanilla.siftcore.feature.friends;

import java.util.Locale;

/**
 * Everything a write unit read about a pair of players before deciding, from the acting player's side ("self") and
 * the other player's side ("other"). Pure data for {@link Decisions}.
 *
 * @param friends              whether they are friends
 * @param outgoing             the request self sent to other, or null
 * @param incoming             the request other sent to self, or null
 * @param selfFriends          how many friends self has
 * @param selfLimit            self's friend limit
 * @param otherFriends         how many friends other has
 * @param otherLimit           other's friend limit
 * @param selfOutgoingVisible  open requests self has sent that self still sees (pending or hidden, not expired)
 * @param otherIncomingPending requests other can see right now (pending, not expired)
 * @param selfSentToday        requests self sent in the last 24 hours
 * @param otherPrivacy         who other takes requests from
 * @param known                whether self is a friend of one of other's friends, or in other's team
 */
public record PairState(boolean friends, Request outgoing, Request incoming, int selfFriends, int selfLimit,
                        int otherFriends, int otherLimit, int selfOutgoingVisible, int otherIncomingPending,
                        int selfSentToday, Privacy otherPrivacy, boolean known) {

    /** The state column of a request row. */
    public enum State {
        /** Both sides see it. */
        PENDING,
        /** Only the sender sees it (hidden, or denied). */
        SHADOW,
        /** Nobody sees it; kept only as deny memory. */
        CLOSED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static State parse(String value) {
            for (State state : values()) {
                if (state.id().equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("Unknown request state " + value);
        }
    }

    /**
     * One request row.
     *
     * @param state   pending, shadow or closed
     * @param created when it was sent (refreshed by a new request during deny memory)
     * @param decided when the target denied it, 0 if never
     */
    public record Request(State state, long created, long decided) {

        /** Whether the sender still sees it as open: pending or hidden, and not expired. */
        public boolean visibleToSender(long now, FriendRules rules) {
            return (this.state == State.PENDING || this.state == State.SHADOW) && this.created >= rules.expiredBefore(now);
        }

        /** Whether the target sees it: pending and not expired. */
        public boolean visibleToTarget(long now, FriendRules rules) {
            return this.state == State.PENDING && this.created >= rules.expiredBefore(now);
        }

        /** Whether it was denied recently enough that new requests from the same sender stay hidden. */
        public boolean denyRemembered(long now, FriendRules rules) {
            return this.decided > 0 && this.decided >= rules.denyRememberedSince(now);
        }
    }
}
