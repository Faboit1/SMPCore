package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReportRulesTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final ReportRules.Limits LIMITS = new ReportRules.Limits(3, 100, 2, Duration.ofSeconds(60));

    private static Report open(long id, UUID reporter, UUID target) {
        return new Report(id, reporter, "r", target, "t", "flying", 0L, ReportState.OPEN, "", 0L);
    }

    @Test
    void acceptsAPlainReport() {
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(ALICE, BOB, "flying around", List.of(), LIMITS));
    }

    @Test
    void refusesSelfReports() {
        assertEquals(ReportRules.Verdict.SELF, ReportRules.check(ALICE, ALICE, "testing this", List.of(), LIMITS));
    }

    @Test
    void reasonLengthIsCheckedAfterCleaning() {
        assertEquals(ReportRules.Verdict.TOO_SHORT, ReportRules.check(ALICE, BOB, CleanText.clean("  a\u0000b  "), List.of(), LIMITS));
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(ALICE, BOB, CleanText.clean("abc"), List.of(), LIMITS));
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(ALICE, BOB, "x".repeat(100), List.of(), LIMITS));
        assertEquals(ReportRules.Verdict.TOO_LONG, ReportRules.check(ALICE, BOB, "x".repeat(101), List.of(), LIMITS));
        // Lengths count characters, not UTF-16 units: 100 characters outside the basic plane are fine.
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(ALICE, BOB, "𝐀".repeat(100), List.of(), LIMITS));
    }

    @Test
    void oneOpenReportPerTargetAndALimitPerReporter() {
        List<Report> openReports = List.of(open(1, ALICE, BOB));
        assertEquals(ReportRules.Verdict.DUPLICATE, ReportRules.check(ALICE, BOB, "again and again", openReports, LIMITS));
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(EVE, BOB, "same target, other reporter", openReports, LIMITS));
        List<Report> two = List.of(open(1, ALICE, BOB), open(2, ALICE, UUID.randomUUID()));
        assertEquals(ReportRules.Verdict.TOO_MANY, ReportRules.check(ALICE, EVE, "a third one", two, LIMITS));
        Report closed = open(3, ALICE, EVE).close(ReportState.HANDLED, "Staff", 5L);
        assertEquals(ReportRules.Verdict.OK, ReportRules.check(ALICE, EVE, "closed ones don't count", List.of(closed), LIMITS));
    }

    @Test
    void cooldownIsPerReporterAndAtomic() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ReportRules rules = new ReportRules(clock::get);
        assertEquals(Duration.ZERO, rules.tryStart(ALICE, LIMITS.cooldown()));
        assertEquals(Duration.ofSeconds(60), rules.tryStart(ALICE, LIMITS.cooldown()));
        assertEquals(Duration.ZERO, rules.tryStart(BOB, LIMITS.cooldown()), "other reporters are not affected");
        clock.addAndGet(59_000L);
        assertEquals(Duration.ofSeconds(1), rules.tryStart(ALICE, LIMITS.cooldown()), "a refused try does not restart it");
        clock.addAndGet(1_000L);
        assertEquals(Duration.ZERO, rules.tryStart(ALICE, LIMITS.cooldown()));
        assertTrue(rules.tryStart(ALICE, LIMITS.cooldown()).compareTo(Duration.ZERO) > 0);
        rules.reset(ALICE);
        assertEquals(Duration.ZERO, rules.tryStart(ALICE, LIMITS.cooldown()));
        assertEquals(Duration.ZERO, rules.tryStart(EVE, Duration.ZERO), "no cooldown configured");
    }

    @Test
    void sweepForgetsFinishedCooldowns() {
        AtomicLong clock = new AtomicLong(0L);
        ReportRules rules = new ReportRules(clock::get);
        rules.tryStart(ALICE, Duration.ofSeconds(10));
        rules.tryStart(BOB, Duration.ofSeconds(30));
        clock.set(15_000L);
        rules.sweep();
        assertEquals(1, rules.tracked());
        clock.set(31_000L);
        rules.sweep();
        assertEquals(0, rules.tracked());
    }

    @Test
    void cleaningRemovesControlAndFormattingCharacters() {
        assertEquals("he is cflying", CleanText.clean("  he\n is §cflying‮  "));
        assertEquals("", CleanText.clean(null));
        assertEquals("", CleanText.clean(" \t\n "));
        assertEquals("<red>tags stay literal</red>", CleanText.clean("<red>tags stay literal</red>"));
    }

    @Test
    void closingKeepsTheReport() {
        Report report = open(7, ALICE, BOB);
        Report handled = report.close(ReportState.HANDLED, "Mod", 99L);
        assertEquals(ReportState.HANDLED, handled.state());
        assertEquals("Mod", handled.closedBy());
        assertEquals(99L, handled.closedAt());
        assertEquals(report.reason(), handled.reason());
        assertTrue(report.open());
        assertEquals(ReportState.DISMISSED, ReportState.parse("dismissed"));
    }
}
