package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.CombatLogEvent;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped config and text load without a single problem and follow the design rules. */
class CombatResourcesTest {

    private static Lang lang;
    private static YamlConfiguration langYaml;

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = CombatResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void loadText() throws Exception {
        Icons icons = new Icons(Icons.readIndex(CombatResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CombatMessages.class);
        langYaml = yaml("lang/combat.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/combat.yml");
        assertEquals(List.of(), problems);
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/combat.yml", yaml("features/combat.yml"));
        CombatSettings settings = CombatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(Duration.ofSeconds(20), settings.tagDuration());
        assertTrue(settings.tagPets());
        assertTrue(settings.actionBar());
        assertFalse(settings.blockEnderPearls());
        assertTrue(settings.disableElytra());
        assertTrue(settings.blockSpawnEntry());
        assertEquals(CombatLogEvent.Punishment.KILL, settings.logoutPunishment());
        assertTrue(settings.punishKicks(), "getting kicked is no way out of combat");
        assertTrue(settings.announceLogout());
        assertTrue(settings.deathMessages());
        assertTrue(settings.showWeapon());
        assertEquals(new AntiFarm.Rules(true, true, true, Duration.ofMinutes(10)), settings.antiFarm());
        assertEquals(CombatSettings.DEFAULT_STREAKS, settings.streaks().announceAt(), "the file and the built-in defaults agree");
        assertEquals(5, settings.streaks().endedFrom());
        List<String> shipped = settings.blockedCommands().rules().stream().map(CommandFilter.Rule::toString).toList();
        assertEquals(CombatSettings.DEFAULT_BLOCKED_COMMANDS, shipped, "the file and the built-in defaults agree");
        Function<String, Set<String>> names = Set::of;
        for (String command : List.of("/spawn", "/home", "/tpa Alex", "/rtp", "/warp shop", "/team home", "/ec", "/craft", "/shardshop")) {
            assertTrue(settings.blockedCommands().blocks(command, names), command + " is blocked by default");
        }
        assertFalse(settings.blockedCommands().blocks("/balance", names));
        assertFalse(settings.blockedCommands().blocks("/team info", names));
    }

    @Test
    void badValuesAreReportedPrecisely() throws Exception {
        YamlConfiguration broken = yaml("features/combat.yml");
        broken.set("tag.duration", "0s");
        broken.set("logout.punishment", "ban");
        broken.set("while-tagged.blocked-commands", List.of("home", "bad <entry>", ""));
        broken.set("anti-farm.repeated-pair-cooldown", "2d");
        broken.set("streaks.announce-at", List.of(5, "ten", 0, 20));
        broken.set("streaks.announce-ended-from", -1);
        ConfigReader reader = new ConfigReader("features/combat.yml", broken);
        CombatSettings settings = CombatSettings.parse(reader);
        assertEquals(8, reader.problems().size(), reader.problems().toString());
        assertEquals(List.of(5, 20), settings.streaks().announceAt(), "valid streaks are kept");
        assertEquals(5, settings.streaks().endedFrom(), "fallback");
        assertEquals(Duration.ofSeconds(20), settings.tagDuration(), "fallback");
        assertEquals(CombatLogEvent.Punishment.KILL, settings.logoutPunishment(), "fallback");
        assertEquals(List.of("home"), settings.blockedCommands().rules().stream().map(CommandFilter.Rule::toString).toList(),
            "valid entries are kept");
        assertEquals(Duration.ofMinutes(10), settings.antiFarm().repeatedPairCooldown(), "fallback");
    }

    @Test
    void punishmentNone() throws Exception {
        YamlConfiguration custom = yaml("features/combat.yml");
        custom.set("logout.punishment", "none");
        ConfigReader reader = new ConfigReader("features/combat.yml", custom);
        assertEquals(CombatLogEvent.Punishment.NONE, CombatSettings.parse(reader).logoutPunishment());
        assertEquals(List.of(), reader.problems());
    }

    @Test
    void everyLangEntryBelongsToAMessage() {
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("combat."))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(registered, inFile);
    }

    @Test
    void everyReasonHasText() {
        for (AntiFarm.Reason reason : AntiFarm.Reason.values()) {
            assertTrue(lang.plain(CombatMessages.reason(reason)).length() > 3, reason.name());
        }
    }

    @Test
    void killLinesRenderNamesAndTheWeapon() {
        Component weapon = Component.text("Diamond Sword");
        assertEquals("Alex was killed by Sam.", lang.plain(CombatMessages.DEATH_KILLED, Arg.text("victim", "Alex"), Arg.text("killer", "Sam")));
        assertEquals("Alex was killed by Sam using Diamond Sword.", lang.plain(CombatMessages.DEATH_KILLED_USING,
            Arg.text("victim", "Alex"), Arg.text("killer", "Sam"), Arg.component("item", weapon)));
        assertEquals("In combat 12s", lang.plain(CombatMessages.TAG_ACTION_BAR, Arg.time("time", Duration.ofSeconds(12))));
        assertEquals("Alex logged out in combat.", lang.plain(CombatMessages.LOGOUT_ANNOUNCE, Arg.text("name", "Alex")));
        assertEquals("<b>x</b> was killed by Sam.", lang.plain(CombatMessages.DEATH_KILLED, Arg.text("victim", "<b>x</b>"),
            Arg.text("killer", "Sam")), "player text stays literal");
    }

