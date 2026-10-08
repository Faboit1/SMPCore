package net.siftvanilla.siftcore.feature.stats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Everything players see: the stats dialog, the leaderboard dialogs, the leaderboard picker, and the chat versions
 * for the console and /playtime. Dialogs are rebuilt from current data every time they open, never reused.
 */
final class StatsViews {

    static final String TOP_PERMISSION = "siftcore.command.top";
    private static final int BOARD_BUTTON_WIDTH = 100;

    private final Services services;
    private final StatsStore store;
    private final Leaderboards boards;
    private final Setting<StatsSettings> settings;

    StatsViews(Services services, StatsStore store, Leaderboards boards, Setting<StatsSettings> settings) {
        this.services = services;
        this.store = store;
        this.boards = boards;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ stats

    /** Opens a player's stats for the viewer; offline players are loaded first. {@code back} null shows Close. */
    void openStats(Player viewer, UUID target, Button.Handler back) {
        withStats(target, viewer, stats -> showStats(viewer, target, stats, back),
            () -> this.services.messenger().send(viewer, StatsMessages.LOAD_FAILED));
    }

    private void showStats(Player viewer, UUID target, StatsSnapshot stats, Button.Handler back) {
        Lang lang = this.services.lang();
        boolean self = viewer.getUniqueId().equals(target);
        Component title = self
            ? lang.get(StatsMessages.VIEW_TITLE_SELF)
            : lang.get(StatsMessages.VIEW_TITLE_OTHER, Arg.text("name", this.services.directory().name(target)));
        List<Component> body = new ArrayList<>(lang.lines(StatsMessages.VIEW_BODY, statArgs(target, stats)));
        List<Button> buttons = new ArrayList<>();
        if (viewer.hasPermission(TOP_PERMISSION)) {
            body.add(Component.empty());
            body.addAll(lang.lines(StatsMessages.VIEW_BOARDS));
            for (Board board : Board.values()) {
                buttons.add(Button.of(lang.get(StatsMessages.button(board)),
                    s -> openBoard(s.player(), board, 1, again -> openStats(again.player(), target, back))).width(BOARD_BUTTON_WIDTH));
            }
        }
        this.services.dialogs().show(viewer, this.services.templates().list(title, body, buttons, 3, back));
    }

    /** Prints a player's stats in chat (console). */
    void printStats(CommandSender sender, UUID target) {
        withStats(target, null, stats -> {
            Arg[] stat = statArgs(target, stats);
            Arg[] args = new Arg[stat.length + 1];
            args[0] = Arg.text("name", this.services.directory().name(target));
            System.arraycopy(stat, 0, args, 1, stat.length);
            this.services.messenger().chat(sender, StatsMessages.VIEW_CONSOLE, args);
        }, () -> this.services.messenger().chat(sender, StatsMessages.LOAD_FAILED));
    }

    /** Tells the sender how long the target played (active time). */
    void sendPlaytime(CommandSender sender, UUID target) {
        Player viewer = sender instanceof Player player ? player : null;
        withStats(target, viewer, stats -> {
            Arg time = Arg.time("time", Duration.ofSeconds(stats.playtime()));
            if (viewer != null && viewer.getUniqueId().equals(target)) {
                this.services.messenger().chat(sender, StatsMessages.PLAYTIME_SELF, time);
            } else {
                this.services.messenger().chat(sender, StatsMessages.PLAYTIME_OTHER,
                    Arg.text("name", this.services.directory().name(target)), time);
            }
        }, () -> this.services.messenger().send(sender, StatsMessages.LOAD_FAILED));
    }

    /**
     * Runs {@code show} with the target's current stats: right away when they are in memory, otherwise after
     * loading them (on the viewer's thread when there is a viewer). {@code failed} runs if they cannot be loaded.
     */
    private void withStats(UUID target, Player viewer, Consumer<StatsSnapshot> show, Runnable failed) {
        StatsSnapshot current = this.store.current(target);
        if (current != null) {
            show.accept(current);
            return;
        }
        if (viewer != null) {
            this.services.messenger().send(viewer, CoreMessages.LOADING);
        }
        this.store.load(target).whenComplete((loaded, error) -> {
            Runnable next = () -> {
                if (error != null || loaded == null) {
                    failed.run();
                    return;
                }
                StatsSnapshot latest = this.store.current(target);
                show.accept(latest == null ? loaded : latest);
            };
            if (viewer == null) {
                next.run();
            } else {
                this.services.scheduler().entity(viewer, next, null);
            }
        });
    }

    private Arg[] statArgs(UUID target, StatsSnapshot stats) {
        return new Arg[] {
            Arg.number("kills", stats.kills()),
            Arg.number("deaths", stats.deaths()),
            kdr("kdr", stats.kills(), stats.deaths()),
            Arg.number("streak", stats.streak()),
            Arg.number("best", stats.bestStreak()),
            Arg.time("playtime", Duration.ofSeconds(stats.playtime())),
            Arg.number("mobs", stats.mobs()),
            Arg.number("blocks", stats.blocks()),
            Arg.money("earned", stats.earned()),
            Arg.money("balance", this.services.ledger().balance(target, Currency.MONEY))
        };
    }

    // ------------------------------------------------------------------ leaderboards

    /** The leaderboard picker (/top without arguments). */
    void openPicker(Player viewer, Button.Handler back) {
        Lang lang = this.services.lang();
        List<Button> buttons = new ArrayList<>();
        for (Board board : Board.values()) {
            buttons.add(Button.of(lang.get(StatsMessages.button(board)),
                s -> openBoard(s.player(), board, 1, again -> openPicker(again.player(), back))).width(BOARD_BUTTON_WIDTH));
        }
        this.services.dialogs().show(viewer, this.services.templates().list(lang.get(StatsMessages.PICKER_TITLE),
            lang.lines(StatsMessages.PICKER_BODY), buttons, 3, back));
    }

    /** One page of a leaderboard. {@code back} null shows Close. */
    void openBoard(Player viewer, Board board, int page, Button.Handler back) {
        Lang lang = this.services.lang();
        Leaderboard snapshot = this.boards.board(board);
        int pageSize = this.settings.get().pageSize();
        int pages = snapshot.pages(pageSize);
        int current = Math.clamp(page, 1, pages);
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(StatsMessages.TOP_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        List<Leaderboard.Entry> entries = snapshot.page(current, pageSize);
        if (entries.isEmpty()) {
            lines.add(lang.get(snapshot.builtAt() == 0 ? StatsMessages.TOP_NOT_READY : StatsMessages.TOP_EMPTY));
        }
        for (Leaderboard.Entry entry : entries) {
            lines.add(lang.get(StatsMessages.TOP_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                value(board, "value", entry.value(), entry.secondary())));
        }
        int minKills = this.settings.get().kdrMinKills();
        if (board == Board.KDR && minKills > 1) {
            // Only players with kills are ranked anyway, so a rule of 0 or 1 says nothing new.
            lines.add(lang.get(StatsMessages.TOP_KDR_RULE, Arg.number("kills", minKills)));
        }
        lines.add(Component.empty());
        Optional<Leaderboard.Entry> own = snapshot.entryOf(viewer.getUniqueId());
        lines.add(own.isPresent()
            ? lang.get(StatsMessages.TOP_YOU, Arg.number("rank", own.get().rank()), value(board, "value", own.get().value(), own.get().secondary()))
            : lang.get(StatsMessages.TOP_NOT_LISTED));
        if (snapshot.builtAt() > 0) {
            lines.add(lang.get(StatsMessages.TOP_UPDATED,
                Arg.time("time", Duration.ofMillis(Math.max(0, System.currentTimeMillis() - snapshot.builtAt())))));
        }
        List<Button> buttons = new ArrayList<>(2);
        if (current > 1) {
            buttons.add(Button.of(lang.get(StatsMessages.TOP_PREVIOUS), s -> openBoard(s.player(), board, current - 1, back)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(StatsMessages.TOP_NEXT), s -> openBoard(s.player(), board, current + 1, back)).width(150));
        }
        this.services.dialogs().show(viewer, this.services.templates().list(lang.get(StatsMessages.title(board)), lines,
            buttons, 2, back));
    }

    /** One page of a leaderboard in chat (console). */
    void printBoard(CommandSender sender, Board board, int page) {
        var messenger = this.services.messenger();
        Lang lang = this.services.lang();
        Leaderboard snapshot = this.boards.board(board);
        int pageSize = this.settings.get().pageSize();
        int pages = snapshot.pages(pageSize);
        int current = Math.clamp(page, 1, pages);
        messenger.chat(sender, StatsMessages.TOP_CONSOLE_HEADER, Arg.component("title", lang.get(StatsMessages.title(board))),
            Arg.number("page", current), Arg.number("pages", pages));
        List<Leaderboard.Entry> entries = snapshot.page(current, pageSize);
        if (entries.isEmpty()) {
            messenger.chat(sender, StatsMessages.TOP_CONSOLE_EMPTY);
        }
        for (Leaderboard.Entry entry : entries) {
            messenger.chat(sender, StatsMessages.TOP_CONSOLE_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                value(board, "value", entry.value(), entry.secondary()));
        }
        int minKills = this.settings.get().kdrMinKills();
        if (board == Board.KDR && minKills > 1) {
            messenger.chat(sender, StatsMessages.TOP_KDR_RULE, Arg.number("kills", minKills));
        }
    }

    /** The comma separated board ids, for usage messages. */
    static String boardIds() {
        StringJoiner joiner = new StringJoiner(", ");
        for (Board board : Board.values()) {
            joiner.add(board.id());
        }
        return joiner.toString();
    }

    // ------------------------------------------------------------------ values

    /** A board value as a typed placeholder. For KDR, {@code value} is kills and {@code secondary} deaths. */
    Arg value(Board board, String name, long value, long secondary) {
        return switch (board.format()) {
            case NUMBER -> Arg.number(name, value);
            case KDR -> kdr(name, value, secondary);
            case DURATION -> Arg.time(name, Duration.ofSeconds(value));
            case MONEY -> Arg.money(name, value);
        };
    }

    /** A board value as plain text (placeholders). */
    String plain(Board board, long value, long secondary) {
        return switch (board.format()) {
            case NUMBER -> Lang.number(value);
            case KDR -> Kdr.format(value, secondary);
            case DURATION -> Durations.format(Duration.ofSeconds(value));
            case MONEY -> this.services.money().get().format(value);
        };
    }

    /** A counter value as a typed placeholder (staff messages). */
    static Arg counterValue(Counter counter, String name, long value) {
        return switch (counter) {
            case PLAYTIME -> Arg.time(name, Duration.ofSeconds(value));
            case EARNED -> Arg.money(name, value);
            default -> Arg.number(name, value);
        };
    }

    /** A KDR in the primary colour with exactly two decimals, like numbers are shown. */
    private Arg kdr(String name, long kills, long deaths) {
        return Arg.component(name, Component.text(Kdr.format(kills, deaths), this.services.lang().style().palette().primary()));
    }
}
