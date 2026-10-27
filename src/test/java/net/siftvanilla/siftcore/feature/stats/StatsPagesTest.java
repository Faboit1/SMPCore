package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.MergedLang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The stats and leaderboard dialogs in the dialog style: buttons with coloured values and tooltips, nothing paged, a
 * whole leaderboard in one scrolling dialog with its cap named, and clicks that lead to the leaderboard or a player.
 */
class StatsPagesTest {

    private static Lang lang;
    private static Templates templates;
    private static final Palette PALETTE = Palette.defaults();

    @BeforeAll
    static void load() {
        lang = MergedLang.of(List.of("lang/core.yml", "lang/stats.yml"), CoreMessages.class, StatsMessages.class);
        templates = new Templates(lang);
    }

    private static String plain(Component text) {
        return text == null ? "" : TextStyle.plain(text);
    }

    private static List<String> labels(View view) {
        return view.allButtons().stream().map(button -> plain(button.label())).toList();
    }

    private static Button button(View view, String start) {
        return view.allButtons().stream().filter(button -> plain(button.label()).startsWith(start)).findFirst()
            .orElseThrow(() -> new AssertionError("no '" + start + "' in " + labels(view)));
    }

    /** The colour of the last coloured part of a label (the value of "Label: value"). */
    private static TextColor valueColor(Component label) {
        TextColor[] found = new TextColor[1];
        visit(label, null, found);
        return found[0];
    }

