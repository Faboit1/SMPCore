package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who may report whom, with what, and how often. Validation is pure; the per-reporter cooldown is the only state
 * and uses an injected clock so it can be tested.
 */
final class ReportRules {

    /** The outcome of checking a report before it is stored. */
    enum Verdict {
        OK,
        SELF,
        TOO_SHORT,
        TOO_LONG,
        DUPLICATE,
        TOO_MANY
    }

    /**
     * Limits from the config.
     *
     * @param minLength          shortest accepted reason (after cleaning)
     * @param maxLength          longest accepted reason
     * @param maxOpenPerReporter open reports one player may have at the same time
     * @param cooldown           time between two reports of the same player
     */
    record Limits(int minLength, int maxLength, int maxOpenPerReporter, Duration cooldown) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Long> nextAllowed = new ConcurrentHashMap<>();

    ReportRules(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Checks a report. {@code reason} must already be cleaned with {@link CleanText#clean(String)};
     * {@code openReports} are the reports currently open (any reporter).
     */
    static Verdict check(UUID reporter, UUID target, String reason, Collection<Report> openReports, Limits limits) {
        if (reporter.equals(target)) {
            return Verdict.SELF;
        }
        int length = reason.codePointCount(0, reason.length());
        if (length < limits.minLength()) {
            return Verdict.TOO_SHORT;
        }
        if (length > limits.maxLength()) {
            return Verdict.TOO_LONG;
        }
        int mine = 0;
        for (Report report : openReports) {
            if (!report.open() || !report.reporter().equals(reporter)) {
                continue;
            }
            if (report.target().equals(target)) {
                return Verdict.DUPLICATE;
            }
            mine++;
        }
        if (mine >= limits.maxOpenPerReporter()) {
            return Verdict.TOO_MANY;
        }
        return Verdict.OK;
    }

    /**
     * Atomically starts the reporter's cooldown if it is over. Returns {@link Duration#ZERO} when the report may go
     * ahead (the cooldown is now running), otherwise the time left (nothing changes).
     */
    Duration tryStart(UUID reporter, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return Duration.ZERO;
        }
        long now = this.clock.getAsLong();
        long[] left = {0};
        this.nextAllowed.compute(reporter, (key, until) -> {
            if (until != null && until > now) {
                left[0] = until - now;
                return until;
            }
            return now + cooldown.toMillis();
        });
        return left[0] > 0 ? Duration.ofMillis(left[0]) : Duration.ZERO;
    }

    /** Ends a reporter's cooldown early (used when a report could not be stored). */
    void reset(UUID reporter) {
        this.nextAllowed.remove(reporter);
    }

    /** Forgets cooldowns that are over, so the map only holds recent reporters. */
    void sweep() {
        long now = this.clock.getAsLong();
        this.nextAllowed.values().removeIf(until -> until <= now);
    }

    int tracked() {
        return this.nextAllowed.size();
    }
}
