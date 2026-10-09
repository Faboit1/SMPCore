package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void onlyFriendsComingOverSkipTheRequest() {
        assertTrue(TpaGate.friendSkips(Kind.TO_TARGET, true, true, false));
        assertFalse(TpaGate.friendSkips(Kind.TO_SENDER, true, true, false), "a /tpahere always asks");
        assertFalse(TpaGate.friendSkips(Kind.TO_TARGET, false, true, false), "strangers always ask");
        assertFalse(TpaGate.friendSkips(Kind.TO_TARGET, true, false, false), "the target must allow it");
    }

    @Test
    void aFriendNeverArrivesInTheMiddleOfAFightWithoutAsking() {
        assertFalse(TpaGate.friendSkips(Kind.TO_TARGET, true, true, true), "a target in combat is asked");
    }

    @Test
    void nobodyAcceptsWhileEitherPlayerIsInCombat() {
        assertEquals(TpaGate.Fight.NONE, TpaGate.acceptBlocked(false, false));
        assertEquals(TpaGate.Fight.YOU, TpaGate.acceptBlocked(true, false), "a tagged target can't pull a teammate into the fight");
        assertEquals(TpaGate.Fight.YOU, TpaGate.acceptBlocked(true, true));
        assertEquals(TpaGate.Fight.OTHER, TpaGate.acceptBlocked(false, true), "nobody is pulled into the sender's fight");
    }
}
