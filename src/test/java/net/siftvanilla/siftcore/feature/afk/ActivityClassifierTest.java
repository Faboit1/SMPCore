package net.siftvanilla.siftcore.feature.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.siftvanilla.siftcore.feature.afk.ActivityClassifier.Move;
import net.siftvanilla.siftcore.feature.afk.ActivityClassifier.Verdict;
import org.junit.jupiter.api.Test;

/**
 * The anti-bypass rules of AFK detection: what counts as a player at the keyboard and what doesn't (water and
 * vehicles, knockback, jump macros, pacing and circling, steady-rate and back-and-forth turning, auto-clickers and
 * repeated chat).
 */
class ActivityClassifierTest {

    private static final float NO_VEHICLE = Float.NaN;

    /** A scripted client: tracks its position and view like the server's from/to. */
    private static final class Client {
        private final ActivityClassifier classifier = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        private double x = 0.5;
        private double y = 64;
        private double z = 0.5;
        private float yaw;
        private float pitch;
        private long now = 1_000_000;
        private float vehicleYaw = NO_VEHICLE;
        private boolean liquid;
        private final List<Verdict> verdicts = new ArrayList<>();

        Verdict move(double dx, double dy, double dz, float dYaw, float dPitch) {
            this.now += 50;
            Move move = new Move(this.x, this.y, this.z, this.x + dx, this.y + dy, this.z + dz, this.yaw, this.pitch,
                this.yaw + dYaw, this.pitch + dPitch, this.vehicleYaw, this.liquid, false, false, this.now);
            this.x += dx;
            this.y += dy;
            this.z += dz;
            this.yaw += dYaw;
            this.pitch += dPitch;
            Verdict verdict = this.classifier.move(move);
            this.verdicts.add(verdict);
            return verdict;
        }

        Verdict turn(float dYaw, float dPitch) {
            return move(0, 0, 0, dYaw, dPitch);
        }

        long active() {
            return this.verdicts.stream().filter(v -> v == Verdict.ACTIVE).count();
        }

        void reset() {
            this.verdicts.clear();
        }
    }

    // ------------------------------------------------------------------ walking vs being pushed

    @Test
    void walkingToNewGroundCountsWaterPushDoesNot() {
        Client walker = new Client();
        for (int i = 0; i < 10; i++) {
            walker.move(0.25, 0, 0, 0, 0);
        }
        assertTrue(walker.active() >= 2, "walking 2.5 blocks over new ground counts: " + walker.verdicts);

        Client swimmer = new Client();
        swimmer.liquid = true;
        for (int i = 0; i < 200; i++) {
            assertEquals(Verdict.PUSHED, swimmer.move(0.25, 0, 0, 0, 0),
                "a water stream (or bubble column) carrying the player is not activity");
        }
        assertEquals(0, swimmer.active());
    }

    @Test
    void vehiclesCarryingThePlayerDoNotCount() {
        Client rider = new Client();
        rider.vehicleYaw = 0;
        for (int i = 0; i < 100; i++) {
            rider.move(0.4, 0, 0.1, 0, 0);
        }
        assertEquals(0, rider.active(), "a minecart or boat moving the player is not activity");
    }

    @Test
    void aBoatTurningItsPassengerIsNotLookingAround() {
        Client rider = new Client();
        rider.vehicleYaw = 0;
        rider.turn(0, 0);
        for (int i = 0; i < 90; i++) {
            // The boat turns 2 degrees a tick and takes the passenger's view with it.
            rider.vehicleYaw += 2;
            rider.turn(2, 0);
        }
        assertEquals(0, rider.active(), "the boat's turn is not the player's");
        rider.reset();
        // The passenger now really looks up while the boat keeps turning: that counts.
        for (int i = 0; i < 10; i++) {
            rider.vehicleYaw += 2;
            rider.turn(2, -3 - (i % 3) * 0.7f);
        }
        assertTrue(rider.active() > 0, "looking up from a turning boat counts: " + rider.verdicts);
    }

