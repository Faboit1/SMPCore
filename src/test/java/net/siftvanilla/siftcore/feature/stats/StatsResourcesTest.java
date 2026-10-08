package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
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

/** The shipped config and text must load without a single problem and follow the design rules. */
class StatsResourcesTest {

    private static Lang lang;
    private static YamlConfiguration langYaml;

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = StatsResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void loadText() throws Exception {
        Icons icons = new Icons(Icons.readIndex(StatsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        ConfigReader iconReader = new ConfigReader("icons.yml", yaml("icons.yml"));
        assertTrue(icons.load(IconSettings.parse(iconReader).icons()).isEmpty(), "every icon exists");
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(StatsMessages.class);
        langYaml = yaml("lang/stats.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/stats.yml");
        assertEquals(List.of(), problems);
    }

    /** Stands in for the server's block registry: the default ignore list plus a few real blocks. */
    private static boolean knownBlock(String name) {
        return StatsSettings.DEFAULT_IGNORED_BLOCKS.contains(name) || Set.of("stone", "dirt", "oak_log").contains(name);
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/stats.yml", yaml("features/stats.yml"));
        StatsSettings settings = StatsSettings.parse(reader, StatsResourcesTest::knownBlock);
        assertEquals(List.of(), reader.problems());
        assertEquals(Duration.ofSeconds(60), settings.saveInterval());
        assertEquals(Duration.ofMinutes(5), settings.keepOffline());
        assertEquals(Duration.ofSeconds(60), settings.leaderboardRefresh());
        assertEquals(100, settings.leaderboardSize());
        assertEquals(10, settings.pageSize());
        assertEquals(25, settings.kdrMinKills());
        assertEquals(Duration.ofMinutes(15), settings.ignorePlacedFor());
        assertEquals(50_000, settings.placedMemory());
        assertEquals(Set.copyOf(StatsSettings.DEFAULT_EARN_KINDS), settings.earnKinds());
        assertEquals(Set.copyOf(StatsSettings.DEFAULT_TAX_KINDS), settings.taxKinds());
        assertEquals(Set.copyOf(StatsSettings.DEFAULT_IGNORED_BLOCKS), settings.ignoredBlocks());
        assertTrue(settings.ignored("melon"));
        assertTrue(!settings.ignored("stone"));
    }

    @Test
    void badConfigValuesAreReportedPrecisely() throws Exception {
        YamlConfiguration broken = yaml("features/stats.yml");
        broken.set("leaderboards.size", 500);
        broken.set("money-earned.kinds", List.of("sell", "Bad Kind!"));
        ConfigReader reader = new ConfigReader("features/stats.yml", broken);
        StatsSettings settings = StatsSettings.parse(reader, StatsResourcesTest::knownBlock);
        assertEquals(2, reader.problems().size(), reader.problems().toString());
        assertEquals(100, settings.leaderboardSize());
        assertEquals(Set.copyOf(StatsSettings.DEFAULT_EARN_KINDS), settings.earnKinds());
    }

    @Test
    void ignoredBlocksAcceptNamespacesAndReportUnknownNames() throws Exception {
        YamlConfiguration custom = yaml("features/stats.yml");
        custom.set("blocks-mined.ignore", List.of("minecraft:Pumpkin", " oak_log ", "not_a_block", "bad name!"));
        ConfigReader reader = new ConfigReader("features/stats.yml", custom);
        StatsSettings settings = StatsSettings.parse(reader, StatsResourcesTest::knownBlock);
        assertEquals(Set.of("pumpkin", "oak_log"), settings.ignoredBlocks(), "valid entries are kept");
        assertEquals(2, reader.problems().size(), reader.problems().toString());
        assertTrue(reader.problems().getFirst().toString().contains("not_a_block"), reader.problems().toString());
        custom.set("blocks-mined.ignore", List.of());
        ConfigReader empty = new ConfigReader("features/stats.yml", custom);
        assertTrue(StatsSettings.parse(empty, StatsResourcesTest::knownBlock).ignoredBlocks().isEmpty());
        assertEquals(List.of(), empty.problems(), "an empty list counts every block");
    }

    @Test
    void everyLangEntryBelongsToAMessage() {
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("stats.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }

    @Test
    void everyBoardAndCounterHasText() {
        for (Board board : Board.values()) {
            assertTrue(plain(lang.get(StatsMessages.button(board))).length() > 1);
            assertTrue(plain(lang.get(StatsMessages.title(board))).length() > 1);
        }
        for (Counter counter : Counter.values()) {
            assertTrue(plain(lang.get(StatsMessages.name(counter))).length() > 1);
        }
    }

    @Test
    void statsBodyRendersEveryValue() {
        List<Component> lines = lang.lines(StatsMessages.VIEW_BODY,
            Arg.number("kills", 1234), Arg.number("deaths", 56), Arg.text("kdr", "22.04"), Arg.number("streak", 3),
            Arg.number("best", 17), Arg.time("playtime", Duration.ofHours(30)), Arg.number("mobs", 999),
            Arg.number("blocks", 120_000), Arg.money("earned", 2_500), Arg.money("balance", 75_000));
        assertEquals(9, lines.size());
        String text = lines.stream().map(StatsResourcesTest::plain).collect(Collectors.joining("\n"));
        for (String expected : List.of("Kills 1,234", "Deaths 56", "KDR 22.04", "Streak 3, best 17", "Playtime 1d 6h",
            "Mobs killed 999", "Blocks mined 120,000", "Money earned $2,500", "Balance $75,000")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in\n" + text);
        }
        // Each line starts with an icon sprite.
        assertTrue(text.lines().allMatch(line -> line.startsWith("[")), text);
    }

    @Test
    void messagesHaveNoLeftoverTagsOrStraySpaces() {
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "x")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertTrue(!plain.contains("<") && !plain.contains(">"), key.path() + ": " + plain);
            assertTrue(!plain.matches("(?s).* [.,!?:].*"), "space before punctuation in " + key.path() + ": " + plain);
            assertTrue(!plain.matches("(?s).*\\b[A-Z]{4,}\\b.*"), "all caps in " + key.path() + ": " + plain);
            assertTrue(!plain.contains("  "), "double space in " + key.path() + ": " + plain);
        }
    }

    private static String plain(Component component) {
        return TextStyle.plain(component);
    }
}
