package net.siftvanilla.siftcore.feature.stats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Everything players see: the stats dialog, the leaderboard dialogs, the leaderboard picker, and the chat versions
 * for the console and /playtime. Dialogs are rebuilt from current data every time they open, never reused. Another
 * player's balance shows only when their {@code balance-privacy} allows the viewer (staff with
 * {@value #BALANCE_BYPASS} and the console always see it).
 */
final class StatsViews {

    static final String TOP_PERMISSION = "siftcore.command.top";
    /** Staff who manage balances see every balance. */
    static final String BALANCE_BYPASS = "siftcore.admin.eco";

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
        withStats(target, viewer, stats -> withBalanceVisibility(viewer, target, visible -> showStats(viewer, target, stats, visible, back)),
            () -> this.services.messenger().send(viewer, StatsMessages.LOAD_FAILED));
    }

    /**
     * Runs {@code then} on the viewer's thread with whether they may see the target's balance: always their own, and
     * staff with {@value #BALANCE_BYPASS}; otherwise the target's {@code balance-privacy} (read from the database when
     * they are offline) decides. A failed read keeps the balance hidden.
     */
    private void withBalanceVisibility(Player viewer, UUID target, Consumer<Boolean> then) {
        UUID id = viewer.getUniqueId();
        if (id.equals(target) || viewer.hasPermission(BALANCE_BYPASS)) {
            then.accept(true);
            return;
        }
        Relations relations = this.services.relations();
        CompletableFuture<Audience> audience = this.services.settings().lookup(target, SharedSettings.BALANCE_PRIVACY);
        if (audience.isDone() && !audience.isCompletedExceptionally()) {
            then.accept(balanceVisible(relations, audience.join(), target, id));
            return;
        }
        audience.whenComplete((chosen, error) -> this.services.scheduler().entity(viewer,
            () -> then.accept(error == null && balanceVisible(relations, chosen, target, id)), null));
    }

    /** Whether {@code viewer} may see {@code target}'s balance under the target's choice. */
    static boolean balanceVisible(Relations relations, Audience chosen, UUID target, UUID viewer) {
        return chosen != null && relations.allows(chosen, target, viewer);
    }

    private void showStats(Player viewer, UUID target, StatsSnapshot stats, boolean balance, Button.Handler back) {
        // Often shown after a database read (an offline target, their balance privacy), outside the command's scope:
        // written for the viewer, so the balance and earnings follow their money format either way.
        this.services.lang().viewing(viewer, () -> showStatsNow(viewer, target, stats, balance, back));
    }

    /**
     * The stats page ({@link StatsPages#statsView}): a button per stat showing its value; a click opens that stat's
     * leaderboard (for players who may see them), whose Back returns here.
     */
    private void showStatsNow(Player viewer, UUID target, StatsSnapshot stats, boolean balance, Button.Handler back) {
        Lang lang = this.services.lang();
        boolean self = viewer.getUniqueId().equals(target);
        Component title = self
            ? lang.get(StatsMessages.VIEW_TITLE_SELF)
            : lang.get(StatsMessages.VIEW_TITLE_OTHER, Arg.text("name", this.services.directory().name(target)));
        Long shownBalance = balance ? this.services.ledger().balance(target, Currency.MONEY) : null;
        Function<Board, Button.Handler> open = viewer.hasPermission(TOP_PERMISSION)
            ? board -> s -> openBoard(s.player(), board, again -> openStats(again.player(), target, back))
            : null;
        this.services.dialogs().show(viewer, StatsPages.statsView(lang, this.services.templates(), title,
            StatsPages.stats(lang, stats, shownBalance), board -> this.boards.board(board).rankOf(target), open,
            s -> openStats(s.player(), target, back), back));
    }

    /** Prints a player's stats in chat (console). */
    void printStats(CommandSender sender, UUID target) {
        withStats(target, null, stats -> {
            Arg[] stat = statArgs(target, stats, true);
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
            Arg time = Arg.text("time", Durations.format(Duration.ofSeconds(stats.playtime())));
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

    /** The stat placeholders; {@code balance} false shows "hidden" instead of the balance. */
    private Arg[] statArgs(UUID target, StatsSnapshot stats, boolean balance) {
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
            balance ? Arg.money("balance", this.services.ledger().balance(target, Currency.MONEY))
                : Arg.component("balance", this.services.lang().get(StatsMessages.BALANCE_HIDDEN))
        };
    }

    // ------------------------------------------------------------------ leaderboards

    /** The leaderboard picker (/top without arguments): a button per board. */
    void openPicker(Player viewer, Button.Handler back) {
        UUID id = viewer.getUniqueId();
        this.services.dialogs().show(viewer, StatsPages.pickerView(this.services.lang(), this.services.templates(),
            board -> this.boards.board(board).rankOf(id),
            board -> s -> openBoard(s.player(), board, again -> openPicker(again.player(), back)), back));
    }

    /**
     * A whole leaderboard ({@link StatsPages#boardView}): no pages, every listed player is a button that opens their
     * stats (for viewers who may see other players' stats), whose Back returns here. {@code back} null shows Close.
     */
    void openBoard(Player viewer, Board board, Button.Handler back) {
        Leaderboard snapshot = this.boards.board(board);
        Function<Leaderboard.Entry, Button.Handler> open = viewer.hasPermission(StatsCommands.STATS_OTHERS)
            ? entry -> s -> openStats(s.player(), entry.uuid(), again -> openBoard(again.player(), board, back))
            : null;
        this.services.dialogs().show(viewer, StatsPages.boardView(this.services.lang(), this.services.templates(), board, snapshot,
            viewer.getUniqueId(), this.settings.get().kdrMinKills(), System.currentTimeMillis(),
            entry -> value(board, "value", entry.value(), entry.secondary()), open, s -> openBoard(s.player(), board, back), back));
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

    /**
     * A board value as a placeholder: money in the money colour, everything else as text the lang file colours
     * ({@code <accent><value>}). For KDR, {@code value} is kills and {@code secondary} deaths.
     */
    Arg value(Board board, String name, long value, long secondary) {
        return switch (board.format()) {
            case NUMBER -> Arg.text(name, Lang.number(value));
            case KDR -> Arg.text(name, Kdr.format(value, secondary));
            case DURATION -> Arg.text(name, Durations.format(Duration.ofSeconds(value)));
            case MONEY -> Arg.money(name, value);
        };
    }

    /** A board value as plain text (placeholders): money in the money format of {@code reader} (null: the server's way). */
    String plain(Board board, long value, long secondary, OfflinePlayer reader) {
        return switch (board.format()) {
            case NUMBER -> Lang.number(value);
            case KDR -> Kdr.format(value, secondary);
            case DURATION -> Durations.format(Duration.ofSeconds(value));
            case MONEY -> this.services.lang().moneyFor(reader, value);
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