    @Test
    void movementRightAfterKnockbackDoesNotCount() {
        Client player = new Client();
        player.classifier.pushed(player.now);
        for (int i = 0; i < 10; i++) {
            assertNotEquals(Verdict.ACTIVE, player.move(0.6, 0.1, 0, 0, 0), "knockback is not activity");
        }
        player.now += 2_000;
        player.reset();
        for (int i = 0; i < 8; i++) {
            player.move(0.25, 0, 0, 0, 0);
        }
        assertTrue(player.active() > 0, "walking on afterwards counts again");
    }

    @Test
    void stepsTooLongToWalkAreNotWalking() {
        Client player = new Client();
        assertEquals(Verdict.PUSHED, player.move(3.0, 0, 0, 0, 0), "a 3 block step is a correction or a push, not a walk");
    }

    // ------------------------------------------------------------------ macros

    @Test
    void jumpMacroInPlaceNeverCounts() {
        Client jumper = new Client();
        for (int jump = 0; jump < 100; jump++) {
            for (double dy : new double[] {0.42, 0.33, 0.25, 0.17, -0.08, -0.23, -0.37, -0.49}) {
                assertNotEquals(Verdict.ACTIVE, jumper.move(0, dy, 0, 0, 0));
            }
        }
        // A tiny view wobble below the look threshold doesn't help either.
        for (int i = 0; i < 100; i++) {
            assertNotEquals(Verdict.ACTIVE, jumper.move(0, i % 2 == 0 ? 0.42 : -0.42, 0, i % 2 == 0 ? 1.2f : -1.2f, 0));
        }
    }

    @Test
    void walkingBackAndForthOnAFixedPathOnlyCountsTheFirstPass() {
        Client pacer = new Client();
        for (int step = 0; step < 20; step++) {
            pacer.move(0.25, 0, 0, 0, 0);
        }
        long firstPass = pacer.active();
        assertTrue(firstPass > 0, "the first walk over new ground counts");
        pacer.reset();
        for (int lap = 0; lap < 30; lap++) {
            double direction = lap % 2 == 0 ? -0.25 : 0.25;
            for (int step = 0; step < 20; step++) {
                pacer.move(direction, 0, 0, 0, 0);
            }
        }
        assertEquals(0, pacer.active(), "pacing over the same blocks is a macro: " + pacer.verdicts.stream().distinct().toList());
    }

    @Test
    void backAndForthWithSnapTurnsAtTheEndsDoesNotCount() {
        Client pacer = new Client();
        for (int lap = 0; lap < 40; lap++) {
            for (int step = 0; step < 16; step++) {
                pacer.move(lap % 2 == 0 ? 0.25 : -0.25, 0, 0, 0, 0);
            }
            // The macro turns around in one tick at each end.
            pacer.turn(180, 0);
            if (lap == 8) {
                pacer.reset();
            }
        }
        assertEquals(0, pacer.active(), "a pacing macro that snaps around at the ends: " + pacer.verdicts.stream().distinct().toList());
    }

    @Test
    void circleMacroTurningAtASteadyRateDoesNotCount() {
        Client circler = new Client();
        for (int tick = 0; tick < 2_000; tick++) {
            double angle = Math.toRadians(tick * 3.0);
            circler.move(Math.cos(angle) * 0.2, 0, Math.sin(angle) * 0.2, 3.0f, 0);
            if (tick == 200) {
                circler.reset();
            }
        }
        assertEquals(0, circler.active(), "walking in circles while turning at a steady rate: "
            + circler.verdicts.stream().distinct().toList());
    }

    @Test
    void cyclingThroughTheSameFewDirectionsDoesNotCount() {
        Client macro = new Client();
        float[] cycle = {90, 45, -120, 60, -75};
        for (int round = 0; round < 40; round++) {
            for (float turn : cycle) {
                macro.turn(turn, 0);
            }
            if (round == 6) {
                macro.reset();
            }
        }
        assertEquals(0, macro.active(), "a macro snapping through five fixed directions: " + macro.verdicts.stream().distinct().toList());
    }

