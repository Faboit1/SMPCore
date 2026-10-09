package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Channel;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Routing;
import org.junit.jupiter.api.Test;

/** Where combat's refusals go for players who chose chat as their feedback channel. */
class CombatRefusalsTest {

    @Test
    void walkingIntoSpawnIsRefusedAboveTheHotbarWhateverTheFeedbackChannel() {
        // Every pushed-back step repeats it: in chat it would pile up several lines a second.
        assertTrue(CombatMessages.BLOCKED_SPAWN.status());
        assertEquals(Feedback.ERROR, CombatMessages.BLOCKED_SPAWN.feedback(), "still red with the error note");
        for (AlertStyle preference : AlertStyle.values()) {
            assertEquals(EnumSet.of(Routing.Place.ACTIONBAR), Routing.feedback(CombatMessages.BLOCKED_SPAWN, Channel.ACTIONBAR,
                preference), preference.name());
        }
    }

    @Test
    void refusalsOfSingleAttemptsFollowTheFeedbackChannel() {
        for (var key : java.util.List.of(CombatMessages.BLOCKED_COMMAND, CombatMessages.BLOCKED_ENDER_PEARL, CombatMessages.BLOCKED_ELYTRA)) {
            assertFalse(key.status(), key.path());
            assertEquals(EnumSet.of(Routing.Place.CHAT), Routing.feedback(key, Channel.ACTIONBAR, AlertStyle.CHAT), key.path());
        }
    }
}
