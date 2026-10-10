package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpamGuardTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    private static SpamGuard.Rules rules(Duration cooldown, int rate, Duration window, Duration repeats, SpamGuard.CapsAction caps) {
        return new SpamGuard.Rules(100, cooldown, rate, window, repeats, 0.9, 3, 0.6, 8, caps);
    }

    private static final SpamGuard.Rules OFF = rules(Duration.ZERO, 0, Duration.ZERO, Duration.ZERO, SpamGuard.CapsAction.LOWERCASE);

    @Test
    void tooLongMessagesAreRefused() {
        SpamGuard guard = new SpamGuard();
        assertEquals(SpamGuard.Verdict.TOO_LONG, guard.check(ALEX, "x".repeat(101), 0, OFF, true).verdict());
        assertTrue(guard.check(ALEX, "x".repeat(100), 0, OFF, true).allowed());
    }

    @Test
    void cooldownBetweenMessages() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = rules(Duration.ofSeconds(1), 0, Duration.ZERO, Duration.ZERO, SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "one", 10_000, rules, true).allowed());
        SpamGuard.Outcome early = guard.check(ALEX, "two", 10_400, rules, true);
        assertEquals(SpamGuard.Verdict.TOO_FAST, early.verdict());
        assertEquals(Duration.ofMillis(600), early.retryIn());
        assertTrue(guard.check(ALEX, "two", 11_000, rules, true).allowed(), "a refused message doesn't restart the cooldown");
        assertTrue(guard.check(SAM, "other player", 11_000, rules, true).allowed(), "per player");
    }

    @Test
    void rateLimitCountsAcceptedMessagesInTheWindow() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = rules(Duration.ZERO, 3, Duration.ofSeconds(10), Duration.ZERO, SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "a", 0, rules, true).allowed());
        assertTrue(guard.check(ALEX, "b", 1_000, rules, true).allowed());
        assertTrue(guard.check(ALEX, "c", 2_000, rules, true).allowed());
        SpamGuard.Outcome fourth = guard.check(ALEX, "d", 3_000, rules, true);
        assertEquals(SpamGuard.Verdict.RATE_LIMITED, fourth.verdict());
        assertEquals(Duration.ofMillis(7_000), fourth.retryIn(), "until the oldest message leaves the window");
        assertTrue(guard.check(ALEX, "d", 10_000, rules, true).allowed(), "the first message left the window");
    }

    @Test
    void exactAndNearRepeatsAreRefusedWithinTheWindow() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = rules(Duration.ZERO, 0, Duration.ZERO, Duration.ofSeconds(30), SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "selling diamonds cheap at spawn", 0, rules, true).allowed());
        assertEquals(SpamGuard.Verdict.DUPLICATE, guard.check(ALEX, "Selling diamonds cheap at spawn!!", 1_000, rules, true).verdict());
        assertEquals(SpamGuard.Verdict.DUPLICATE, guard.check(ALEX, "selling diamond cheap at spawn", 2_000, rules, true).verdict(),
            "one letter apart");
        assertTrue(guard.check(ALEX, "buying iron at spawn", 3_000, rules, true).allowed());
        assertTrue(guard.check(ALEX, "selling diamonds cheap at spawn", 31_000, rules, true).allowed(), "after the window");
    }

    @Test
    void shortMessagesOnlyRepeatWhenEqual() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = rules(Duration.ZERO, 0, Duration.ZERO, Duration.ofSeconds(30), SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "hi", 0, rules, true).allowed());
        assertTrue(guard.check(ALEX, "ho", 1_000, rules, true).allowed());
        assertEquals(SpamGuard.Verdict.DUPLICATE, guard.check(ALEX, "HI!", 2_000, rules, true).verdict());
        assertEquals(SpamGuard.Verdict.DUPLICATE, guard.check(ALEX, "hiiiiii", 3_000, rules, true).verdict(), "stretched letters");
        assertTrue(guard.check(ALEX, "???", 4_000, rules, true).allowed());
        assertEquals(SpamGuard.Verdict.DUPLICATE, guard.check(ALEX, "???", 5_000, rules, true).verdict(), "symbols repeat too");
    }

    @Test
    void onlyTheLastFewMessagesAreCompared() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = new SpamGuard.Rules(100, Duration.ZERO, 0, Duration.ZERO, Duration.ofSeconds(30), 0.9, 2, 1.0, 8,
            SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "first message here", 0, rules, true).allowed());
        assertTrue(guard.check(ALEX, "second message here", 1_000, rules, true).allowed());
        assertTrue(guard.check(ALEX, "third thing to say", 2_000, rules, true).allowed());
        assertTrue(guard.check(ALEX, "first message here", 3_000, rules, true).allowed(), "older than the last two");
    }

    @Test
    void privateMessagesSkipTheRepeatCheck() {
        SpamGuard guard = new SpamGuard();
        SpamGuard.Rules rules = rules(Duration.ZERO, 0, Duration.ZERO, Duration.ofSeconds(30), SpamGuard.CapsAction.LOWERCASE);
        assertTrue(guard.check(ALEX, "are you there", 0, rules, false).allowed());
        assertTrue(guard.check(ALEX, "are you there", 1_000, rules, false).allowed());
    }

    @Test
    void shoutingIsLoweredOrRefused() {
        SpamGuard lowering = new SpamGuard();
        SpamGuard.Outcome lowered = lowering.check(ALEX, "WHO IS SELLING ELYTRA", 0, OFF, true);
        assertTrue(lowered.allowed());
        assertEquals("who is selling elytra", lowered.text());
        SpamGuard blocking = new SpamGuard();
        SpamGuard.Rules block = rules(Duration.ZERO, 0, Duration.ZERO, Duration.ZERO, SpamGuard.CapsAction.BLOCK);
        assertEquals(SpamGuard.Verdict.CAPS, blocking.check(ALEX, "WHO IS SELLING ELYTRA", 0, block, true).verdict());
        assertEquals("GG WP", blocking.check(ALEX, "GG WP", 0, block, true).text(), "too few letters to shout");
        assertEquals("Hello Steve, How Are You", blocking.check(ALEX, "Hello Steve, How Are You", 1, block, true).text());
    }

    @Test
    void shoutingThresholds() {
        assertTrue(SpamGuard.shouting("ABCDEFGH", 0.6, 8));
        assertFalse(SpamGuard.shouting("ABCDEFG", 0.6, 8), "seven letters");
        assertFalse(SpamGuard.shouting("ABCDefgh", 0.6, 8), "half");
        assertTrue(SpamGuard.shouting("ABCDEfgh", 0.6, 8), "five of eight is over 0.6");
        assertFalse(SpamGuard.shouting("ABCDEFGH", 1.0, 8), "turned off");
        assertFalse(SpamGuard.shouting("12345678!!", 0.6, 1), "no letters");
    }

    @Test
    void similarityAndDistance() {
        assertEquals(0, SpamGuard.distance("abc", "abc"));
        assertEquals(1, SpamGuard.distance("abc", "abd"));
        assertEquals(3, SpamGuard.distance("", "abc"));
        assertEquals(3, SpamGuard.distance("kitten", "sitting"));
        assertEquals(1.0, SpamGuard.similarity("same", "same"));
        assertEquals(0.75, SpamGuard.similarity("abcd", "abce"), 1e-9);
        assertEquals("hey", SpamGuard.normalizeForCompare("HEYYYYY!!!"));
        assertEquals("heloworld", SpamGuard.normalizeForCompare("Hello, World"));
        assertEquals("? ?", SpamGuard.normalizeForCompare("  ?   ? "));
    }

    @Test
    void sweepForgetsQuietPlayers() {
        SpamGuard guard = new SpamGuard();
        guard.check(ALEX, "hello", 0, OFF, true);
        guard.check(SAM, "hello", 50_000, OFF, true);
        assertEquals(2, guard.tracked());
        guard.sweep(60_000, Duration.ofSeconds(30));
        assertEquals(1, guard.tracked());
        guard.forget(SAM);
        assertEquals(0, guard.tracked());
    }
}
