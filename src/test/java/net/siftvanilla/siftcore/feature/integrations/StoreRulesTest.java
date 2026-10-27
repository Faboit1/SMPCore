package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreRulesTest {

    @Test
    void referencesAreShortAndPlain() {
        assertNull(StoreRules.refProblem("tbx-2491-a8f3c"));
        assertNull(StoreRules.refProblem("order_77.1+gift"));
        assertEquals("empty", StoreRules.refProblem(""));
        assertEquals("empty", StoreRules.refProblem(null));
        assertEquals("too_long", StoreRules.refProblem("x".repeat(49)));
        assertNull(StoreRules.refProblem("x".repeat(48)));
        assertEquals("bad_characters", StoreRules.refProblem("a b"));
        assertEquals("bad_characters", StoreRules.refProblem("a;drop"));
        assertTrue(("store:" + "x".repeat(StoreRules.MAX_REF)).length() <= 64, "the crate grant reference fits its column");
    }

    @Test
    void groupNamesAreLuckPermsNames() {
        assertEquals("elite", StoreRules.group("Elite"));
        assertEquals("vip-plus", StoreRules.group("vip-plus"));
        assertNull(StoreRules.group("group.admin"));
        assertNull(StoreRules.group("a b"));
        assertNull(StoreRules.group(null));
    }

    @Test
    void accountIdsAreRecognised() {
        UUID id = UUID.randomUUID();
        assertEquals(id, StoreRules.uuid(id.toString()).orElseThrow());
        assertTrue(StoreRules.uuid("Steve").isEmpty());
        assertTrue(StoreRules.uuid("x".repeat(36)).isEmpty());
    }

    @Test
    void timedRanksAddUpAndPermanentRanksStay() {
        Instant now = Instant.parse("2026-10-08T12:00:00.750Z");
        Duration month = Duration.ofDays(30);
        assertEquals(Instant.parse("2026-11-07T12:00:00Z"), StoreRules.rankEnd(false, null, now, month),
            "from now, in whole seconds (LuckPerms stores seconds)");
        Instant held = Instant.parse("2026-10-20T00:00:00Z");
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), StoreRules.rankEnd(false, held, now, month), "added to the time left");
        Instant expired = Instant.parse("2026-10-01T00:00:00Z");
        assertEquals(Instant.parse("2026-11-07T12:00:00Z"), StoreRules.rankEnd(false, expired, now, month), "an ended grant counts from now");
        assertNull(StoreRules.rankEnd(true, null, now, month), "a permanent holder stays permanent");
        assertNull(StoreRules.rankEnd(false, held, now, null), "a permanent purchase is permanent");
    }
}
