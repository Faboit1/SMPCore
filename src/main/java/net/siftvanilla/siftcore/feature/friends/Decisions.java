package net.siftvanilla.siftcore.feature.friends;

/**
 * The decision table of every friends action, as pure functions over what a write unit read ({@link PairState}).
 * The units call these inside the database writer, so the checks and the changes they lead to are atomic; the unit
 * tests and the self-test call them directly.
 */
public final class Decisions {

    private Decisions() {
    }

    /**
     * What a request leads to.
     *
     * @param outcome  the result
     * @param state    the state to store when the outcome is {@link Outcome#SENT} or {@link Outcome#SHADOWED}
     * @param decided  the deny time to keep on the row (deny memory), 0 for none
     */
    public record RequestDecision(Outcome outcome, PairState.State state, long decided) {

        static RequestDecision of(Outcome outcome) {
            return new RequestDecision(outcome, null, 0);
        }
    }

    /**
     * A friend request from self to other. The sender-side memory checks (self, vanish, account age, rate) ran
     * before; these are the stored-state checks, in order: already friends; a request from other that other still
     * sees (it becomes a friendship, or {@link Outcome#MUTUAL_NEEDED} without {@code allowMutual}); an open request
     * from self; self's open requests; the daily cap; self full; other full; other's privacy. A request that passes
     * is hidden ({@link Outcome#SHADOWED}) when other ignores self, other already has the most visible requests, or
     * other denied self within the deny memory.
     *
     * @param targetIgnoresSender whether other ignores self
     * @param allowMutual         whether a request from other may turn into a friendship right away (its event ran)
     */
    public static RequestDecision request(PairState s, FriendRules rules, long now, boolean targetIgnoresSender,
                                          boolean allowMutual) {
        if (s.friends()) {
            return RequestDecision.of(Outcome.ALREADY_FRIENDS);
        }
        PairState.Request reverse = s.incoming();
        if (reverse != null && reverse.visibleToSender(now, rules) && !targetIgnoresSender) {
            if (!allowMutual) {
                return RequestDecision.of(Outcome.MUTUAL_NEEDED);
            }
            if (s.selfFriends() >= s.selfLimit()) {
                return RequestDecision.of(Outcome.SENDER_FULL);
            }
            if (s.otherFriends() >= s.otherLimit()) {
                return RequestDecision.of(Outcome.TARGET_FULL);
            }
            return RequestDecision.of(Outcome.BECAME_FRIENDS);
        }
        PairState.Request forward = s.outgoing();
        if (forward != null && forward.visibleToSender(now, rules)) {
            return RequestDecision.of(Outcome.ALREADY_SENT);
        }
        if (s.selfOutgoingVisible() >= rules.maxOutgoing()) {
            return RequestDecision.of(Outcome.OUTGOING_FULL);
        }
        if (s.selfSentToday() >= rules.perDay()) {
            return RequestDecision.of(Outcome.DAILY_CAP);
        }
        if (s.selfFriends() >= s.selfLimit()) {
            return RequestDecision.of(Outcome.SENDER_FULL);
        }
        if (s.otherFriends() >= s.otherLimit()) {
            return RequestDecision.of(Outcome.TARGET_FULL);
        }
        if (!s.otherPrivacy().allows(s.known())) {
            return RequestDecision.of(Outcome.PRIVATE);
        }
        boolean remembered = forward != null && forward.denyRemembered(now, rules);
        if (targetIgnoresSender || s.otherIncomingPending() >= rules.maxIncoming() || remembered) {
            return new RequestDecision(Outcome.SHADOWED, PairState.State.SHADOW, remembered ? forward.decided() : 0);
        }
        return new RequestDecision(Outcome.SENT, PairState.State.PENDING, 0);
    }

    /**
     * Self accepts other's request. The request must still be pending and not expired, and must not be hidden by an
     * ignore in either direction; both friend limits are checked now, not when the request was sent.
     *
     * @param ignored whether either player ignores the other
     */
    public static Outcome accept(PairState s, FriendRules rules, long now, boolean ignored) {
        if (s.friends()) {
            return Outcome.ALREADY_FRIENDS;
        }
        PairState.Request request = s.incoming();
        if (request == null || !request.visibleToTarget(now, rules) || ignored) {
            return Outcome.GONE;
        }
        if (s.selfFriends() >= s.selfLimit()) {
            return Outcome.SENDER_FULL;
        }
        if (s.otherFriends() >= s.otherLimit()) {
            return Outcome.TARGET_FULL;
        }
        return Outcome.BECAME_FRIENDS;
    }

    /** Self denies other's request: only an open, visible request can be denied. */
    public static Outcome deny(PairState.Request incoming, FriendRules rules, long now) {
        return incoming != null && incoming.visibleToTarget(now, rules) ? Outcome.DONE : Outcome.GONE;
    }

    /** What cancelling an own request does to its row. */
    public enum CancelAction {
        /** Nothing to cancel. */
        GONE,
        /** The row is removed. */
        DELETE,
        /** The row was denied: it is closed and kept as deny memory. */
        CLOSE
    }

    /** Self cancels the request it sent to other. */
    public static CancelAction cancel(PairState.Request outgoing, FriendRules rules, long now) {
        if (outgoing == null || !outgoing.visibleToSender(now, rules)) {
            return CancelAction.GONE;
        }
        return outgoing.decided() > 0 ? CancelAction.CLOSE : CancelAction.DELETE;
    }

    /**
     * Setting a friend's favourite flag.
     *
     * @param friends    whether they are friends
     * @param current    the flag now
     * @param desired    the flag asked for
     * @param favourites how many favourites self has now
     * @param cap        the most favourites allowed
     */
    public static Outcome favourite(boolean friends, boolean current, boolean desired, int favourites, int cap) {
        if (!friends) {
            return Outcome.NOT_FRIENDS;
        }
        if (current == desired) {
            return Outcome.DONE;
        }
        if (desired && favourites >= cap) {
            return Outcome.FAVOURITES_FULL;
        }
        return Outcome.DONE;
    }

    /**
     * Staff make two players friends: limits and privacy don't apply, the hard cap does.
     *
     * @param aFriends friends of the first player
     * @param bFriends friends of the second player
     */
    public static Outcome staffAdd(boolean friends, int aFriends, int bFriends, int hardCap) {
        if (friends) {
            return Outcome.ALREADY_FRIENDS;
        }
        if (aFriends >= hardCap) {
            return Outcome.SENDER_FULL;
        }
        if (bFriends >= hardCap) {
            return Outcome.TARGET_FULL;
        }
        return Outcome.BECAME_FRIENDS;
    }
}
