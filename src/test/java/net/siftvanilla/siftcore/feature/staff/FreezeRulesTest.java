package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

class FreezeRulesTest {

    private static final Set<String> ALLOWED = Set.of("msg", "r", "reply", "tell");

    @Test
    void teleportsAFrozenPlayerNeverMakes() {
        for (PlayerTeleportEvent.TeleportCause cause : List.of(PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL, PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.SPECTATE)) {
            assertTrue(FreezeRules.teleportRefused(cause, true, 0), cause + " never moves a frozen player");
        }
    }

    @Test
    void pluginTeleportsMayOnlyKeepThePlayerWhereTheyStand() {
        assertTrue(FreezeRules.teleportRefused(PlayerTeleportEvent.TeleportCause.PLUGIN, true, 500 * 500), "spawn, home, RTP");
        assertTrue(FreezeRules.teleportRefused(PlayerTeleportEvent.TeleportCause.PLUGIN, false, 0), "another world");
        assertFalse(FreezeRules.teleportRefused(PlayerTeleportEvent.TeleportCause.PLUGIN, true, 0),
            "the server applying the freeze's own move reset (Canvas does it with a plugin teleport in place)");
        assertFalse(FreezeRules.teleportRefused(PlayerTeleportEvent.TeleportCause.COMMAND, true, 500 * 500), "staff /tp still works");
        assertFalse(FreezeRules.teleportRefused(PlayerTeleportEvent.TeleportCause.COMMAND, false, 0), "staff /tp to another world");
    }

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
