package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FreezeRulesTest {

    private static final Set<String> ALLOWED = Set.of("msg", "r", "reply", "tell");

    @Test
    void turningTheHeadIsNotMoving() {
        assertFalse(FreezeRules.changesPosition(10.5, 64.0, -3.25, 10.5, 64.0, -3.25));
    }

    @Test
    void anyPositionChangeIsMoving() {
        assertTrue(FreezeRules.changesPosition(10.5, 64.0, -3.25, 10.6, 64.0, -3.25));
        assertTrue(FreezeRules.changesPosition(10.5, 64.0, -3.25, 10.5, 64.0001, -3.25));
        assertTrue(FreezeRules.changesPosition(10.5, 64.0, -3.25, 10.5, 64.0, -3.2500001));
        // There is no tolerance to creep through, even a tiny one.
        assertTrue(FreezeRules.changesPosition(0.0, 0.0, 0.0, Math.ulp(0.0), 0.0, 0.0));
    }

    @Test
    void onlyAllowedCommandsRun() {
        assertTrue(FreezeRules.commandAllowed("/msg Mod I'm here", ALLOWED));
        assertTrue(FreezeRules.commandAllowed("/R hello", ALLOWED));
        assertTrue(FreezeRules.commandAllowed("/minecraft:tell Mod hi", ALLOWED));
        assertFalse(FreezeRules.commandAllowed("/spawn", ALLOWED));
        assertFalse(FreezeRules.commandAllowed("/home base", ALLOWED));
        assertFalse(FreezeRules.commandAllowed("/msgx hi", ALLOWED));
        assertFalse(FreezeRules.commandAllowed("/", ALLOWED));
        assertFalse(FreezeRules.commandAllowed("", ALLOWED));
    }

    @Test
    void labelsAreReadLikeTheServerDoes() {
        assertEquals("tell", CommandLabels.label("/minecraft:tell Bob hi"));
        assertEquals("msg", CommandLabels.label("msg Bob"));
        assertEquals("me", CommandLabels.label("//ME waves"));
        assertEquals("spawn", CommandLabels.label("  /spawn  "));
        assertEquals("", CommandLabels.label(null));
    }
}
