package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;

/**
 * Spot checks of the pure rules for {@code /sift selftest}: a handful of decision table rows and the note cleaner.
 * Each returns null when it passes, or what is wrong. Pure.
 */
final class FriendsSelfTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final FriendRules RULES = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 50, 500, 10);

    private FriendsSelfTest() {
    }

    private static PairState state(boolean friends, PairState.Request out, PairState.Request in, int selfFriends, int otherFriends,
                                   Privacy privacy, boolean known) {
        return new PairState(friends, out, in, selfFriends, 50, otherFriends, 50, 0, 0, 0, privacy, known);
    }

    static String decisions() {
        long now = 100 * DAY;
        PairState.Request pending = new PairState.Request(PairState.State.PENDING, now - DAY, 0);
        PairState.Request denied = new PairState.Request(PairState.State.SHADOW, now - DAY, now - DAY);
        PairState.Request expired = new PairState.Request(PairState.State.PENDING, now - 8 * DAY, 0);
        if (Decisions.request(state(false, null, null, 0, 0, Privacy.EVERYONE, false), RULES, now, false, false).outcome() != Outcome.SENT) {
            return "a plain request is not sent";
        }
        if (Decisions.request(state(false, null, null, 0, 0, Privacy.EVERYONE, false), RULES, now, true, false).outcome() != Outcome.SHADOWED) {
            return "a request to someone who ignores the sender is not hidden";
        }
        if (Decisions.request(state(false, null, pending, 0, 0, Privacy.EVERYONE, false), RULES, now, false, false).outcome()
            != Outcome.MUTUAL_NEEDED) {
            return "a request back is not turned into an accept";
        }
        if (Decisions.request(state(false, denied, null, 0, 0, Privacy.EVERYONE, false), RULES, now, false, false).outcome()
            != Outcome.ALREADY_SENT) {
            return "a denied request does not look waiting to its sender";
        }
        PairState.Request closed = new PairState.Request(PairState.State.CLOSED, now - 2 * DAY, now - DAY);
        Decisions.RequestDecision again = Decisions.request(state(false, closed, null, 0, 0, Privacy.EVERYONE, false), RULES, now, false, false);
        if (again.outcome() != Outcome.SHADOWED || again.decided() != closed.decided()) {
            return "a request within the deny memory is not hidden";
        }
        if (Decisions.request(state(false, null, null, 0, 0, Privacy.KNOWN, false), RULES, now, false, false).outcome() != Outcome.PRIVATE
            || Decisions.request(state(false, null, null, 0, 0, Privacy.KNOWN, true), RULES, now, false, false).outcome() != Outcome.SENT
            || Decisions.request(state(false, null, null, 0, 0, Privacy.NOBODY, true), RULES, now, false, false).outcome() != Outcome.PRIVATE) {
            return "privacy is not applied";
        }
        if (Decisions.request(state(false, null, null, 50, 0, Privacy.EVERYONE, false), RULES, now, false, false).outcome() != Outcome.SENDER_FULL
            || Decisions.request(state(false, null, null, 0, 50, Privacy.EVERYONE, false), RULES, now, false, false).outcome() != Outcome.TARGET_FULL) {
            return "friend limits are not applied";
        }
        if (Decisions.accept(state(false, null, pending, 0, 0, Privacy.EVERYONE, false), RULES, now, false) != Outcome.BECAME_FRIENDS
            || Decisions.accept(state(false, null, expired, 0, 0, Privacy.EVERYONE, false), RULES, now, false) != Outcome.GONE
            || Decisions.accept(state(false, null, denied, 0, 0, Privacy.EVERYONE, false), RULES, now, false) != Outcome.GONE) {
            return "accepting does not check the request";
        }
        if (Decisions.cancel(denied, RULES, now) != Decisions.CancelAction.CLOSE
            || Decisions.cancel(pending, RULES, now) != Decisions.CancelAction.DELETE) {
            return "cancelling a denied request does not keep the deny memory";
        }
        return null;
    }

    static String notes() {
        if (!NoteText.clean("  hi\u0000 §cthere​ ").equals("hi cthere")) {
            return "control and format characters are kept";
        }
        String longText = "x".repeat(200);
        if (NoteText.clean(longText).length() != NoteText.MAX_LENGTH) {
            return "notes are not cut to " + NoteText.MAX_LENGTH + " characters";
        }
        return null;
    }
}
