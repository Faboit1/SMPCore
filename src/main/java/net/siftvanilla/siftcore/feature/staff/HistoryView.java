package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /history}: a player's punishments newest first with type, reason, staff, when, length and whether it is
 * still in force. Players get a paged dialog, the console gets chat lines.
 */
final class HistoryView {

    /** The most rows loaded for one player. */
    static final int MAX_ROWS = 500;

    private final Services services;
    private final StaffStore store;
    private final StaffText text;
    private final Setting<StaffSettings> settings;
    private final Logger logger;

    HistoryView(Services services, StaffStore store, StaffText text, Setting<StaffSettings> settings, Logger logger) {
        this.services = services;
        this.store = store;
        this.text = text;
        this.settings = settings;
        this.logger = logger;
    }

    /** Loads the history and shows it to {@code sender}: a dialog for players, chat for the console. */
    void show(CommandSender sender, UUID target, String name) {
        show(sender, target, name, null);
    }

    /**
     * Like {@link #show(CommandSender, UUID, String)}; {@code back} (the whois view it was opened from) adds a Back
     * button to the dialog instead of Close.
     */
    void show(CommandSender sender, UUID target, String name, Button.Handler back) {
        this.store.history(target, MAX_ROWS).whenComplete((rows, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the history of " + name, error);
                this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            if (sender instanceof Player player) {
                page(player, name, rows, back);
            } else {
                print(sender, name, rows);
            }
        });
    }

    /** The whole history in one dialog that scrolls (no pages), the newest first, up to {@code history.list-size}. */
    private void page(Player viewer, String name, List<Punishment> rows, Button.Handler back) {
        Lang lang = this.services.lang();
        int cap = this.settings.get().historyListSize();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        if (rows.isEmpty()) {
            lines.add(lang.get(StaffMessages.HISTORY_EMPTY, Arg.text("name", name)));
        } else {
            lines.add(lang.get(StaffMessages.HISTORY_COUNT, Arg.value("count", rows.size())));
            if (rows.size() > cap) {
                lines.add(lang.get(StaffMessages.HISTORY_CAPPED, Arg.value("shown", cap), Arg.value("count", rows.size())));
            }
            for (Punishment punishment : rows.subList(0, Math.min(rows.size(), cap))) {
                lines.add(Component.empty());
                lines.addAll(entry(punishment, now));
            }
        }
        this.services.dialogs().show(viewer, this.services.templates().list(lang.get(StaffMessages.HISTORY_TITLE, Arg.text("name", name)),
            lines, List.of(), 1, back));
    }

    private void print(CommandSender sender, String name, List<Punishment> rows) {
        Lang lang = this.services.lang();
        this.services.messenger().chat(sender, StaffMessages.HISTORY_HEADER, Arg.text("name", name), Arg.number("count", rows.size()));
        if (rows.isEmpty()) {
            sender.sendMessage(lang.get(StaffMessages.HISTORY_EMPTY, Arg.text("name", name)));
            return;
        }
        long now = System.currentTimeMillis();
        for (Punishment punishment : rows) {
            for (Component line : entry(punishment, now)) {
                sender.sendMessage(line);
            }
        }
    }

    /** The lines of one punishment. */
    private List<Component> entry(Punishment punishment, long now) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>(3);
        lines.add(lang.get(StaffMessages.HISTORY_ENTRY, Arg.component("type", this.text.type(punishment.type())),
            Arg.time("ago", StaffText.since(punishment.created(), now)),
            Arg.text("staff", this.text.staff(punishment.staff(), punishment.staffName()))));
        lines.add(lang.get(StaffMessages.HISTORY_REASON, Arg.text("reason", this.text.reason(punishment.reason()))));
        if (punishment.type().lasting()) {
            lines.add(lang.get(StaffMessages.HISTORY_LENGTH, this.text.length("length", punishment.length()),
                Arg.component("state", state(punishment, now))));
        }
        return lines;
    }

    private Component state(Punishment punishment, long now) {
        Lang lang = this.services.lang();
        return switch (punishment.state(now)) {
            case ACTIVE -> punishment.permanent()
                ? lang.get(StaffMessages.STATE_ACTIVE_PERMANENT)
                : lang.get(StaffMessages.STATE_ACTIVE, Arg.time("left", punishment.remaining(now)));
            case EXPIRED -> lang.get(StaffMessages.STATE_EXPIRED);
            case LIFTED -> lang.get(StaffMessages.STATE_LIFTED, Arg.text("name", punishment.revokedBy()));
            case RECORD -> Component.empty();
        };
    }
}
