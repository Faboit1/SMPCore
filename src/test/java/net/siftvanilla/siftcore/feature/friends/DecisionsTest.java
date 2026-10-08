package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The decision table of requests, accepts, denies, cancels, favourites and staff adds. */
class DecisionsTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long NOW = 1_000 * DAY;
    private static final FriendRules RULES = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 50, 500, 10);

    private static final PairState.Request PENDING = new PairState.Request(PairState.State.PENDING, NOW - DAY, 0);
    private static final PairState.Request HIDDEN = new PairState.Request(PairState.State.SHADOW, NOW - DAY, 0);
    private static final PairState.Request DENIED = new PairState.Request(PairState.State.SHADOW, NOW - DAY, NOW - DAY);
    private static final PairState.Request CLOSED = new PairState.Request(PairState.State.CLOSED, NOW - 3 * DAY, NOW - 2 * DAY);
    private static final PairState.Request EXPIRED = new PairState.Request(PairState.State.PENDING, NOW - 8 * DAY, 0);

    /** A pair with everything at zero and open privacy; tweak with the withers below. */
    private record S(boolean friends, PairState.Request out, PairState.Request in, int selfFriends, int otherFriends,
                     int outgoing, int incoming, int today, Privacy privacy, boolean known) {

        static S plain() {
            return new S(false, null, null, 0, 0, 0, 0, 0, Privacy.EVERYONE, false);
        }

        S befriended() {
            return new S(true, this.out, this.in, this.selfFriends, this.otherFriends, this.outgoing, this.incoming, this.today, this.privacy, this.known);
        }

        S out(PairState.Request request) {
            return new S(this.friends, request, this.in, this.selfFriends, this.otherFriends, this.outgoing, this.incoming, this.today, this.privacy, this.known);
        }

        S in(PairState.Request request) {
            return new S(this.friends, this.out, request, this.selfFriends, this.otherFriends, this.outgoing, this.incoming, this.today, this.privacy, this.known);
        }

        S counts(int selfFriends, int otherFriends) {
            return new S(this.friends, this.out, this.in, selfFriends, otherFriends, this.outgoing, this.incoming, this.today, this.privacy, this.known);
        }

        S caps(int outgoing, int incoming, int today) {
            return new S(this.friends, this.out, this.in, this.selfFriends, this.otherFriends, outgoing, incoming, today, this.privacy, this.known);
        }

        S privacy(Privacy privacy, boolean known) {
            return new S(this.friends, this.out, this.in, this.selfFriends, this.otherFriends, this.outgoing, this.incoming, this.today, privacy, known);
        }

        PairState state() {
            return new PairState(this.friends, this.out, this.in, this.selfFriends, 50, this.otherFriends, 50, this.outgoing,
                this.incoming, this.today, this.privacy, this.known);
        }
    }

    private static Outcome request(S s) {
        return Decisions.request(s.state(), RULES, NOW, false, false).outcome();
    }

    @Test
    void plainRequestIsSent() {
        Decisions.RequestDecision decision = Decisions.request(S.plain().state(), RULES, NOW, false, false);
        assertEquals(Outcome.SENT, decision.outcome());
        assertEquals(PairState.State.PENDING, decision.state());
        assertEquals(0, decision.decided());
    }

    @Test
    void checksRunInOrder() {
        assertEquals(Outcome.ALREADY_FRIENDS, request(S.plain().befriended().counts(50, 50)));
        assertEquals(Outcome.MUTUAL_NEEDED, request(S.plain().in(PENDING).caps(20, 50, 30)),
            "a request back wins over every cap");
        assertEquals(Outcome.ALREADY_SENT, request(S.plain().out(PENDING).caps(20, 0, 30)));
        assertEquals(Outcome.ALREADY_SENT, request(S.plain().out(HIDDEN)), "hidden requests look the same to the sender");
        assertEquals(Outcome.OUTGOING_FULL, request(S.plain().caps(20, 0, 30)));
        assertEquals(Outcome.DAILY_CAP, request(S.plain().caps(19, 0, 30).counts(50, 50)));
        assertEquals(Outcome.SENDER_FULL, request(S.plain().counts(50, 50).privacy(Privacy.NOBODY, false)));
        assertEquals(Outcome.TARGET_FULL, request(S.plain().counts(0, 50).privacy(Privacy.NOBODY, false)));
        assertEquals(Outcome.PRIVATE, request(S.plain().privacy(Privacy.NOBODY, true)));
    }

    @Test
    void mutualNeedsTheEventFirst() {
        PairState state = S.plain().in(PENDING).state();
        assertEquals(Outcome.MUTUAL_NEEDED, Decisions.request(state, RULES, NOW, false, false).outcome());
        assertEquals(Outcome.BECAME_FRIENDS, Decisions.request(state, RULES, NOW, false, true).outcome());
        assertEquals(Outcome.BECAME_FRIENDS, Decisions.request(S.plain().in(HIDDEN).state(), RULES, NOW, false, true).outcome(),
            "their request is hidden from us but they still see it waiting");
        assertEquals(Outcome.SENDER_FULL, Decisions.request(S.plain().in(PENDING).counts(50, 0).state(), RULES, NOW, false, true).outcome());
        assertEquals(Outcome.TARGET_FULL, Decisions.request(S.plain().in(PENDING).counts(0, 50).state(), RULES, NOW, false, true).outcome());
        assertEquals(Outcome.SENT, Decisions.request(S.plain().in(EXPIRED).state(), RULES, NOW, false, true).outcome(),
            "a run-out request back is not mutual");
        assertEquals(Outcome.SHADOWED, Decisions.request(S.plain().in(PENDING).state(), RULES, NOW, true, true).outcome(),
            "they asked and then ignored us: no friendship, a hidden request");
    }

    @Test
    void hiddenRequests() {
        Decisions.RequestDecision ignored = Decisions.request(S.plain().state(), RULES, NOW, true, false);
        assertEquals(Outcome.SHADOWED, ignored.outcome());
        assertEquals(PairState.State.SHADOW, ignored.state());
        assertEquals(Outcome.SHADOWED, request(S.plain().caps(0, 50, 0)), "the target has the most visible requests");
        assertEquals(Outcome.SENT, request(S.plain().caps(0, 49, 0)));
        Decisions.RequestDecision remembered = Decisions.request(S.plain().out(CLOSED).state(), RULES, NOW, false, false);
        assertEquals(Outcome.SHADOWED, remembered.outcome(), "denied two days ago");
        assertEquals(CLOSED.decided(), remembered.decided(), "the deny time is kept, so the memory does not grow");
        PairState.Request oldDeny = new PairState.Request(PairState.State.CLOSED, NOW - 20 * DAY, NOW - 8 * DAY);
        assertEquals(Outcome.SENT, request(S.plain().out(oldDeny)), "after the deny memory");
        assertEquals(Outcome.SENT, request(S.plain().out(EXPIRED)), "a run-out request is sent again");
    }

    @Test
    void privacy() {
        assertEquals(Outcome.SENT, request(S.plain().privacy(Privacy.EVERYONE, false)));
        assertEquals(Outcome.PRIVATE, request(S.plain().privacy(Privacy.KNOWN, false)));
        assertEquals(Outcome.SENT, request(S.plain().privacy(Privacy.KNOWN, true)));
        assertEquals(Outcome.PRIVATE, request(S.plain().privacy(Privacy.NOBODY, true)));
        assertEquals(Outcome.MUTUAL_NEEDED, request(S.plain().in(PENDING).privacy(Privacy.NOBODY, false)),
            "someone who asked you can always be answered");
    }

    @Test
    void accept() {
        assertEquals(Outcome.BECAME_FRIENDS, Decisions.accept(S.plain().in(PENDING).state(), RULES, NOW, false));
        assertEquals(Outcome.GONE, Decisions.accept(S.plain().state(), RULES, NOW, false));
        assertEquals(Outcome.GONE, Decisions.accept(S.plain().in(HIDDEN).state(), RULES, NOW, false), "hidden from the accepter");
        assertEquals(Outcome.GONE, Decisions.accept(S.plain().in(DENIED).state(), RULES, NOW, false));
        assertEquals(Outcome.GONE, Decisions.accept(S.plain().in(EXPIRED).state(), RULES, NOW, false));
        assertEquals(Outcome.GONE, Decisions.accept(S.plain().in(PENDING).state(), RULES, NOW, true), "an ignore in between");
        assertEquals(Outcome.SENDER_FULL, Decisions.accept(S.plain().in(PENDING).counts(50, 0).state(), RULES, NOW, false));
        assertEquals(Outcome.TARGET_FULL, Decisions.accept(S.plain().in(PENDING).counts(0, 50).state(), RULES, NOW, false));
        assertEquals(Outcome.ALREADY_FRIENDS, Decisions.accept(S.plain().befriended().in(PENDING).state(), RULES, NOW, false));
    }

    @Test
    void denyAndCancel() {
        assertEquals(Outcome.DONE, Decisions.deny(PENDING, RULES, NOW));
        assertEquals(Outcome.GONE, Decisions.deny(DENIED, RULES, NOW));
        assertEquals(Outcome.GONE, Decisions.deny(EXPIRED, RULES, NOW));
        assertEquals(Outcome.GONE, Decisions.deny(null, RULES, NOW));
        assertEquals(Decisions.CancelAction.DELETE, Decisions.cancel(PENDING, RULES, NOW));
        assertEquals(Decisions.CancelAction.DELETE, Decisions.cancel(HIDDEN, RULES, NOW), "hidden but never denied");
        assertEquals(Decisions.CancelAction.CLOSE, Decisions.cancel(DENIED, RULES, NOW), "kept as deny memory");
        assertEquals(Decisions.CancelAction.GONE, Decisions.cancel(CLOSED, RULES, NOW));
        assertEquals(Decisions.CancelAction.GONE, Decisions.cancel(EXPIRED, RULES, NOW));
        assertEquals(Decisions.CancelAction.GONE, Decisions.cancel(null, RULES, NOW));
    }

    @Test
    void favouritesAndStaff() {
        assertEquals(Outcome.NOT_FRIENDS, Decisions.favourite(false, false, true, 0, 10));
        assertEquals(Outcome.DONE, Decisions.favourite(true, false, true, 9, 10));
        assertEquals(Outcome.FAVOURITES_FULL, Decisions.favourite(true, false, true, 10, 10));
        assertEquals(Outcome.DONE, Decisions.favourite(true, true, false, 10, 10), "unmarking is always allowed");
        assertEquals(Outcome.DONE, Decisions.favourite(true, true, true, 10, 10), "already a favourite");
        assertEquals(Outcome.BECAME_FRIENDS, Decisions.staffAdd(false, 499, 499, 500));
        assertEquals(Outcome.SENDER_FULL, Decisions.staffAdd(false, 500, 0, 500));
        assertEquals(Outcome.TARGET_FULL, Decisions.staffAdd(false, 0, 500, 500));
        assertEquals(Outcome.ALREADY_FRIENDS, Decisions.staffAdd(true, 0, 0, 500));
    }

    @Test
    void requestVisibility() {
        assertTrue(PENDING.visibleToSender(NOW, RULES) && PENDING.visibleToTarget(NOW, RULES));
        assertTrue(HIDDEN.visibleToSender(NOW, RULES));
        assertFalse(HIDDEN.visibleToTarget(NOW, RULES));
        assertFalse(CLOSED.visibleToSender(NOW, RULES) || CLOSED.visibleToTarget(NOW, RULES));
        assertFalse(EXPIRED.visibleToSender(NOW, RULES) || EXPIRED.visibleToTarget(NOW, RULES));
        assertTrue(DENIED.denyRemembered(NOW, RULES));
        assertFalse(PENDING.denyRemembered(NOW, RULES));
    }

    @Test
    void limits() {
        assertEquals(50, FriendRules.effectiveLimit(0, 50, 500), "no rank node: the default");
        assertEquals(75, FriendRules.effectiveLimit(75, 50, 500));
        assertEquals(50, FriendRules.effectiveLimit(10, 50, 500), "a rank never lowers the default");
        assertEquals(500, FriendRules.effectiveLimit(900, 50, 500), "the hard cap");
        assertEquals(500, FriendRules.effectiveLimit(FriendRules.UNLIMITED, 50, 500), "unlimited is the hard cap");
        assertEquals(1, FriendRules.effectiveLimit(0, 0, 0), "never below one");
    }

    @Test
    void statesParse() {
        for (PairState.State state : PairState.State.values()) {
            assertEquals(state, PairState.State.parse(state.id()));
        }
        assertTrue(Outcome.SENT.ok() && Outcome.SHADOWED.ok() && Outcome.BECAME_FRIENDS.ok() && Outcome.DONE.ok());
        assertFalse(Outcome.PRIVATE.ok() || Outcome.GONE.ok() || Outcome.MUTUAL_NEEDED.ok());
    }
}
