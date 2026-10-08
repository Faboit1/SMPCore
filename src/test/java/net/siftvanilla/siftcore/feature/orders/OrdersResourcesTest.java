package net.siftvanilla.siftcore.feature.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import org.junit.jupiter.api.Test;

/** The bundled {@code features/orders.yml} and {@code lang/orders.yml} are valid and complete. */
class OrdersResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = OrdersResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    @Test
    void bundledConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/orders.yml", yaml("features/orders.yml"));
        OrdersSettings settings = OrdersSettings.parse(reader, MoneyFormat.defaults(), Set.of());
        assertEquals(List.of(), reader.problems());
        assertEquals(Duration.ofDays(7), settings.duration());
        assertEquals(Duration.ofSeconds(30), settings.expiryCheck());
        assertEquals(Duration.ofHours(12), settings.expiryWarning());
        assertEquals(3, settings.defaultLimit());
        assertEquals(1, settings.minPrice());
        assertEquals(100_000, settings.maxQuantity());
        assertEquals(100_000_000_000L, settings.maxTotal());
        assertEquals(200, settings.taxBasisPoints());
        assertTrue(settings.blockInCombat());
        assertTrue(settings.joinReminder());
        assertFalse(settings.refuseSameIp());
        assertEquals(1_000, settings.suggestMargin());
        assertEquals(0, settings.minVsWorth());
        assertEquals(0.0, settings.maxVsWorth());
        assertTrue(settings.booksEnabled());
        assertFalse(settings.allowCurses());
        assertTrue(settings.potionsEnabled());
        assertTrue(settings.spawnersEnabled());
        assertEquals(200, settings.historyEntries());
        assertEquals(Duration.ZERO, settings.historyKeep());
        assertTrue(settings.extendEnabled());
        assertEquals(Duration.ofDays(30), settings.maxLifetime());
        assertEquals(1_000_000, settings.announceMinTotal());
        assertEquals(Duration.ofMinutes(10), settings.announceCooldown());
        assertEquals(OrdersSettings.DEFAULT_BLOCKED.stream().map(entry -> "minecraft:" + entry).toList(), settings.blocked().entries(),
            "the bundled list is the built-in default");
        assertTrue(settings.isBlocked("minecraft:bedrock"));
        assertTrue(settings.isBlocked("minecraft:zombie_spawn_egg"));
        assertTrue(settings.isBlocked("minecraft:infested_stone"));
        assertFalse(settings.isBlocked("minecraft:diamond"));
        assertFalse(settings.isBlocked("minecraft:enchanted_book"), "books are ordered as variants, switched by books.enabled");
    }

    /** The bundled config with some values replaced. */
    private static YamlConfiguration bundledWith(Object... pathsAndValues) throws Exception {
        YamlConfiguration yaml = yaml("features/orders.yml");
        for (int i = 0; i < pathsAndValues.length; i += 2) {
            yaml.set((String) pathsAndValues[i], pathsAndValues[i + 1]);
        }
        return yaml;
    }

    @Test
    void mistakesAreReportedPreciselyAndFallBack() throws Exception {
        YamlConfiguration yaml = bundledWith("duration", "5s", "expiry-check", "2h", "limits.active-orders", -1, "limits.max-quantity", 0,
            "tax", 75, "pricing.max-vs-worth", 0.5, "history.keep", "1h", "books.enabled", "maybe",
            "blocked-items", List.of("bedrock", "not an item!", "*_spawn_egg"));
        ConfigReader reader = new ConfigReader("features/orders.yml", yaml);
        OrdersSettings settings = OrdersSettings.parse(reader, MoneyFormat.defaults(), Set.of("minecraft:bedrock", "minecraft:pig_spawn_egg"));
        Set<String> paths = new TreeSet<>();
        for (ConfigProblem problem : reader.problems()) {
            paths.add(problem.path());
        }
        assertEquals(Set.of("duration", "expiry-check", "limits.active-orders", "limits.max-quantity", "tax", "pricing.max-vs-worth",
            "history.keep", "books.enabled", "blocked-items"), paths);
        assertEquals(Duration.ofDays(7), settings.duration(), "a broken duration falls back to the default");
        assertEquals(Duration.ofSeconds(30), settings.expiryCheck());
        assertEquals(3, settings.defaultLimit());
        assertEquals(100_000, settings.maxQuantity());
        assertEquals(200, settings.taxBasisPoints());
        assertEquals(0.0, settings.maxVsWorth(), "a ceiling under the worth is turned off");
        assertEquals(Duration.ZERO, settings.historyKeep());
        assertTrue(settings.booksEnabled());
        assertEquals(List.of("minecraft:bedrock", "minecraft:*_spawn_egg"), settings.blocked().entries(), "the broken entry is left out");
    }

    @Test
    void anEntryMatchingNoItemIsReported() throws Exception {
        ConfigReader reader = new ConfigReader("features/orders.yml", bundledWith("blocked-items", List.of("bedrock", "no_such_item")));
        OrdersSettings settings = OrdersSettings.parse(reader, MoneyFormat.defaults(), Set.of("minecraft:bedrock"));
        assertEquals(1, reader.problems().size(), reader.problems().toString());
        assertTrue(reader.problems().getFirst().message().contains("matches no item"), reader.problems().toString());
        assertEquals(List.of("minecraft:bedrock"), settings.blocked().entries());
    }

    @Test
    void contradictoryLimitsAreReported() throws Exception {
        ConfigReader reader = new ConfigReader("features/orders.yml", bundledWith("limits.min-price", "1k", "limits.max-total", "500",
            "extend.max-lifetime", "1d"));
        OrdersSettings settings = OrdersSettings.parse(reader, MoneyFormat.defaults(), Set.of());
        Set<String> paths = new TreeSet<>();
        for (ConfigProblem problem : reader.problems()) {
            paths.add(problem.path());
        }
        assertEquals(Set.of("limits.max-total", "extend.max-lifetime"), paths);
        assertEquals(1_000, settings.minPrice());
        assertTrue(settings.maxTotal() >= settings.minPrice());
        assertEquals(Duration.ofDays(7), settings.maxLifetime(), "an order may always run its whole duration");
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = OrdersResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        ConfigReader iconReader = new ConfigReader("icons.yml", yaml("icons.yml"));
        assertTrue(icons.load(IconSettings.parse(iconReader).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(OrdersMessages.class);
        return lang;
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/orders.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/orders.yml");
        assertEquals(List.of(), problems);
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "Word")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + " leaves a tag behind: " + plain);
            assertFalse(plain.matches("(?s).*[ \\t][.,!?;:].*"), key.path() + " has a space before punctuation: " + plain);
            assertFalse(plain.isBlank(), key.path() + " is empty");
            String raw = String.join("\n", file.isList(key.path()) ? file.getStringList(key.path()) : List.of(file.getString(key.path())));
            assertFalse(raw.matches("(?s).*[A-Z]{3,}.*"), key.path() + " uses capitals: " + raw);
            assertFalse(raw.contains("<bold>") || raw.contains("<b>") || raw.contains("<gradient"), key.path() + " is styled: " + raw);
        }
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/orders.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }
}
