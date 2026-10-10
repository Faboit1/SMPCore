package net.siftvanilla.siftcore.feature.boosters;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /booster} for everyone (the running booster, who it is from and what comes next, with the boss bar switch)
 * and {@code /sift booster start|stop|list} for staff and the console. Every staff action is audited
 * ({@code booster.start}, {@code booster.stop}).
 */
final class BoosterCommands {

    static final String COMMAND = "siftcore.command.booster";
    static final String ADMIN = "siftcore.admin.booster";
    /** Longest staff reason kept. */
    private static final int REASON_MAX = 128;

    private final Services services;
    private final Setting<BoostersSettings> settings;
    private final BoosterService service;
    private final BoosterAnnouncer names;
    private final Toggle bar;
    private final Consumer<Player> refresh;

    /**
     * @param refresh shows or hides a player's boss bar at once (after they flipped the switch)
     */
    BoosterCommands(Services services, Setting<BoostersSettings> settings, BoosterService service, BoosterAnnouncer names, Toggle bar,
                    Consumer<Player> refresh) {
        this.services = services;
        this.settings = settings;
        this.service = service;
        this.names = names;
        this.bar = bar;
        this.refresh = refresh;
    }

    List<SiftCommand> all() {
        return List.of(new SimpleCommand("booster", List.of("boosters", "boost"), "Shows the server sell booster", COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(COMMAND))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player player) {
                        open(player, null);
                    } else {
                        print(sender);
                    }
                    return CommandSupport.OK;
                })));
    }

    AdminFeature.AdminCommandPart part() {
        return () -> Commands.literal("booster").requires(CommandSupport.permission(ADMIN))
            .executes(ctx -> list(ctx.getSource().getSender()))
            .then(Commands.literal("start")
                .then(Commands.argument("percent", IntegerArgumentType.integer(1, BoostersSettings.PERCENT_CAP))
                    .then(Commands.argument("length", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String example : List.of("30m", "1h", "2h", "24h")) {
                                if (example.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(example);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> start(ctx, null))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "reason")))))))
            .then(Commands.literal("stop")
                .executes(ctx -> stop(ctx.getSource().getSender(), null))
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                    .suggests((context, builder) -> {
                        BoosterService.View view = this.service.view();
                        if (view.active() != null) {
                            builder.suggest(Long.toString(view.active().id()));
                        }
                        for (Booster booster : view.waiting()) {
                            builder.suggest(Long.toString(booster.id()));
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> stop(ctx.getSource().getSender(), LongArgumentType.getLong(ctx, "id")))))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getSender())));
    }

    // ------------------------------------------------------------------ /booster

    /** The booster dialog. {@code back} runs on Back (from a menu); null shows Close. */
    void open(Player player, Button.Handler back) {
        open(player, back, null);
    }

    /** The booster dialog with a red line under it when {@code error} is set. */
    private void open(Player player, Button.Handler back, Component error) {
        Lang lang = this.services.lang();
        List<Component> lines = lines(player.getUniqueId());
        var prefs = this.services.settings();
        boolean shown = prefs.get(player, this.bar);
        List<Button> buttons = new ArrayList<>(1);
        // The switch ("Booster bar: ON"), unless the server fixed the setting for everyone (locked or hidden). A click
        // flips it and shows the dialog again with the new state; a refusal shows in red there.
        if (this.settings.get().bar().enabled() && !prefs.locked(this.bar) && !prefs.hidden(this.bar)) {
            buttons.add(this.services.templates().switchButton(lang.get(BoostersMessages.TOGGLE_LABEL), shown,
                lang.get(BoostersMessages.BAR_TOOLTIP), s -> {
                    SetResult result = prefs.set(s.player(), this.bar, !prefs.get(s.player(), this.bar), Change.feature());
                    this.refresh.accept(s.player());
                    if (result.succeeded()) {
                        open(s.player(), back);
                    } else {
                        this.services.messenger().feedback(s.player(), Feedback.ERROR);
                        open(s.player(), back, lang.get(BoostersMessages.BAR_FIXED));
                    }
                }).width(Templates.LONG));
        }
        View view = this.services.templates().list(lang.get(BoostersMessages.TITLE), lines, buttons, 1, back);
        this.services.dialogs().show(player, error == null ? view : view.withError(error, FormValues.EMPTY));
    }

    /** {@code /booster} from the console: the same lines in chat. */
    private void print(CommandSender sender) {
        for (Component line : lines(null)) {
            sender.sendMessage(line);
        }
    }

    /** What /booster shows: the running booster and the line. */
    private List<Component> lines(UUID viewer) {
        Lang lang = this.services.lang();
        BoostersSettings s = this.settings.get();
        BoosterService.View view = this.service.view();
        List<Component> lines = new ArrayList<>();
        Booster active = view.active();
        if (active == null) {
            lines.addAll(lang.lines(BoostersMessages.NONE));
        } else {
            lines.addAll(lang.lines(BoostersMessages.ACTIVE, Arg.number("percent", this.service.percent())));
            lines.addAll(lang.lines(BoostersMessages.LEFT, Arg.text("time", Durations.format(this.service.left())),
                Arg.text("length", Durations.format(active.duration()))));
            lines.addAll(active.owner() == null ? lang.lines(BoostersMessages.FROM_SERVER)
                : lang.lines(BoostersMessages.FROM, Arg.text("name", this.names.name(active.owner()))));
        }
        lines.add(Component.empty());
        List<Booster> waiting = view.waiting();
        if (waiting.isEmpty()) {
            lines.addAll(lang.lines(BoostersMessages.QUEUE_EMPTY));
            return lines;
        }
        lines.addAll(lang.lines(BoostersMessages.QUEUE_HEADER));
        int shown = Math.min(s.shown(), waiting.size());
        for (int i = 0; i < shown; i++) {
            Booster booster = waiting.get(i);
            Arg position = Arg.number("position", i + 1);
            Arg percent = Arg.number("percent", this.service.paid(booster));
            Arg time = Arg.time("time", booster.left());
            lines.addAll(booster.owner() == null
                ? lang.lines(BoostersMessages.QUEUE_LINE_SERVER, position, percent, time)
                : lang.lines(BoostersMessages.QUEUE_LINE, position, percent, time, Arg.text("name", this.names.name(booster.owner()))));
        }
        if (waiting.size() > shown) {
            lines.addAll(lang.lines(BoostersMessages.QUEUE_MORE, Arg.number("count", waiting.size() - shown)));
        }
        return lines;
    }

    // ------------------------------------------------------------------ /sift booster

    private int start(CommandContext<CommandSourceStack> ctx, String reasonInput) {
        CommandSender sender = ctx.getSource().getSender();
        int percent = IntegerArgumentType.getInteger(ctx, "percent");
        String input = StringArgumentType.getString(ctx, "length");
        Duration duration;
        try {
            duration = Durations.parse(input);
        } catch (IllegalArgumentException | ArithmeticException e) {
            this.services.messenger().chat(sender, BoostersMessages.ADMIN_BAD_DURATION_INPUT, Arg.text("input", input));
            return CommandSupport.OK;
        }
        String reason = reasonInput == null || reasonInput.isBlank() ? null : reasonInput.strip();
        if (reason != null && reason.length() > REASON_MAX) {
            reason = reason.substring(0, REASON_MAX);
        }
        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        String actor = owner == null ? "console" : owner.toString();
        BoosterService.Started started = this.service.start(percent, duration, owner, reason, actor);
        BoostersSettings s = this.settings.get();
        if (started.problem() != null) {
            switch (started.problem()) {
                case "bad_percent" -> this.services.messenger().chat(sender, BoostersMessages.ADMIN_BAD_PERCENT,
                    Arg.number("max", s.maxPercent()));
                case "bad_duration" -> this.services.messenger().chat(sender, BoostersMessages.ADMIN_BAD_DURATION,
                    Arg.time("min", s.minDuration()), Arg.time("max", s.maxDuration()));
                case "queue_full" -> this.services.messenger().chat(sender, BoostersMessages.ADMIN_QUEUE_FULL,
                    Arg.number("count", this.service.view().waiting().size()));
                default -> this.services.messenger().chat(sender, BoostersMessages.ADMIN_FAILED,
                    Arg.text("reason", started.problem().replace('_', ' ')));
            }
            return CommandSupport.OK;
        }
        Booster booster = started.booster();
        if (booster.state() == Booster.State.ACTIVE) {
            this.services.messenger().chat(sender, BoostersMessages.ADMIN_STARTED, Arg.number("id", booster.id()),
                Arg.number("percent", this.service.paid(booster)), Arg.time("time", booster.duration()));
        } else {
            this.services.messenger().chat(sender, BoostersMessages.ADMIN_QUEUED, Arg.number("id", booster.id()),
                Arg.number("percent", this.service.paid(booster)), Arg.time("time", booster.duration()),
                Arg.number("position", Math.max(1, this.service.position(booster.id()))));
        }
        this.services.audit().record(actor, "booster.start", null, "#" + booster.id() + " +" + booster.percent() + "% for "
            + Durations.format(booster.duration()) + (reason == null ? "" : ": " + reason));
        return CommandSupport.OK;
    }

    private int stop(CommandSender sender, Long id) {
        Optional<Booster> stopped = this.service.stop(id);
        if (stopped.isEmpty()) {
            if (id == null) {
                this.services.messenger().chat(sender, BoostersMessages.ADMIN_NONE_RUNNING);
            } else {
                this.services.messenger().chat(sender, BoostersMessages.ADMIN_UNKNOWN, Arg.number("id", id));
            }
            return CommandSupport.OK;
        }
        Booster booster = stopped.get();
        boolean wasRunning = booster.started() > 0;
        this.services.messenger().chat(sender, wasRunning ? BoostersMessages.ADMIN_STOPPED : BoostersMessages.ADMIN_REMOVED,
            Arg.number("id", booster.id()), Arg.number("percent", this.service.paid(booster)));
        String actor = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        this.services.audit().record(actor, "booster.stop", booster.owner() == null ? null : booster.owner().toString(),
            "#" + booster.id() + " +" + booster.percent() + "%" + (booster.ref() == null ? "" : " (store " + booster.ref() + ")")
                + (wasRunning ? ", " + Durations.format(booster.left()) + " left" : ", was waiting"));
        return CommandSupport.OK;
    }

    private int list(CommandSender sender) {
        Lang lang = this.services.lang();
        BoostersSettings s = this.settings.get();
        BoosterService.View view = this.service.view();
        int count = view.waiting().size() + (view.active() == null ? 0 : 1);
        if (count == 0) {
            this.services.messenger().chat(sender, BoostersMessages.LIST_EMPTY);
            return CommandSupport.OK;
        }
        this.services.messenger().chat(sender, BoostersMessages.LIST_HEADER, Arg.number("count", count), Arg.number("max", s.maxPercent()));
        Booster active = view.active();
        if (active != null) {
            this.services.messenger().chat(sender, BoostersMessages.LIST_ACTIVE, Arg.number("id", active.id()),
                Arg.number("percent", this.service.paid(active)), Arg.time("left", this.service.left()),
                Arg.time("length", active.duration()), Arg.text("name", owner(active)), Arg.component("source", source(lang, active)),
                Arg.component("capped", capped(lang, active)));
        }
        List<Booster> waiting = view.waiting();
        for (int i = 0; i < waiting.size(); i++) {
            Booster booster = waiting.get(i);
            this.services.messenger().chat(sender, BoostersMessages.LIST_WAITING, Arg.number("id", booster.id()),
                Arg.number("percent", this.service.paid(booster)), Arg.number("position", i + 1), Arg.time("length", booster.left()),
                Arg.text("name", owner(booster)), Arg.component("source", source(lang, booster)),
                Arg.component("capped", capped(lang, booster)));
        }
        return CommandSupport.OK;
    }

    /** For staff: a booster bought for more than it pays now (sell.max-percent was lowered), or nothing. */
    private Component capped(Lang lang, Booster booster) {
        int paid = this.service.paid(booster);
        return paid < booster.percent() ? lang.get(BoostersMessages.LIST_CAPPED, Arg.number("percent", booster.percent()))
            : Component.empty();
    }

    private String owner(Booster booster) {
        return booster.owner() == null ? this.services.lang().plain(BoostersMessages.SERVER) : this.names.name(booster.owner());
    }

    private static Component source(Lang lang, Booster booster) {
        if (booster.source() == Booster.Source.STORE) {
            return lang.get(BoostersMessages.SOURCE_STORE, Arg.text("ref", booster.ref() == null ? "-" : booster.ref()));
        }
        return booster.reason() == null ? lang.get(BoostersMessages.SOURCE_STAFF)
            : lang.get(BoostersMessages.SOURCE_STAFF_REASON, Arg.text("reason", booster.reason()));
    }
}
