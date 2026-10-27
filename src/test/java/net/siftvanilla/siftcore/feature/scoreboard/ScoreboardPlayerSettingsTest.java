package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The display settings of the scoreboard: the sidebar switch and layout (group, order, when they are offered), the
 * layouts from the config, the /sidebar decisions, and how a hidden rank is shown.
 */
class ScoreboardPlayerSettingsTest {

    private static ScoreboardSettings parse(YamlConfiguration yaml, List<String> problems) {
        ConfigReader reader = new ConfigReader("features/scoreboard.yml", yaml);
        ScoreboardSettings settings = ScoreboardSettings.parse(reader);
        reader.problems().forEach(problem -> problems.add(problem.toString()));
        return settings;
    }

    private static ScoreboardSettings shipped() {
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(ScoreboardTestSupport.yaml("features/scoreboard.yml"), problems);
        assertEquals(List.of(), problems);
        return settings;
    }

    private static PlayerSettings registered(AtomicReference<ScoreboardSettings> current) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        ScoreboardFeature.registerSettings(settings, current::get, player -> { });
        return settings;
    }

    private static List<String> offered(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.DISPLAY.id()).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    // ------------------------------------------------------------------ the layouts in the config

    @Test
    void theShippedConfigHasAMoneyViewAndFightStats() {
        ScoreboardSettings settings = shipped();
        assertEquals(ScoreboardSettings.DEFAULT_LINES, settings.lines(SidebarLayout.FULL));
        assertEquals(ScoreboardSettings.DEFAULT_COMPACT, settings.lines(SidebarLayout.COMPACT));
        assertEquals(ScoreboardSettings.DEFAULT_COMBAT, settings.lines(SidebarLayout.COMBAT));
        assertEquals(settings.lines(), settings.lines(null), "no layout reads as the full sidebar");
        assertTrue(settings.offers(SidebarLayout.COMPACT) && settings.offers(SidebarLayout.COMBAT) && settings.offersChoice());
        assertTrue(ScoreboardSettings.DEFAULT_COMPACT.contains("balance") && !ScoreboardSettings.DEFAULT_COMPACT.contains("kills"),
            "the compact layout is a short money view");
        assertTrue(ScoreboardSettings.DEFAULT_COMBAT.containsAll(List.of("kills", "deaths", "kdr", "streak")),
            "the combat layout shows fight stats");
        assertTrue(ScoreboardSettings.DEFAULT_COMPACT.size() < ScoreboardSettings.DEFAULT_LINES.size());
    }

    @Test
    void anEmptyLayoutIsNotOfferedAndReadsAsTheFullSidebar() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.layouts.compact", List.of());
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(yaml, problems);
        assertEquals(List.of(), problems, "[] is how a layout is turned off");
        assertFalse(settings.offers(SidebarLayout.COMPACT));
        assertEquals(settings.lines(), settings.lines(SidebarLayout.COMPACT), "players who picked it see everything");
        assertTrue(settings.offersChoice(), "fight stats are still there");

        yaml.set("sidebar.layouts.combat", List.of());
        assertFalse(parse(yaml, problems).offersChoice(), "nothing left to choose");
        assertEquals(List.of(), problems);
    }

    @Test
    void layoutMistakesAreReportedAndFallBackToThatLayoutsLines() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.layouts.combat", List.of("kills", "kils"));
        yaml.set("sidebar.layouts.huge", List.of("balance"));
        yaml.set("sidebar.layouts.compact", List.of(" Balance ", "BLANK", "website"));
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(yaml, problems);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("layouts.combat") && p.contains("'kils'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("layouts.huge") && p.contains("not a sidebar layout")), problems.toString());
        assertEquals(ScoreboardSettings.DEFAULT_COMBAT, settings.lines(SidebarLayout.COMBAT));
        assertEquals(List.of("balance", "blank", "website"), settings.lines(SidebarLayout.COMPACT), "names are read like sidebar.lines");
    }

    @Test
    void anotherPluginsSidebarKeepsTheLayoutsForLater() {
        ScoreboardSettings settings = shipped();
        ScoreboardSettings tab = settings.effective(settings.yielded("TAB"::equals));
        assertFalse(tab.sidebarEnabled());
        assertEquals(settings.layouts(), tab.layouts());
    }

    // ------------------------------------------------------------------ the settings

    @Test
    void theChoiceKeepsItsIdsAndHasThreeOptions() {
        Choice<SidebarLayout> layout = ScoreboardFeature.LAYOUT;
        assertEquals("sidebar-layout", layout.id());
        assertEquals(List.of("full", "compact", "combat"), layout.optionIds());
        assertEquals(SidebarLayout.FULL, layout.defaultValue());
        assertEquals(SidebarLayout.COMBAT, layout.decodeOrNull(" Combat "));
        assertNull(layout.decodeOrNull("huge"));
        assertEquals("scoreboard", ScoreboardFeature.TOGGLE.id(), "the switch keeps its id, so stored rows keep working");
        assertTrue(ScoreboardFeature.TOGGLE.defaultOn());
        assertNull(ScoreboardFeature.TOGGLE.permission());
        assertNull(layout.permission());
        for (SidebarLayout value : SidebarLayout.values()) {
            assertSame(value, SidebarLayout.byId(value.id()));
        }
    }

    @Test
    void bothSitInTheDisplayGroupInCatalogOrderAndApplyAtOnce() {
        PlayerSettings settings = registered(new AtomicReference<>(shipped()));
        assertEquals(List.of("scoreboard", "sidebar-layout"), offered(settings));
        Registry.Entry<?> toggle = settings.registry().entry("scoreboard");
        Registry.Entry<?> layout = settings.registry().entry("sidebar-layout");
        assertEquals(SettingCategories.DISPLAY, toggle.category());
        assertEquals(1, toggle.options().order());
        assertEquals(3, layout.options().order(), "after the shared feedback channel");
        assertEquals(SettingOptions.Apply.INSTANT, toggle.options().apply());
        assertEquals(SettingOptions.Apply.INSTANT, layout.options().apply());
        assertNotNull(toggle.options().onChange());
        assertNotNull(layout.options().onChange());
    }

    @Test
    void neitherIsOfferedWhileAnotherPluginShowsTheSidebarOrItIsOff() {
        ScoreboardSettings shipped = shipped();
        AtomicReference<ScoreboardSettings> current = new AtomicReference<>(shipped);
        PlayerSettings settings = registered(current);

        current.set(shipped.effective(shipped.yielded("TAB"::equals)));
        assertEquals(List.of(), offered(settings), "TAB draws the sidebar");

        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.enabled", false);
        current.set(parse(yaml, new ArrayList<>()));
        assertEquals(List.of(), offered(settings), "the sidebar is off for everyone");

        yaml.set("sidebar.enabled", true);
        yaml.set("sidebar.layouts.compact", List.of());
        yaml.set("sidebar.layouts.combat", List.of());
        current.set(parse(yaml, new ArrayList<>()));
        assertEquals(List.of("scoreboard"), offered(settings), "no layout to pick besides the full sidebar");
    }

    @Test
    void anEmptiedLayoutIsNoLongerAnOptionAndItsPickersReadTheDefault() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        AtomicReference<ScoreboardSettings> current = new AtomicReference<>(parse(yaml, new ArrayList<>()));
        PlayerSettings settings = registered(current);
        UUID player = UUID.randomUUID();
        settings.overrides(new Overrides(Map.of("sidebar-layout", "compact"), Map.of(), Set.of()));
        assertEquals(SidebarLayout.COMPACT, settings.get(player, ScoreboardFeature.LAYOUT), "the server's default layout");

        Registry.Entry<?> entry = settings.registry().entry("sidebar-layout");
        assertEquals(List.of("full", "compact", "combat"), optionIds(settings, entry));
        yaml.set("sidebar.layouts.compact", List.of());
        current.set(parse(yaml, new ArrayList<>()));
        assertEquals(List.of("full", "combat"), optionIds(settings, entry));
        assertEquals(SidebarLayout.FULL, settings.get(player, ScoreboardFeature.LAYOUT), "an option the server took away reads as the default");
    }

    private static <T> List<String> optionIds(PlayerSettings settings, Registry.Entry<T> entry) {
        return settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    // ------------------------------------------------------------------ /sidebar

    @Test
    void sidebarCommandSteps() {
        assertEquals(ScoreboardCommands.Step.YIELDED, ScoreboardCommands.step(true, true, true, null));
        assertEquals(ScoreboardCommands.Step.OFF_ON_SERVER, ScoreboardCommands.step(false, true, true, false),
            "a sidebar turned off in the config is reported as off, whoever else would show it");
        assertEquals(ScoreboardCommands.Step.OFF_ON_SERVER, ScoreboardCommands.step(false, false, true, null));
        assertEquals(ScoreboardCommands.Step.ALREADY_SHOWN, ScoreboardCommands.step(true, false, true, true));
        assertEquals(ScoreboardCommands.Step.ALREADY_HIDDEN, ScoreboardCommands.step(true, false, false, false));
        assertEquals(ScoreboardCommands.Step.CHANGE, ScoreboardCommands.step(true, false, true, null), "a bare /sidebar flips it");
        assertEquals(ScoreboardCommands.Step.CHANGE, ScoreboardCommands.step(true, false, false, true));
    }

    @Test
    void sidebarCommandOutcomes() {
        assertEquals(ScoreboardMessages.SHOWN, ScoreboardCommands.outcome(SetResult.CHANGED, true));
        assertEquals(ScoreboardMessages.HIDDEN, ScoreboardCommands.outcome(SetResult.CHANGED, false));
        assertEquals(ScoreboardMessages.HIDDEN, ScoreboardCommands.outcome(SetResult.UNCHANGED, false));
        assertEquals(ScoreboardMessages.FIXED, ScoreboardCommands.outcome(SetResult.LOCKED, false), "locked in features/settings.yml");
        assertEquals(ScoreboardMessages.FIXED, ScoreboardCommands.outcome(SetResult.NOT_ALLOWED, true), "hidden in features/settings.yml");
        assertEquals(ScoreboardMessages.FIXED, ScoreboardCommands.outcome(SetResult.UNKNOWN, true), "not registered");
        assertTrue(ScoreboardMessages.FIXED.feedback() != null);
    }

    @Test
    void sidebarIsTheFeaturesOwnFlipThatNoListenerCancels() {
        assertFalse(ScoreboardCommands.CHANGE.cause().cancellable(), "/sidebar can never be cancelled, like /msgtoggle");
        for (SetResult result : SetResult.values()) {
            assertEquals(result.succeeded() ? ScoreboardMessages.SHOWN : ScoreboardMessages.FIXED, ScoreboardCommands.outcome(result, true),
                "every result has its answer: " + result);
        }
    }

    @Test
    void theSwitchCommandRespectsAServerLock() {
        PlayerSettings settings = registered(new AtomicReference<>(shipped()));
        UUID player = UUID.randomUUID();
        settings.overrides(new Overrides(Map.of(), Map.of("scoreboard", "true"), Set.of()));
        assertEquals(SetResult.LOCKED, settings.set(player, ScoreboardFeature.TOGGLE, false, ScoreboardCommands.CHANGE));
        assertTrue(settings.get(player, ScoreboardFeature.TOGGLE));
        assertEquals(ScoreboardMessages.FIXED, ScoreboardCommands.outcome(SetResult.LOCKED, false));
    }

    // ------------------------------------------------------------------ a hidden rank

    @Test
    void aHiddenRankLooksLikeAnOrdinaryMember() {
        RankOrder order = RankOrder.defaults();
        RankOrder.PlayerRank member = order.resolve("", "default", Set.of("default")::contains);
        assertEquals(member, order.hidden(), "same label, team and tab position as a default player");
        assertEquals("", order.hidden().label());
        assertEquals(order.listOrder(member), order.listOrder(order.hidden()));
        RankOrder.PlayerRank tycoon = order.resolve("Tycoon", "tycoon", Set.of("tycoon")::contains);
        assertTrue(order.listOrder(tycoon) > order.listOrder(order.hidden()), "a hidden Tycoon is not listed first");

        RankOrder noDefault = new RankOrder(List.of(new RankOrder.Rank("vip", "VIP")));
        assertEquals(RankOrder.PlayerRank.NONE, noDefault.hidden(), "unranked when default is not listed");
        assertEquals(0, noDefault.listOrder(noDefault.hidden()));
        assertEquals(noDefault.resolve("", "default", name -> false), noDefault.hidden(), "like a player without a listed rank");
    }

    @Test
    void aHiddenRankJoinsTheMembersNametagTeam() {
        RankOrder order = RankOrder.defaults();
        NametagModel model = new NametagModel();
        RankOrder.PlayerRank member = order.resolve("", "default", Set.of("default")::contains);
        RankOrder.PlayerRank hidden = order.hidden();
        model.set("Member", new NametagModel.Key(member.order(), member.label()));
        model.set("Hider", new NametagModel.Key(hidden.order(), hidden.label()));
        assertEquals(1, model.teams().size(), "one team for both: " + model.teams().keySet());
        assertEquals(Set.of("Member", "Hider"), model.teams().values().iterator().next().members());
    }
}
