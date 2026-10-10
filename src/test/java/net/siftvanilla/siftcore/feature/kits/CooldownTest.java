package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CooldownTest {

    private static final long HOUR = 3_600_000L;
    private static final long CLAIMED = 1_700_000_000_000L;

    @Test
    void parsesOnceAndDurations() {
        assertSame(Cooldown.ONCE, Cooldown.parse("once"));
        assertSame(Cooldown.ONCE, Cooldown.parse(" ONCE "));
        assertEquals(Duration.ofHours(24), Cooldown.parse("24h").every());
        assertEquals(Duration.ofHours(36), Cooldown.parse("1d12h").every());
        assertEquals(Duration.ofDays(3), Cooldown.parse("3d").every());
        assertEquals(Duration.ofSeconds(90), Cooldown.parse("90").every());
        assertTrue(Cooldown.ONCE.once());
        assertFalse(Cooldown.parse("1h").once());
    }

    @Test
    void refusesWhatIsNoCooldown() {
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("never"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("500ms"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("366d"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("5x"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse("99999999999999999w"));
        assertThrows(IllegalArgumentException.class, () -> Cooldown.parse(null));
    }

    @Test
    void neverClaimedIsReady() {
        assertTrue(Cooldown.ONCE.status(null, CLAIMED).ready());
        assertTrue(Cooldown.parse("24h").status(null, CLAIMED).ready());
    }

    @Test
    void onceStaysClaimedForever() {
        KitStatus status = Cooldown.ONCE.status(CLAIMED, CLAIMED + 1000L * 24 * HOUR);
        KitStatus.Claimed claimed = assertInstanceOf(KitStatus.Claimed.class, status);
        assertEquals(CLAIMED, claimed.at());
        assertFalse(status.ready());
    }

    @Test
    void waitsUntilTheCooldownEnds() {
        Cooldown day = Cooldown.parse("24h");
        KitStatus.Waiting waiting = assertInstanceOf(KitStatus.Waiting.class, day.status(CLAIMED, CLAIMED + 20 * HOUR + 40 * 60_000L));
        assertEquals(Duration.ofMinutes(3 * 60 + 20), waiting.left());
        assertEquals(CLAIMED + 24 * HOUR, waiting.readyAt());
        assertFalse(day.status(CLAIMED, CLAIMED + 24 * HOUR - 1).ready());
        assertTrue(day.status(CLAIMED, CLAIMED + 24 * HOUR).ready());
        assertTrue(day.status(CLAIMED, CLAIMED + 1000 * HOUR).ready());
    }

    @Test
    void aClaimThisVeryMomentWaitsTheWholeCooldown() {
        KitStatus.Waiting waiting = assertInstanceOf(KitStatus.Waiting.class, Cooldown.parse("72h").status(CLAIMED, CLAIMED));
        assertEquals(Duration.ofHours(72), waiting.left());
    }

    @Test
    void aClockThatWentBackNeverMeansAWaitLongerThanOneCooldown() {
        Cooldown day = Cooldown.parse("24h");
        long now = CLAIMED - 5 * HOUR;
        KitStatus.Waiting waiting = assertInstanceOf(KitStatus.Waiting.class, day.status(CLAIMED, now));
        assertEquals(Duration.ofHours(24), waiting.left());
        assertEquals(now + 24 * HOUR, waiting.readyAt());
    }

    @Test
    void aChangedCooldownAppliesToEarlierClaims() {
        long now = CLAIMED + HOUR;
        KitStatus.Waiting longer = assertInstanceOf(KitStatus.Waiting.class, Cooldown.parse("48h").status(CLAIMED, now));
        assertEquals(Duration.ofHours(47), longer.left());
        assertTrue(Cooldown.parse("30m").status(CLAIMED, now).ready());
    }

    @Test
    void timeLeftIsShownRoundedUpToSeconds() {
        assertEquals(Duration.ofSeconds(1), new KitStatus.Waiting(Duration.ofMillis(1), 0).shown());
        assertEquals(Duration.ofSeconds(2), new KitStatus.Waiting(Duration.ofMillis(1001), 0).shown());
        assertEquals(Duration.ofSeconds(60), new KitStatus.Waiting(Duration.ofSeconds(60), 0).shown());
        assertEquals(Duration.ofSeconds(1), KitText.roundUp(Duration.ZERO));
        assertEquals(Duration.ofSeconds(13), KitText.roundUp(Duration.ofMillis(12_300)));
    }

    @Test
    void farFutureClaimsDoNotOverflow() {
        Cooldown year = Cooldown.parse("365d");
        KitStatus status = year.status(Long.MAX_VALUE - 10, Long.MAX_VALUE - 5);
        assertFalse(status.ready());
    }

    @Test
    void describesItselfLikeTheConfig() {
        assertEquals("once", Cooldown.ONCE.describe());
        assertEquals("1d 12h", Cooldown.parse("36h").describe());
        assertEquals("30m", Cooldown.parse("30m").describe());
    }
}
