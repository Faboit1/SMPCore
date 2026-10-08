package net.siftvanilla.siftcore.feature.staff;

import java.util.Objects;
import java.util.UUID;

/**
 * A player report.
 *
 * @param id           row id, shown to staff as {@code #id}
 * @param reporter     who sent it
 * @param reporterName their name when they sent it
 * @param target       who was reported
 * @param targetName   their name when it was sent
 * @param reason       what happened, cleaned plain text
 * @param created      when it was sent (epoch milliseconds)
 * @param state        open, handled or dismissed
 * @param closedBy     the staff member who closed it (a name), empty while open
 * @param closedAt     when it was closed, 0 while open
 */
public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName, String reason,
                     long created, ReportState state, String closedBy, long closedAt) {

    public Report {
        Objects.requireNonNull(reporter);
        Objects.requireNonNull(target);
        Objects.requireNonNull(state);
        reporterName = reporterName == null ? "" : reporterName;
        targetName = targetName == null ? "" : targetName;
        reason = reason == null ? "" : reason;
        closedBy = closedBy == null ? "" : closedBy;
    }

    public boolean open() {
        return this.state == ReportState.OPEN;
    }

    /** A copy closed with {@code state} by {@code by} at {@code when}. */
    public Report close(ReportState state, String by, long when) {
        if (state == ReportState.OPEN) {
            throw new IllegalArgumentException("Closing needs a closed state");
        }
        return new Report(this.id, this.reporter, this.reporterName, this.target, this.targetName, this.reason, this.created,
            state, by, when);
    }
}
