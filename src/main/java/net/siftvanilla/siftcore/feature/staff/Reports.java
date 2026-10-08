package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Player reports. Open reports are held in memory and stored in {@code staff_reports}; staff are told about each
 * new one with a chat line that opens it. Closing is atomic: two staff members closing the same report at once
 * end up with one closing it and the other being told it is already closed.
 */
final class Reports {

    /**
     * Why a report was refused, ready to show.
     *
     * @param key  the message
     * @param args its placeholders
     */
    record Refusal(MessageKey key, Arg... args) {
    }

    /** The command staff click in a report notification (also usable by hand). */
    static final String REVIEW_COMMAND = "/reports ";

    private final StaffStore store;
    private final AuditLog audit;
    private final Messenger messenger;
    private final Lang lang;
    private final StaffNotices notices;
    private final Setting<StaffSettings> settings;
    private final Logger logger;
    private final ReportRules rules = new ReportRules(System::currentTimeMillis);
    private final Map<Long, Report> open = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    Reports(StaffStore store, AuditLog audit, Messenger messenger, StaffNotices notices, Setting<StaffSettings> settings,
            Logger logger) {
        this.store = store;
        this.audit = audit;
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.notices = notices;
        this.settings = settings;
        this.logger = logger;
    }

    /** Loads the open reports; call once from enable (blocks on storage). */
    void load() throws Exception {
        List<Report> rows = this.store.openReports().get();
        synchronized (this.lock) {
            this.open.clear();
            for (Report report : rows) {
                this.open.put(report.id(), report);
            }
        }
    }

    /** Open reports, oldest first. */
    List<Report> open() {
        List<Report> list = new ArrayList<>(this.open.values());
        list.sort(Comparator.comparingLong(Report::id));
        return list;
    }

    Optional<Report> open(long id) {
        return Optional.ofNullable(this.open.get(id));
    }

    int openCount() {
        return this.open.size();
    }

    /**
     * Checks and stores a report from {@code reporter}, tells staff and thanks the reporter. Returns null when it was
     * sent, otherwise why not (nothing was stored). Runs on the reporter's thread.
     */
    Refusal submit(Player reporter, UUID target, String targetName, String rawReason) {
        String reason = CleanText.clean(rawReason);
        ReportRules.Limits limits = this.settings.get().reports();
        UUID reporterId = reporter.getUniqueId();
        Refusal refusal = refusal(ReportRules.check(reporterId, target, reason, this.open.values(), limits), targetName, limits);
        if (refusal != null) {
            return refusal;
        }
        if (!reporter.hasPermission("siftcore.bypass.cooldown")) {
            Duration left = this.rules.tryStart(reporterId, limits.cooldown());
            if (!left.isZero()) {
                return new Refusal(CoreMessages.COOLDOWN, Arg.time("time", left));
            }
        }
        Report report;
        synchronized (this.lock) {
            refusal = refusal(ReportRules.check(reporterId, target, reason, this.open.values(), limits), targetName, limits);
            if (refusal != null) {
                this.rules.reset(reporterId);
                return refusal;
            }
            report = new Report(this.store.nextReportId(), reporterId, reporter.getName(), target, targetName, reason,
                System.currentTimeMillis(), ReportState.OPEN, "", 0L);
            this.open.put(report.id(), report);
            watch(this.store.insert(report), "store report " + report.id());
        }
        Component line = this.lang.get(StaffMessages.REPORT_NOTIFY, Arg.text("id", Long.toString(report.id())),
                Arg.text("reporter", report.reporterName()), Arg.text("target", report.targetName()), Arg.text("reason", report.reason()))
            .clickEvent(ClickEvent.runCommand(REVIEW_COMMAND + report.id()))
            .hoverEvent(HoverEvent.showText(this.lang.get(StaffMessages.REPORT_NOTIFY_HOVER)));
        this.notices.send(StaffNodes.REPORTS, null, line, Feedback.NOTIFY);
        this.messenger.send(reporter, StaffMessages.REPORT_SENT, Arg.text("name", targetName));
        return null;
    }

    private static Refusal refusal(ReportRules.Verdict verdict, String targetName, ReportRules.Limits limits) {
        return switch (verdict) {
            case OK -> null;
            case SELF -> new Refusal(CoreMessages.NOT_YOURSELF);
            case TOO_SHORT -> new Refusal(StaffMessages.REPORT_TOO_SHORT, Arg.number("min", limits.minLength()));
            case TOO_LONG -> new Refusal(StaffMessages.REPORT_TOO_LONG, Arg.number("max", limits.maxLength()));
            case DUPLICATE -> new Refusal(StaffMessages.REPORT_DUPLICATE, Arg.text("name", targetName));
            case TOO_MANY -> new Refusal(StaffMessages.REPORT_TOO_MANY, Arg.number("count", limits.maxOpenPerReporter()));
        };
    }

    /** Closes an open report. Empty when it was not open (already closed by someone else). */
    Optional<Report> close(long id, ReportState state, Actor actor) {
        Report closed;
        synchronized (this.lock) {
            Report report = this.open.remove(id);
            if (report == null) {
                return Optional.empty();
            }
            closed = report.close(state, actor.name(), System.currentTimeMillis());
            watch(this.store.close(closed), "close report " + id);
        }
        this.audit.record(actor.id(), "staff.report." + state.name().toLowerCase(Locale.ROOT), closed.target().toString(), "#" + id);
        if (state == ReportState.HANDLED) {
            Player reporter = Bukkit.getPlayer(closed.reporter());
            if (reporter != null) {
                this.messenger.send(reporter, StaffMessages.REPORT_HANDLED, Arg.text("name", closed.targetName()));
            }
        }
        return Optional.of(closed);
    }

    /** Forgets finished cooldowns (timer). */
    void sweep() {
        this.rules.sweep();
    }

    /** Open reports in memory and in storage, counted at the same moment (self-test). */
    CompletableFuture<String> checkStorage() {
        int memory;
        CompletableFuture<Integer> stored;
        synchronized (this.lock) {
            memory = this.open.size();
            stored = this.store.countOpenReports();
        }
        return stored.thenApply(count -> count == memory ? null : memory + " open reports in memory, " + count + " stored");
    }

    private void watch(CompletableFuture<?> write, String what) {
        write.whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Could not " + what + "; memory and database may differ until a restart", error);
            }
        });
    }
}