    @Test
    void jitteringBackAndForthByAFixedAmountDoesNotCount() {
        Client macro = new Client();
        for (int i = 0; i < 400; i++) {
            // Every few seconds the script nudges the view 7 degrees one way, then back.
            macro.turn(i % 2 == 0 ? 7f : -7f, 0);
            macro.move(0, 0, 0, 0, 0);
            if (i == 20) {
                macro.reset();
            }
        }
        assertEquals(0, macro.active(), "a jitter macro: " + macro.verdicts.stream().distinct().toList());
    }

    @Test
    void aPlayerScanningSlowlyInOneAreaStillCounts() {
        Client human = new Client();
        Random random = new Random(7);
        // A slow, wandering look over a small area: the view drifts a few degrees a tick and changes direction now
        // and then, like someone watching their farm.
        float drift = 1.5f;
        for (int i = 0; i < 1_200; i++) {
            if (random.nextInt(40) == 0) {
                drift = -drift;
            }
            float dYaw = drift + (float) (random.nextGaussian() * 0.6);
            float dPitch = (float) (random.nextGaussian() * 0.4);
            human.turn(dYaw, Math.clamp(human.pitch + dPitch, -60f, 60f) - human.pitch);
        }
        assertTrue(human.active() > 40, "slow scanning counts: " + human.active() + " of " + human.verdicts.size());
    }

    @Test
    void walkingStraightForeverWithoutLookingAroundStopsCounting() {
        Client walker = new Client();
        for (int i = 0; i < 4_000; i++) {
            walker.move(0.25, 0, 0, 0, 0);
        }
        assertTrue(walker.active() <= ActivityClassifier.Settings.DEFAULTS.straightLimit(),
            "auto-walk or a flying machine: only the first new columns count, got " + walker.active());
        walker.reset();
        walker.turn(30, 10);
        walker.turn(-12, 4);
        assertTrue(walker.active() > 0, "looking around counts again");
        walker.reset();
        for (int i = 0; i < 20; i++) {
            walker.move(0.25, 0, 0, 0, 0);
        }
        assertTrue(walker.active() > 0, "and walking on counts after a look");
    }

    // ------------------------------------------------------------------ genuine looking around

    @Test
    void aPlayerLookingAroundCounts() {
        Client human = new Client();
        Random random = new Random(42);
        for (int i = 0; i < 600; i++) {
            float dYaw = (float) (random.nextGaussian() * 2.5);
            float dPitch = (float) (random.nextGaussian() * 1.2);
            human.turn(dYaw, Math.clamp(human.pitch + dPitch, -89f, 89f) - human.pitch);
        }
        assertTrue(human.active() > 20, "irregular mouse movement counts often: " + human.active());
    }

    @Test
    void smallTurnsAddUpToALook() {
        Client player = new Client();
        player.turn(0.5f, 0);
        assertEquals(Verdict.SMALL_TURN, player.turn(1.3f, 0));
        assertEquals(Verdict.SMALL_TURN, player.turn(2.1f, 0.2f));
        assertEquals(Verdict.ACTIVE, player.turn(1.7f, 0.1f), "5.6 degrees in all is a look");
    }

    // ------------------------------------------------------------------ chat, commands, clicks

    @Test
    void repeatingTheSameChatCommandOrClickDoesNotCount() {
        ActivityClassifier classifier = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        assertEquals(Verdict.ACTIVE, classifier.chat("hello"));
        assertEquals(Verdict.REPEATED, classifier.chat("Hello "), "same line, different case and spacing");
        assertEquals(Verdict.ACTIVE, classifier.chat("how is everyone"));
        assertEquals(Verdict.ACTIVE, classifier.command("bal"));
        assertEquals(Verdict.REPEATED, classifier.command("bal"), "a macro running /bal every few minutes");
        assertEquals(Verdict.ACTIVE, classifier.command("home base"));
        assertEquals(Verdict.ACTIVE, classifier.interaction("attack:10,64,10@100"));
        assertEquals(Verdict.REPEATED, classifier.interaction("attack:10,64,10@100"), "an auto-clicker on a mob grinder spot");
        assertEquals(Verdict.ACTIVE, classifier.interaction("use:RIGHT_CLICK_BLOCK:HAND:5,64,5@100"));
        assertEquals(Verdict.REPEATED, classifier.interaction("attack:10,64,10@100"), "alternating between two clicks repeats too");
        for (int i = 0; i < ActivityClassifier.RECENT_ACTIONS; i++) {
            classifier.interaction("use:block" + i);
        }
        assertEquals(Verdict.ACTIVE, classifier.interaction("attack:10,64,10@100"), "an old click counts again after a while");
    }

