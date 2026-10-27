package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.feature.tpa.TpaGate.Verdict;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import org.junit.jupiter.api.Test;

class TpaGateTest {

    @Test
    void anOrdinaryRequestIsAllowed() {
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, false, Kind.TO_TARGET, false, true));
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, false, Kind.TO_SENDER, false, true));
    }

    @Test
    void yourselfComesFirstThenVisibility() {
        assertEquals(Verdict.SELF, TpaGate.check(true, false, true, Kind.TO_TARGET, true, false));
        assertEquals(Verdict.NOT_ONLINE, TpaGate.check(false, false, true, Kind.TO_TARGET, false, true));
        assertEquals(Verdict.NOT_ONLINE, TpaGate.check(false, false, false, Kind.TO_SENDER, true, false),
            "a hidden (vanished) target looks offline, even when it also ignores the sender");
    }

    @Test
    void staffGoStraightThereWithTpaAndPassEverySwitchWithTpahere() {
        assertEquals(Verdict.STAFF_INSTANT, TpaGate.check(false, true, true, Kind.TO_TARGET, true, false));
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, true, Kind.TO_SENDER, true, false),
            "staff /tpahere reaches players who ignore them or turned requests off");
    }

    @Test
    void ignoredSendersAndClosedTargetsAreRefused() {
        assertEquals(Verdict.IGNORED, TpaGate.check(false, true, false, Kind.TO_TARGET, true, true));
        assertEquals(Verdict.IGNORED, TpaGate.check(false, true, false, Kind.TO_TARGET, true, false),
            "ignoring is reported like a refusal before the switch, so the sender learns nothing more");
        assertEquals(Verdict.REQUESTS_OFF, TpaGate.check(false, true, false, Kind.TO_SENDER, false, false));
    }

    /** The two steps of TpaService#skipsRequest: the target's "Auto-accept /tpa from", then the request's kind and combat. */
    private static boolean skips(Kind kind, AutoAccept mode, boolean friends, boolean targetInCombat) {
        return TpaGate.skips(kind, TpaGate.autoAccepts(mode, friends, false, false, false), targetInCombat);
    }

    @Test
    void onlyFriendsComingOverSkipTheRequest() {
        assertTrue(skips(Kind.TO_TARGET, AutoAccept.ALL, true, false));
        assertFalse(skips(Kind.TO_SENDER, AutoAccept.ALL, true, false), "a /tpahere always asks");
        assertFalse(skips(Kind.TO_TARGET, AutoAccept.ALL, false, false), "strangers always ask");
        assertFalse(skips(Kind.TO_TARGET, AutoAccept.NOBODY, true, false), "the target must allow it");
    }

    @Test
    void aFriendNeverArrivesInTheMiddleOfAFightWithoutAsking() {
        assertFalse(skips(Kind.TO_TARGET, AutoAccept.ALL, true, true), "a target in combat is asked");
    }

    @Test
    void nobodyAcceptsWhileEitherPlayerIsInCombat() {
        assertEquals(TpaGate.Fight.NONE, TpaGate.acceptBlocked(false, false));
        assertEquals(TpaGate.Fight.YOU, TpaGate.acceptBlocked(true, false), "a tagged target can't pull a teammate into the fight");
        assertEquals(TpaGate.Fight.YOU, TpaGate.acceptBlocked(true, true));
        assertEquals(TpaGate.Fight.OTHER, TpaGate.acceptBlocked(false, true), "nobody is pulled into the sender's fight");
    }

    @Test
    void pullRequestsFollowTheirOwnSettingOnTopOfTheFirst() {
        assertEquals(Verdict.PULLS_OFF, TpaGate.check(false, true, false, Kind.TO_SENDER, false, true, false));
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, false, Kind.TO_TARGET, false, true, false),
            "a plain /tpa never reads Pull requests from");
        assertEquals(Verdict.REQUESTS_OFF, TpaGate.check(false, true, false, Kind.TO_SENDER, false, false, false),
            "Teleport requests from is told first: the stricter of the two wins");
        assertEquals(Verdict.IGNORED, TpaGate.check(false, true, false, Kind.TO_SENDER, true, true, false));
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, true, Kind.TO_SENDER, false, false, false),
            "staff with the bypass reach players whatever they chose");
        assertEquals(Verdict.ALLOWED, TpaGate.check(false, true, false, Kind.TO_SENDER, false, true, true));
    }

    @Test
    void whoCanSendRequestsFollowsTheAudience() {
        assertTrue(TpaGate.accepts(Audience.EVERYONE, false, false));
        assertFalse(TpaGate.accepts(Audience.NOBODY, true, true), "nobody means nobody, friends included");
        assertTrue(TpaGate.accepts(Audience.FRIENDS, true, false));
        assertFalse(TpaGate.accepts(Audience.FRIENDS, false, true), "a teammate who isn't a friend is not a friend");
        assertTrue(TpaGate.accepts(Audience.FRIENDS_TEAM, false, true));
        assertTrue(TpaGate.accepts(Audience.FRIENDS_TEAM, true, false));
        assertFalse(TpaGate.accepts(Audience.FRIENDS_TEAM, false, false), "a stranger is refused");
    }

    @Test
    void autoAcceptFollowsTheTargetsChoice() {
        assertFalse(TpaGate.autoAccepts(AutoAccept.NOBODY, true, true, true, false));
        assertTrue(TpaGate.autoAccepts(AutoAccept.ALL, true, false, false, false));
        assertFalse(TpaGate.autoAccepts(AutoAccept.ALL, false, false, true, false), "all friends: teammates still ask");
        assertTrue(TpaGate.autoAccepts(AutoAccept.FAVOURITES, true, true, false, false));
        assertFalse(TpaGate.autoAccepts(AutoAccept.FAVOURITES, true, false, false, false), "a friend who isn't a favourite asks");
        assertTrue(TpaGate.autoAccepts(AutoAccept.FRIENDS_TEAM, false, false, true, false), "a teammate comes");
        assertTrue(TpaGate.autoAccepts(AutoAccept.FRIENDS_TEAM, true, false, false, false), "a friend comes");
        assertFalse(TpaGate.autoAccepts(AutoAccept.FRIENDS_TEAM, false, false, false, false), "a stranger asks");
        for (AutoAccept mode : AutoAccept.values()) {
            assertFalse(TpaGate.autoAccepts(mode, true, true, true, true), "an ignored player always asks (" + mode + ")");
        }
    }

    @Test
    void onlyAnAcceptedTpaToATargetOutOfCombatSkipsTheRequest() {
        assertTrue(TpaGate.skips(Kind.TO_TARGET, true, false));
        assertFalse(TpaGate.skips(Kind.TO_SENDER, true, false), "a /tpahere always asks");
        assertFalse(TpaGate.skips(Kind.TO_TARGET, false, false));
        assertFalse(TpaGate.skips(Kind.TO_TARGET, true, true), "a target in combat is asked");
    }

    @Test
    void thePopUpNeverInterrupts() {
        assertTrue(TpaGate.popsUp(true, false, false, false));
        assertFalse(TpaGate.popsUp(false, false, false, false), "only when the player chose it");
        assertFalse(TpaGate.popsUp(true, true, false, false), "never in combat");
        assertFalse(TpaGate.popsUp(true, false, true, false), "never while AFK");
        assertFalse(TpaGate.popsUp(true, false, false, true), "never over another window");
    }

    @Test
    void onlyBeingPulledAsksOnceMore() {
        assertTrue(TpaGate.confirmsPull(Kind.TO_SENDER, true));
        assertFalse(TpaGate.confirmsPull(Kind.TO_SENDER, false), "the player turned it off");
        assertFalse(TpaGate.confirmsPull(Kind.TO_TARGET, true), "accepting a /tpa moves the other player");
    }

    /**
     * Every way /tpaccept picks a request (the only one, a named one, a pick in the window of several) goes through
     * {@link TpaGate#answer}, so a /tpahere picked from the window asks once more too.
     */
    @Test
    void acceptingAPullAsksOnceMoreHoweverTheRequestWasPicked() {
        assertEquals(TpaGate.Answer.ASK_FIRST, TpaGate.answer(true, Kind.TO_SENDER, true, true));
        assertEquals(TpaGate.Answer.ACCEPT, TpaGate.answer(true, Kind.TO_SENDER, false, true), "the player turned the question off");
        assertEquals(TpaGate.Answer.ACCEPT, TpaGate.answer(true, Kind.TO_TARGET, true, true), "a /tpa moves the sender: no question");
        assertEquals(TpaGate.Answer.ACCEPT, TpaGate.answer(true, Kind.TO_SENDER, true, false),
            "a request answered or expired meanwhile: accepting just says it is gone, no window for nothing");
        for (Kind kind : Kind.values()) {
            assertEquals(TpaGate.Answer.DENY, TpaGate.answer(false, kind, true, true), "denying never asks (" + kind + ")");
        }
    }

    @Test
    void tpatoggleFriendsHearsTheServerFirst() {
        assertEquals(TpaGate.Typed.SERVER, TpaGate.typedChoice(true, false, false),
            "a hidden setting offers nothing: say it is the server's, not \"Use one of these: .\"");
        assertEquals(TpaGate.Typed.SERVER, TpaGate.typedChoice(true, true, true), "locked: whatever was typed");
        assertEquals(TpaGate.Typed.NOT_OFFERED, TpaGate.typedChoice(false, false, false), "no choice to list");
        assertEquals(TpaGate.Typed.NOT_OFFERED, TpaGate.typedChoice(false, true, false));
        assertEquals(TpaGate.Typed.UNKNOWN, TpaGate.typedChoice(false, false, true));
        assertEquals(TpaGate.Typed.CHANGE, TpaGate.typedChoice(false, true, true));
    }
}
