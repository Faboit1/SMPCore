package net.siftvanilla.siftcore.feature.stats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;

/**
 * The stats and leaderboard dialogs in the dialog style, built from plain values so they can be tested:
 * <ul>
 *   <li>the stats page is one button per stat, "Kills: 4" with the value in its colour, whose click opens that stat's
 *       leaderboard (the player's place on it in the tooltip);</li>
 *   <li>a leaderboard is one or two short lines (your place, how many are listed and when it was updated), then every
 *       listed player as a button, "1. Alex 120", that opens their stats; no pages, the dialog scrolls (a board keeps
 *       at most its {@code leaderboards.size}, 100);</li>
 *   <li>the picker is a button per leaderboard.</li>
 * </ul>
 */
final class StatsPages {

    private StatsPages() {
    }

    /** One stat on the stats page: its label, its value (coloured or not) and the leaderboard it opens. */
    record Stat(MessageKey label, Component value, Board board) {
    }

    /**
     * The stats of a player, in order.
     *
     * @param balance their balance, or null when they keep it from the viewer
     */
    static List<Stat> stats(Lang lang, StatsSnapshot stats, Long balance) {
        List<Stat> list = new ArrayList<>(9);
        list.add(new Stat(StatsMessages.STAT_LABEL_KILLS, number(stats.kills()), Board.KILLS));
        list.add(new Stat(StatsMessages.STAT_LABEL_DEATHS, number(stats.deaths()), Board.DEATHS));
        list.add(new Stat(StatsMessages.STAT_LABEL_KDR, Component.text(Kdr.format(stats.kills(), stats.deaths())), Board.KDR));
        list.add(new Stat(StatsMessages.STAT_LABEL_STREAK, lang.get(StatsMessages.VIEW_STREAK_VALUE,
            Arg.text("streak", Lang.number(stats.streak())), Arg.text("best", Lang.number(stats.bestStreak()))), Board.STREAK));
        list.add(new Stat(StatsMessages.STAT_LABEL_PLAYTIME, Component.text(Durations.format(Duration.ofSeconds(stats.playtime()))),
            Board.PLAYTIME));
        list.add(new Stat(StatsMessages.STAT_LABEL_MOBS, number(stats.mobs()), Board.MOBS));
        list.add(new Stat(StatsMessages.STAT_LABEL_BLOCKS, number(stats.blocks()), Board.BLOCKS));
        list.add(new Stat(StatsMessages.STAT_LABEL_EARNED, lang.moneyComponent(stats.earned()), Board.EARNED));
        list.add(new Stat(StatsMessages.STAT_LABEL_BALANCE, balance == null ? lang.get(StatsMessages.BALANCE_HIDDEN)
            : lang.moneyComponent(balance), Board.MONEY));
        return list;
    }

    private static Component number(long value) {
        return Component.text(Lang.number(value));
    }

    /**
     * The stats page.
     *
     * @param place  the shown player's place on a board (0 when not listed)
     * @param open   what a stat's button does when the viewer may see the leaderboards, null when they may not (the
     *               button then shows the page again)
     */
    static View statsView(Lang lang, Templates templates, Component title, List<Stat> stats, Function<Board, Integer> place,
                          Function<Board, Button.Handler> open, Button.Handler stay, Button.Handler back) {
        List<Button> buttons = new ArrayList<>(stats.size());
        for (Stat stat : stats) {
            Component tooltip = null;
            Button.Handler handler = stay;
            if (open != null) {
                tooltip = Templates.lines(List.of(placeLine(lang, place.apply(stat.board())),
                    lang.get(StatsMessages.VIEW_OPEN_BOARD, Arg.component("board", lang.get(StatsMessages.title(stat.board()))))));
                handler = open.apply(stat.board());
            }
            buttons.add(templates.choiceButton(lang.get(stat.label()), stat.value(), tooltip, handler));
        }
        return templates.column(title, buttons, back);
    }

    /** "Number 3 on the leaderboard", or "Not on the leaderboard yet". */
    static Component placeLine(Lang lang, int rank) {
        return rank > 0 ? lang.get(StatsMessages.TOP_PLACE, Arg.text("rank", Lang.number(rank))) : lang.get(StatsMessages.TOP_NOT_PLACED);
    }

    /** The leaderboard picker: a button per board, its title and the viewer's place in the tooltip. */
    static View pickerView(Lang lang, Templates templates, Function<Board, Integer> place, Function<Board, Button.Handler> open,
                           Button.Handler back) {
        List<Button> buttons = new ArrayList<>(Board.values().length);
        for (Board board : Board.values()) {
            buttons.add(Button.of(lang.get(StatsMessages.button(board)),
                Templates.lines(List.of(lang.get(StatsMessages.title(board)), placeLine(lang, place.apply(board)))), open.apply(board)));
        }
        return templates.grid(lang.get(StatsMessages.PICKER_TITLE), buttons, back);
    }

    /**
     * A whole leaderboard.
     *
     * @param value       a board value as a placeholder named {@code value}
     * @param kdrMinKills kills needed to be listed on the KDR board
     * @param now         the time now (epoch millis), for "updated 30s ago"
     * @param open        what an entry's button does (their stats), or null when the viewer may not see others' stats
     */
    static View boardView(Lang lang, Templates templates, Board board, Leaderboard snapshot, UUID viewer, int kdrMinKills, long now,
                          Function<Leaderboard.Entry, Arg> value, Function<Leaderboard.Entry, Button.Handler> open,
                          Button.Handler stay, Button.Handler back) {
        List<Component> lines = new ArrayList<>(2);
        Leaderboard.Entry own = snapshot.entryOf(viewer).orElse(null);
        if (own != null) {
            lines.add(lang.get(StatsMessages.TOP_YOU, Arg.text("rank", Lang.number(own.rank())), value.apply(own)));
        } else if (board == Board.KDR && kdrMinKills > 1) {
            // Only players with kills are ranked anyway, so a rule of 0 or 1 says nothing new.
            lines.add(lang.get(StatsMessages.TOP_KDR_RULE, Arg.text("kills", Lang.number(kdrMinKills))));
        } else if (snapshot.size() > 0) {
            lines.add(lang.get(StatsMessages.TOP_NOT_LISTED));
        }
        if (snapshot.builtAt() == 0) {
            lines.add(lang.get(StatsMessages.TOP_NOT_READY));
        } else if (snapshot.size() == 0) {
            lines.add(lang.get(StatsMessages.TOP_EMPTY));
        } else {
            lines.add(lang.get(StatsMessages.TOP_SHOWN, Arg.text("count", Lang.number(snapshot.size())),
                Arg.text("time", Durations.format(Duration.ofMillis(Math.max(0, now - snapshot.builtAt()))))));
        }
        List<Button> buttons = new ArrayList<>(snapshot.size());
        for (Leaderboard.Entry entry : snapshot.entries()) {
            Arg rank = Arg.text("rank", Lang.number(entry.rank()));
            Arg name = Arg.text("name", entry.name());
            Component label = lang.get(entry.uuid().equals(viewer) ? StatsMessages.TOP_ENTRY_YOU : StatsMessages.TOP_ENTRY, rank, name,
                value.apply(entry));
            Button.Handler handler = open == null ? stay : open.apply(entry);
            Component tooltip = open == null ? null : lang.get(StatsMessages.TOP_ENTRY_TOOLTIP, name);
            buttons.add(Button.of(label, tooltip, handler));
        }
        return templates.column(lang.get(StatsMessages.title(board)), lines, buttons, back);
    }
}
