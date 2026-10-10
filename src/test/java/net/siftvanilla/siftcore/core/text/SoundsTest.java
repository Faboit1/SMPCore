package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.siftvanilla.siftcore.core.player.options.PingSound;
import net.siftvanilla.siftcore.core.text.Sounds.Prefs;
import org.junit.jupiter.api.Test;

class SoundsTest {

    private static final float EPSILON = 0.0001f;

    @Test
    void everythingPlaysAtFullVolumeByDefault() {
        for (Feedback kind : new Feedback[] {Feedback.SUCCESS, Feedback.ERROR, Feedback.CLICK, Feedback.NOTIFY}) {
            assertEquals(1f, Sounds.factor(kind, Prefs.DEFAULTS, false), EPSILON, kind.name());
        }
        assertEquals(0f, Sounds.factor(Feedback.NONE, Prefs.DEFAULTS, false), EPSILON);
    }

    @Test
    void theVolumeScalesEverySoundAndZeroMutes() {
        Prefs sixty = new Prefs(60, true, true, true, true, false);
        assertEquals(0.6f, Sounds.factor(Feedback.CLICK, sixty, false), EPSILON);
        assertEquals(0.6f, Sounds.pingFactor(PingSound.CHIME, sixty, false), EPSILON);
        Prefs muted = new Prefs(0, true, true, true, true, false);
        for (Feedback kind : Feedback.values()) {
            assertEquals(0f, Sounds.factor(kind, muted, false), EPSILON, kind.name());
        }
        assertEquals(0f, Sounds.pingFactor(PingSound.BELL, muted, false), EPSILON);
        assertEquals(1f, Sounds.factor(Feedback.CLICK, new Prefs(250, true, true, true, true, false), false), EPSILON, "capped");
    }

    @Test
    void eachKindHasItsOwnSwitch() {
        assertEquals(0f, Sounds.factor(Feedback.NOTIFY, new Prefs(100, false, true, true, true, false), false), EPSILON);
        assertEquals(0f, Sounds.factor(Feedback.CLICK, new Prefs(100, true, false, true, true, false), false), EPSILON);
        assertEquals(0f, Sounds.factor(Feedback.SUCCESS, new Prefs(100, true, true, false, true, false), false), EPSILON);
        assertEquals(0f, Sounds.factor(Feedback.ERROR, new Prefs(100, true, true, true, false, false), false), EPSILON);
        assertEquals(1f, Sounds.factor(Feedback.ERROR, new Prefs(100, false, false, false, true, false), false), EPSILON,
            "switches don't affect each other");
    }

    @Test
    void quietInCombatSilencesPingsAndChimesButNotErrorsOrClicks() {
        Prefs quiet = new Prefs(100, true, true, true, true, true);
        assertEquals(0f, Sounds.factor(Feedback.NOTIFY, quiet, true), EPSILON);
        assertEquals(0f, Sounds.factor(Feedback.SUCCESS, quiet, true), EPSILON);
        assertEquals(1f, Sounds.factor(Feedback.ERROR, quiet, true), EPSILON);
        assertEquals(1f, Sounds.factor(Feedback.CLICK, quiet, true), EPSILON);
        assertEquals(1f, Sounds.factor(Feedback.NOTIFY, quiet, false), EPSILON, "only while tagged");
        assertEquals(1f, Sounds.factor(Feedback.NOTIFY, Prefs.DEFAULTS, true), EPSILON, "only with the setting on");
        assertEquals(0f, Sounds.pingFactor(PingSound.PLING, quiet, true), EPSILON);
        assertEquals(0f, Sounds.pingFactor(PingSound.DEFAULT, quiet, true), EPSILON);
    }

    @Test
    void pingsFollowTheChosenSound() {
        Prefs noPings = new Prefs(100, false, true, true, true, false);
        assertEquals(0f, Sounds.pingFactor(PingSound.DEFAULT, noPings, false), EPSILON, "default follows notification pings");
        assertEquals(1f, Sounds.pingFactor(PingSound.BELL, noPings, false), EPSILON, "a named sound is an explicit choice");
        assertEquals(0f, Sounds.pingFactor(PingSound.OFF, Prefs.DEFAULTS, false), EPSILON);
        assertEquals(1f, Sounds.pingFactor(PingSound.DEFAULT, Prefs.DEFAULTS, false), EPSILON);
    }
}