    @Test
    void scrollingThroughTheHotbarOrRotatingCommandsDoesNotCount() {
        ActivityClassifier scroller = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        List<Verdict> verdicts = new ArrayList<>();
        for (int lap = 0; lap < 5; lap++) {
            for (int slot = 0; slot < 9; slot++) {
                verdicts.add(scroller.interaction("held:" + slot + "@100"));
            }
        }
        // The first two laps are new; from the third on the macro's cycle is recognised.
        assertTrue(verdicts.subList(0, 18).stream().allMatch(v -> v == Verdict.ACTIVE), "the first laps count: " + verdicts);
        assertTrue(verdicts.subList(27, 45).stream().noneMatch(v -> v == Verdict.ACTIVE),
            "a macro scrolling through all nine hotbar slots stops counting: " + verdicts);

        ActivityClassifier rotator = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        String[] commands = {"bal", "shards", "spawn", "home base", "stats", "crates"};
        long counted = 0;
        for (int lap = 0; lap < 10; lap++) {
            for (String command : commands) {
                if (rotator.command(command) == Verdict.ACTIVE) {
                    counted++;
                }
            }
        }
        assertTrue(counted < 3 * commands.length, "rotating six commands counts only until the cycle comes round a third time: "
            + counted);
    }

    @Test
    void aPlayerDoingVariedThingsKeepsCounting() {
        ActivityClassifier player = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        Random random = new Random(7);
        long counted = 0;
        for (int i = 0; i < 200; i++) {
            String click = "use:RIGHT_CLICK_BLOCK:HAND:" + random.nextInt(40) + ",64," + random.nextInt(40) + "@" + random.nextInt(300);
            if (player.interaction(click) == Verdict.ACTIVE) {
                counted++;
            }
        }
        assertTrue(counted > 180, "building in different places keeps counting: " + counted);
    }

    @Test
    void periodicFindsCyclesOfTwoToTheLongestPeriod() {
        int[] ring = new int[30];
        int count = 0;
        for (int i = 0; i < 30; i++) {
            ring[count % ring.length] = i % 10;
            count++;
        }
        assertTrue(ActivityClassifier.periodic(ring, count, 10), "a cycle of ten, three times");
        ring[Math.floorMod(count - 5, ring.length)] = 99;
        assertTrue(!ActivityClassifier.periodic(ring, count, 10), "one change breaks it");
        assertTrue(!ActivityClassifier.periodic(new int[] {1, 2, 1, 2, 1, 0}, 5, 2), "only four values of a cycle of two");
        assertTrue(ActivityClassifier.periodic(new int[] {1, 2, 1, 2, 1, 2}, 6, 2), "six values of a cycle of two");
    }

    @Test
    void bucketsSplitTheViewIntoFifteenDegreeCells() {
        assertEquals(ActivityClassifier.bucket(0, 0), ActivityClassifier.bucket(359.0, 3));
        assertNotEquals(ActivityClassifier.bucket(0, 0), ActivityClassifier.bucket(20, 0));
        assertNotEquals(ActivityClassifier.bucket(0, 0), ActivityClassifier.bucket(0, 30));
        assertEquals(-170.0, ActivityClassifier.wrap(190.0), 1e-9);
        assertEquals(170.0, ActivityClassifier.wrap(-190.0), 1e-9);
    }

    @Test
    void theFeatureSelfTestScriptPasses() {
        assertEquals(null, AfkFeature.classifierCheck());
    }
}