    private static void visit(Component component, TextColor inherited, TextColor[] found) {
        TextColor color = component.color() != null ? component.color() : inherited;
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isBlank() && color != null) {
            found[0] = color;
        }
        for (Component child : component.children()) {
            visit(child, color, found);
        }
    }

    private static final StatsSnapshot STATS = new StatsSnapshot(1234, 56, 3, 17, 999, 120_000, 2_500, 108_000);

    @Test
    void theStatsPageIsAButtonPerStatWithItsValue() {
        List<Board> opened = new ArrayList<>();
        View view = StatsPages.statsView(lang, templates, Component.text("Your stats"), StatsPages.stats(lang, STATS, 75_000L),
            board -> board == Board.KILLS ? 3 : 0, board -> s -> opened.add(board), s -> { }, null);
        assertTrue(view.body().isEmpty(), "nothing above the buttons: " + view.body());
        assertEquals(List.of("Kills: 1,234", "Deaths: 56", "KDR: 22.04", "Streak: 3 (best 17)", "Playtime: 1d 6h", "Mobs killed: 999",
            "Blocks mined: 120,000", "Money earned: $2,500", "Balance: $75,000", "Close"), labels(view));
        assertEquals(PALETTE.accent(), valueColor(button(view, "Kills").label()), "numbers in the accent colour");
        assertEquals(PALETTE.money(), valueColor(button(view, "Balance").label()), "money in the money colour");
        Button kills = button(view, "Kills");
        assertTrue(plain(kills.tooltip()).contains("Number 3 on this leaderboard"), plain(kills.tooltip()));
        assertTrue(plain(kills.tooltip()).contains("Click for the leaderboard"), plain(kills.tooltip()));
        assertTrue(plain(button(view, "Deaths").tooltip()).contains("Not on this leaderboard yet"));
        kills.handler().handle(null);
        button(view, "Money earned").handler().handle(null);
        assertEquals(List.of(Board.KILLS, Board.EARNED), opened, "a stat opens its own leaderboard");
    }

    @Test
    void aHiddenBalanceAndNoLeaderboardAccess() {
        List<String> stayed = new ArrayList<>();
        View view = StatsPages.statsView(lang, templates, Component.text("Alex's stats"), StatsPages.stats(lang, STATS, null),
            board -> 0, null, s -> stayed.add("again"), s -> { });
        Button balance = button(view, "Balance");
        assertEquals("Balance: hidden", plain(balance.label()));
        assertEquals(PALETTE.secondary(), valueColor(balance.label()), "hidden stays gray");
        assertNull(balance.tooltip(), "no leaderboard to promise");
        balance.handler().handle(null);
        assertEquals(List.of("again"), stayed, "the button only shows the page again");
        assertEquals("Back", labels(view).getLast());
    }

    private static Leaderboard board(Board board, int players, long builtAt) {
        List<Leaderboard.Row> rows = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            rows.add(new Leaderboard.Row(new UUID(0, i + 1), 1000 - i, 0));
        }
        return Leaderboard.build(board, rows, 100, id -> "P" + id.getLeastSignificantBits(), builtAt);
    }

    private static Arg value(Leaderboard.Entry entry) {
        return Arg.text("value", Lang.number(entry.value()));
    }

    @Test
    void aWholeLeaderboardInOneDialogWithItsCapNamed() {
        Leaderboard kills = board(Board.KILLS, 150, 1_000_000);
        UUID viewer = new UUID(0, 3);
        List<UUID> opened = new ArrayList<>();
        View view = StatsPages.boardView(lang, templates, Board.KILLS, kills, viewer, 25, 1_030_000, StatsPagesTest::value,
            entry -> s -> opened.add(entry.uuid()), s -> { }, null);
        assertEquals("Most kills", plain(view.title()));
        String body = plain(view.body().getFirst() instanceof net.siftvanilla.siftcore.ui.dialog.Body.Text text ? text.text() : null);
        assertTrue(body.contains("You are number 3 with 998."), body);
        assertTrue(body.contains("Top 100, updated 30s ago."), body);
        assertTrue(!body.contains("Page"), "no pages: " + body);
        assertEquals(101, view.allButtons().size(), "every listed player and Close");
        assertTrue(labels(view).stream().noneMatch(label -> label.contains("Next") || label.contains("Previous")), labels(view).toString());
        assertEquals("1. P1 1,000", labels(view).getFirst());
        assertEquals("100. P100 901", labels(view).get(99));
        Button own = button(view, "3. P3");
        assertEquals(PALETTE.accent(), valueColor(own.label()), "your own line stands out");
        assertTrue(plain(own.tooltip()).contains("Click for P3's stats"));
        button(view, "2. P2").handler().handle(null);
        assertEquals(List.of(new UUID(0, 2)), opened);
    }

    @Test
    void notListedTheKdrRuleAnEmptyAndAnUnbuiltBoard() {
        UUID stranger = UUID.randomUUID();
        View kdr = StatsPages.boardView(lang, templates, Board.KDR, board(Board.KDR, 2, 5_000), stranger, 25, 6_000,
            StatsPagesTest::value, null, s -> { }, s -> { });
        String kdrBody = plain(((net.siftvanilla.siftcore.ui.dialog.Body.Text) kdr.body().getFirst()).text());
        assertTrue(kdrBody.contains("Players need 25 kills to be listed."), kdrBody);
        assertNull(button(kdr, "1. P1").tooltip(), "no stats to open without permission");
        View kills = StatsPages.boardView(lang, templates, Board.KILLS, board(Board.KILLS, 2, 5_000), stranger, 25, 6_000,
            StatsPagesTest::value, null, s -> { }, s -> { });
        assertTrue(plain(((net.siftvanilla.siftcore.ui.dialog.Body.Text) kills.body().getFirst()).text())
            .contains("You are not on this leaderboard yet."));
        View empty = StatsPages.boardView(lang, templates, Board.MOBS, board(Board.MOBS, 0, 5_000), stranger, 25, 6_000,
            StatsPagesTest::value, null, s -> { }, s -> { });
        assertEquals("Nobody is on this leaderboard yet.", plain(((net.siftvanilla.siftcore.ui.dialog.Body.Text) empty.body().getFirst()).text()));
        assertEquals(List.of("Back"), labels(empty));
        View building = StatsPages.boardView(lang, templates, Board.MOBS, Leaderboard.empty(Board.MOBS), stranger, 25, 6_000,
            StatsPagesTest::value, null, s -> { }, s -> { });
        assertTrue(plain(((net.siftvanilla.siftcore.ui.dialog.Body.Text) building.body().getFirst()).text()).contains("still being built"));
    }

    @Test
    void thePickerIsAButtonPerBoard() {
        List<Board> opened = new ArrayList<>();
        View view = StatsPages.pickerView(lang, templates, board -> board == Board.PLAYTIME ? 7 : 0, board -> s -> opened.add(board), null);
        assertTrue(view.body().isEmpty());
        assertEquals(Board.values().length + 1, view.allButtons().size());
        Button playtime = button(view, "Playtime");
        assertTrue(plain(playtime.tooltip()).contains("Most playtime"), plain(playtime.tooltip()));
        assertTrue(plain(playtime.tooltip()).contains("Number 7"), plain(playtime.tooltip()));
        assertNotNull(button(view, "Balance"));
        playtime.handler().handle(null);
        assertEquals(List.of(Board.PLAYTIME), opened);
    }
}
