package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The report form players fill in, and the staff screens: the paged list of open reports and one report with its
 * actions (teleport to the reported player, mark handled, dismiss). Every action re-checks the permission and the
 * report's state when it is clicked.
 */
final class ReportDialogs {

    private static final int MAX_NAME = 16;

    private final Services services;
    private final Reports reports;
    private final Setting<StaffSettings> settings;

    ReportDialogs(Services services, Reports reports, Setting<StaffSettings> settings) {
        this.services = services;
        this.reports = reports;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ players

    /** The report form from /report: Cancel closes it. */
    void openForm(Player player, String name, String reason) {
        openForm(player, name, reason, null);
    }

    /** The report form; {@code back} (the main menu, for the hub entry) turns Cancel into Back. */
    void openForm(Player player, String name, String reason, Button.Handler back) {
        Lang lang = this.services.lang();
        int max = this.settings.get().reports().maxLength();
        this.services.dialogs().show(player, this.services.templates().form(
            lang.get(StaffMessages.REPORT_FORM_TITLE),
            lang.lines(StaffMessages.REPORT_FORM_BODY),
            List.of(Templates.text("player", lang.get(StaffMessages.REPORT_FORM_PLAYER), name, MAX_NAME),
                new Input.Text("reason", lang.get(StaffMessages.REPORT_FORM_REASON), reason, max, 3, 250)),
            lang.get(StaffMessages.REPORT_FORM_SUBMIT),
            this::submitForm,
            back));
    }

    private void submitForm(Submission submission) {
        Player player = submission.player();
        Lang lang = this.services.lang();
        if (!player.hasPermission(StaffNodes.REPORT)) {
            submission.close();
            return;
        }
        String name = submission.values().text("player");
        Player online = Bukkit.getPlayerExact(name);
        Optional<UUID> target = online != null ? Optional.of(online.getUniqueId()) : this.services.directory().uuid(name);
        if (target.isEmpty()) {
            submission.error(lang.get(CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", name)));
            return;
        }
        Reports.Refusal refusal = this.reports.submit(player, target.get(), this.services.directory().name(target.get()),
            submission.values().text("reason"));
        if (refusal != null) {
            submission.error(lang.get(refusal.key(), refusal.args()));
            return;
        }
        submission.close();
    }

    // ------------------------------------------------------------------ staff

    /** The paged list of open reports. */
    void openList(Player staff, int page) {
        Lang lang = this.services.lang();
        List<Report> all = this.reports.open();
        int size = this.settings.get().reportsPageSize();
        int pages = Math.max(1, (all.size() + size - 1) / size);
        int current = Math.clamp(page, 1, pages);
        List<Component> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (all.isEmpty()) {
            lines.add(lang.get(StaffMessages.REPORTS_EMPTY));
        } else {
            lines.add(lang.get(StaffMessages.REPORTS_SUMMARY, Arg.number("count", all.size()), Arg.number("page", current),
                Arg.number("pages", pages)));
            for (Report report : all.subList((current - 1) * size, Math.min(all.size(), current * size))) {
                lines.add(lang.get(StaffMessages.REPORTS_LINE, Arg.text("id", Long.toString(report.id())),
                    Arg.text("target", report.targetName()), Arg.text("reporter", report.reporterName()),
                    Arg.time("age", StaffText.since(report.created(), now)), Arg.component("status", status(report))));
                buttons.add(Button.of(lang.get(StaffMessages.REPORTS_BUTTON, Arg.text("id", Long.toString(report.id())),
                    Arg.text("target", report.targetName())), s -> openDetail(s.player(), report.id(), current)).width(150));
            }
        }
        if (current > 1) {
            buttons.add(Button.of(lang.get(StaffMessages.PAGE_PREVIOUS), s -> openList(s.player(), current - 1)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(StaffMessages.PAGE_NEXT), s -> openList(s.player(), current + 1)).width(150));
        }
        this.services.dialogs().show(staff, this.services.templates().list(lang.get(StaffMessages.REPORTS_TITLE), lines,
            buttons, 2, null));
    }

    /** One open report with its actions; {@code page} is the list page to go back to. */
    void openDetail(Player staff, long id, int page) {
        Lang lang = this.services.lang();
        Optional<Report> found = this.reports.open(id);
        if (found.isEmpty()) {
            this.services.messenger().send(staff, StaffMessages.REPORTS_CLOSED, Arg.text("id", Long.toString(id)));
            openList(staff, page);
            return;
        }
        Report report = found.get();
        List<Component> lines = lang.lines(StaffMessages.REPORTS_DETAIL, Arg.text("target", report.targetName()),
            Arg.component("status", status(report)), Arg.text("reporter", report.reporterName()),
            Arg.time("age", StaffText.since(report.created(), System.currentTimeMillis())), Arg.text("reason", report.reason()));
        List<Button> buttons = new ArrayList<>();
        if (Bukkit.getPlayer(report.target()) != null) {
            buttons.add(Button.of(lang.get(StaffMessages.REPORTS_TELEPORT, Arg.text("name", report.targetName())),
                s -> teleport(s, report, page)).width(Templates.WIDE));
        }
        buttons.add(Button.of(lang.get(StaffMessages.REPORTS_HANDLE), s -> close(s, report, ReportState.HANDLED, page)).width(150));
        buttons.add(Button.of(lang.get(StaffMessages.REPORTS_DISMISS), s -> close(s, report, ReportState.DISMISSED, page)).width(150));
        this.services.dialogs().show(staff, this.services.templates().list(
            lang.get(StaffMessages.REPORTS_DETAIL_TITLE, Arg.text("id", Long.toString(report.id()))),
            lines, buttons, 2, s -> openList(s.player(), page)));
    }

    private void close(Submission submission, Report report, ReportState state, int page) {
        Player staff = submission.player();
        if (!staff.hasPermission(StaffNodes.REPORTS)) {
            submission.close();
            return;
        }
        Optional<Report> closed = this.reports.close(report.id(), state, Actor.of(staff));
        Arg id = Arg.text("id", Long.toString(report.id()));
        if (closed.isEmpty()) {
            this.services.messenger().send(staff, StaffMessages.REPORTS_CLOSED, id);
        } else {
            this.services.messenger().send(staff, state == ReportState.HANDLED ? StaffMessages.REPORTS_HANDLED : StaffMessages.REPORTS_DISMISSED, id);
        }
        openList(staff, page);
    }

    /** Teleports staff to the reported player, with no warmup. */
    private void teleport(Submission submission, Report report, int page) {
        Player staff = submission.player();
        if (!staff.hasPermission(StaffNodes.REPORTS)) {
            submission.close();
            return;
        }
        Player target = Bukkit.getPlayer(report.target());
        if (target == null) {
            // The report again (now without Teleport) replaces this dialog.
            this.services.messenger().send(staff, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", report.targetName()));
            openDetail(staff, report.id(), page);
            return;
        }
        submission.close();
        this.services.audit().record(staff.getUniqueId().toString(), "staff.report.teleport", report.target().toString(),
            "#" + report.id());
        this.services.scheduler().supplyOnEntity(target, target::getLocation).whenComplete((location, error) -> {
            if (error != null || location == null) {
                this.services.messenger().send(staff, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", report.targetName()));
                return;
            }
            staff.teleportAsync(location, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((ok, failure) -> {
                if (failure == null && Boolean.TRUE.equals(ok)) {
                    this.services.messenger().send(staff, StaffMessages.REPORTS_TELEPORTED, Arg.text("name", report.targetName()));
                } else {
                    this.services.messenger().send(staff, TeleportMessages.FAILED);
                }
            });
        });
    }

    /** Console output of /reports. */
    void print(CommandSender sender) {
        Lang lang = this.services.lang();
        List<Report> all = this.reports.open();
        if (all.isEmpty()) {
            sender.sendMessage(lang.get(StaffMessages.REPORTS_EMPTY));
            return;
        }
        long now = System.currentTimeMillis();
        for (Report report : all) {
            this.services.messenger().chat(sender, StaffMessages.REPORTS_CONSOLE_LINE, Arg.text("id", Long.toString(report.id())),
                Arg.text("target", report.targetName()), Arg.text("reporter", report.reporterName()),
                Arg.time("age", StaffText.since(report.created(), now)), Arg.component("status", status(report)),
                Arg.text("reason", report.reason()));
        }
    }

    private Component status(Report report) {
        return this.services.lang().get(Bukkit.getPlayer(report.target()) != null ? StaffMessages.REPORTS_ONLINE : StaffMessages.REPORTS_OFFLINE);
    }
}
