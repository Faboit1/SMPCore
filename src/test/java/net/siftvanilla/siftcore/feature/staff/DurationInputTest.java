package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationInputTest {

    private static final Duration MAX = Duration.ofDays(3650);

    @Test
    void optionalTimeThenReason() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("1h30m spamming in chat", MAX);
        assertTrue(parsed.ok());
        assertEquals(Duration.ofMinutes(90), parsed.length());
        assertEquals("spamming in chat", parsed.reason());
        assertEquals("1h30m", parsed.token());
    }

    @Test
    void missingTimeMeansPermanentAndEverythingIsTheReason() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("spamming in chat", MAX);
        assertTrue(parsed.ok());
        assertTrue(parsed.permanent());
        assertEquals("spamming in chat", parsed.reason());
    }

    @Test
    void bareNumbersAreNeverTimes() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("5 alts on one address", MAX);
        assertTrue(parsed.ok());
        assertTrue(parsed.permanent());
        assertEquals("5 alts on one address", parsed.reason());
    }

    @Test
    void emptyInputIsPermanentWithoutReason() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("", MAX);
        assertTrue(parsed.ok());
        assertTrue(parsed.permanent());
        assertEquals("", parsed.reason());
        assertTrue(DurationInput.optionalLength(null, MAX).permanent());
    }

    @Test
    void permanentWordsAreExplicit() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("perm toxic", MAX);
        assertTrue(parsed.ok());
        assertTrue(parsed.permanent());
        assertEquals("toxic", parsed.reason());
        assertTrue(DurationInput.optionalLength("PERMANENT", MAX).permanent());
        assertTrue(DurationInput.optionalLength("forever and ever", MAX).permanent());
    }

    @Test
    void unknownUnitIsRefused() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("10x spam", MAX);
        assertFalse(parsed.ok());
        assertEquals(DurationInput.Problem.INVALID, parsed.problem());
        assertEquals("10x", parsed.token());
    }

    @Test
    void limitsAreEnforced() {
        assertEquals(DurationInput.Problem.TOO_LONG, DurationInput.optionalLength("3651d", MAX).problem());
        assertEquals(DurationInput.Problem.NONE, DurationInput.optionalLength("3650d", MAX).problem());
        assertEquals(DurationInput.Problem.TOO_SHORT, DurationInput.optionalLength("500ms", MAX).problem());
        assertEquals(DurationInput.Problem.NONE, DurationInput.optionalLength("1s", MAX).problem());
        assertEquals(DurationInput.Problem.TOO_LONG, DurationInput.optionalLength("2h", Duration.ofHours(1)).problem());
        // Overflowing arithmetic is "too long", not a crash.
        assertEquals(DurationInput.Problem.TOO_LONG, DurationInput.optionalLength("99999999999999999w", MAX).problem());
    }

    @Test
    void requiredTimeMustBeThere() {
        DurationInput.Parsed parsed = DurationInput.requiredLength("7d griefing spawn", MAX);
        assertTrue(parsed.ok());
        assertEquals(Duration.ofDays(7), parsed.length());
        assertEquals("griefing spawn", parsed.reason());
        assertEquals(DurationInput.Problem.MISSING, DurationInput.requiredLength("griefing", MAX).problem());
        assertEquals(DurationInput.Problem.MISSING, DurationInput.requiredLength("", MAX).problem());
        assertEquals(DurationInput.Problem.MISSING, DurationInput.requiredLength("permanent", MAX).problem());
        assertEquals(DurationInput.Problem.MISSING, DurationInput.requiredLength("30 griefing", MAX).problem());
    }

    @Test
    void reasonsAreCleanedAndLimited() {
        DurationInput.Parsed parsed = DurationInput.optionalLength("1h  spam§c  and\tmore​", MAX);
        assertEquals("spamc and more", parsed.reason());
        assertEquals(DurationInput.Problem.REASON_TOO_LONG, DurationInput.reasonOnly("x".repeat(DurationInput.MAX_REASON + 1)).problem());
        assertTrue(DurationInput.reasonOnly("x".repeat(DurationInput.MAX_REASON)).ok());
        assertNull(DurationInput.reasonOnly("hacking").length());
    }

    @Test
    void singleTokenCheck() {
        assertEquals(DurationInput.Problem.NONE, DurationInput.check("12h", MAX));
        assertEquals(DurationInput.Problem.INVALID, DurationInput.check("12", MAX));
        assertEquals(DurationInput.Problem.INVALID, DurationInput.check("soon", MAX));
        assertEquals(DurationInput.Problem.INVALID, DurationInput.check(null, MAX));
    }
}