    @Test
    void settingLinesRender() {
        Arg alex = Arg.text("name", "Alex");
        assertEquals("You are in combat with Alex! Don't log out for 20s.", lang.plain(CombatMessages.TAG_STARTED, alex,
            Arg.time("time", Duration.ofSeconds(20))));
        assertEquals("Your kill on Alex counted. Kill streak 3.", lang.plain(CombatMessages.KILL_COUNTED, alex, Arg.number("streak", 3)));
        assertEquals("Your kill on Alex didn't count: friends.", lang.plain(CombatMessages.KILL_NOT_COUNTED, alex,
            Arg.text("reason", lang.plain(CombatMessages.REASON_FRIENDS))));
        assertEquals("Your kill on Alex didn't count.", lang.plain(CombatMessages.KILL_NOT_COUNTED_PLAIN, alex));
        assertEquals("You died at 12, -64, -3,500 in world.", lang.plain(CombatMessages.DEATH_LOCATION, Arg.text("world", "world"),
            Arg.number("x", 12), Arg.number("y", -64), Arg.number("z", -3_500)));
        assertEquals("You died in world_nether.", lang.plain(CombatMessages.DEATH_LOCATION_HIDDEN, Arg.text("world", "world_nether")));
        assertEquals("Sam had 6.5 hearts left, using Diamond Sword.", lang.plain(CombatMessages.DEATH_RECAP_USING,
            Arg.text("killer", "Sam"), Arg.decimal("hearts", 6.5), Arg.component("item", Component.text("Diamond Sword"))));
        assertEquals("Alex logged out in combat with 12s left. Last hit by Sam.", lang.plain(CombatMessages.STAFF_COMBAT_LOG, alex,
            Arg.time("time", Duration.ofSeconds(12)), Arg.text("attacker", "Sam")));
        assertEquals("Out of combat", lang.plain(CombatMessages.TAG_ENDED_TITLE));
        assertEquals("Player kills", lang.plain(CombatMessages.OPTION_DEATHS_PVP));
    }

    @Test
    void recapHeartsReadWithoutANeedlessDecimal() {
        assertEquals("10", CombatListener.heartsText(10.0));
        assertEquals("6.5", CombatListener.heartsText(6.5));
        assertEquals("0.5", CombatListener.heartsText(0.5));
        assertEquals("0", CombatListener.heartsText(0.0));
        assertEquals("Sam had 10 hearts left.", lang.plain(CombatMessages.DEATH_RECAP, Arg.text("killer", "Sam"),
            Arg.text("hearts", CombatListener.heartsText(10.0))));
    }

    /** The values players read (times, streaks, hearts, positions) are in the accent colour, their words are not. */
    @Test
    void valuesAreInTheAccentColour() {
        net.kyori.adventure.text.format.TextColor accent = Palette.defaults().accent();
        Component recap = lang.get(CombatMessages.DEATH_RECAP, Arg.text("killer", "Sam"), Arg.text("hearts", "6.5"));
        assertEquals(accent, colourOf(recap, "6.5"), "hearts: " + recap);
        Component timer = lang.get(CombatMessages.TAG_ACTION_BAR, Arg.text("time", "12s"));
        assertEquals(accent, colourOf(timer, "12s"), "the combat timer: " + timer);
        Component streak = lang.get(CombatMessages.STREAK_REACHED, Arg.text("name", "Alex"), Arg.text("count", "10"));
        assertEquals(accent, colourOf(streak, "10"), "the streak: " + streak);
        Component died = lang.get(CombatMessages.DEATH_LOCATION, Arg.text("world", "world"), Arg.text("x", "12"), Arg.text("y", "-64"),
            Arg.text("z", "-3,500"));
        assertEquals(accent, colourOf(died, "12"), "the position: " + died);
        assertEquals(Palette.defaults().secondary(), colourOf(died, "You died at "), "the words stay gray: " + died);
    }

    /** The colour the part of {@code text} reading exactly {@code part} is shown in (inherited from its parents). */
    private static net.kyori.adventure.text.format.TextColor colourOf(Component text, String part) {
        return colourOf(text, part, null);
    }

    private static net.kyori.adventure.text.format.TextColor colourOf(Component text, String part,
                                                                       net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor colour = text.color() != null ? text.color() : inherited;
        if (text instanceof net.kyori.adventure.text.TextComponent t && t.content().startsWith(part)) {
            return colour;
        }
        for (Component child : text.children()) {
            net.kyori.adventure.text.format.TextColor found = colourOf(child, part, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void messagesHaveNoLeftoverTagsOrStraySpaces() {
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "x")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + ": " + plain);
            assertFalse(plain.matches("(?s).* [.,!?:].*"), "space before punctuation in " + key.path() + ": " + plain);
            assertFalse(plain.matches("(?s).*\\b[A-Z]{4,}\\b.*"), "all caps in " + key.path() + ": " + plain);
            assertFalse(plain.contains("  "), "double space in " + key.path() + ": " + plain);
        }
    }
}
