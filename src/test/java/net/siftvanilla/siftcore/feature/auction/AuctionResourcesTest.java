package net.siftvanilla.siftcore.feature.auction;

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

/** The bundled {@code features/auction.yml} and {@code lang/auction.yml} are valid and complete. */
class AuctionResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = AuctionResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static YamlConfiguration yamlOf(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void bundledConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/auction.yml", yaml("features/auction.yml"));
        AuctionSettings settings = AuctionSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        assertEquals(Duration.ofHours(48), settings.duration());
        assertEquals(3, settings.defaultSlots());
        assertEquals(new AuctionMath.PriceRules(1, 10_000_000_000L, 1, 0), settings.price());
        assertEquals(0, settings.taxBasisPoints(), "no tax unless the owner sets one");
        assertTrue(settings.blacklist().matches("minecraft:barrier"));
        assertTrue(settings.blacklist().matches("minecraft:command_block"));
        assertTrue(settings.blacklist().matches("minecraft:creeper_spawn_egg"));
        assertFalse(settings.blacklist().matches("minecraft:shulker_box"));
        assertEquals(AuctionSettings.DEFAULT_BLACKLIST, settings.blacklist().entries());
        assertTrue(settings.allowFilledContainers());
        assertEquals(128 * 1024L, settings.maxItemBytes());
        assertFalse(settings.allowCreative());
        assertTrue(settings.blockInCombat());
        assertEquals(Duration.ofSeconds(30), settings.expiryCheck());
        assertTrue(settings.autoClaim());
        assertTrue(settings.joinReminder());
        assertEquals(20, settings.historySize());
        assertEquals(SortOrder.NEWEST, settings.defaultSort());
    }

    @Test
    void mistakesAreReportedPreciselyAndFallBack() throws Exception {
        String text = """
            listings:
              duration: 30s
              default-slots: -1
            price:
              minimum: 500
              maximum: 100
              minimum-per-item: 0
              maximum-per-item: 0
            tax: 150
            blacklist:
              items: [minecraft:barrier, "not an item"]
              allow-filled-containers: maybe
              max-item-size: -5
              allow-creative-mode: false
            block-in-combat: true
            expiry-check: 1s
            auto-claim: true
            join-reminder: true
            history-size: 20
            default-sort: cheapest
            """;
        ConfigReader reader = new ConfigReader("features/auction.yml", yamlOf(text));
        AuctionSettings settings = AuctionSettings.parse(reader, MoneyFormat.defaults());
        Set<String> paths = new TreeSet<>();
        for (ConfigProblem problem : reader.problems()) {
            paths.add(problem.path());
        }
        assertEquals(Set.of("listings.duration", "listings.default-slots", "price.maximum", "tax", "blacklist.items",
            "blacklist.allow-filled-containers", "blacklist.max-item-size", "expiry-check", "default-sort"), paths);
        assertEquals(128 * 1024L, settings.maxItemBytes(), "a broken size limit falls back to the default");
        assertEquals(Duration.ofHours(48), settings.duration());
        assertEquals(3, settings.defaultSlots());
        assertEquals(new AuctionMath.PriceRules(1, 10_000_000_000L, 1, 0), settings.price());
        assertEquals(0, settings.taxBasisPoints(), "a broken tax falls back to none");
        assertEquals(AuctionSettings.DEFAULT_BLACKLIST, settings.blacklist().entries());
        assertEquals(SortOrder.NEWEST, settings.defaultSort());
    }

    @Test
    void contradictoryPerItemLimitsAreReported() throws Exception {
        String text = """
            listings: {duration: 1d, default-slots: 5}
            price: {minimum: 1, maximum: 1m, minimum-per-item: 10, maximum-per-item: 5}
            tax: 2.5
            blacklist: {items: [], allow-filled-containers: false, max-item-size: 0, allow-creative-mode: true}
            block-in-combat: false
            expiry-check: 10s
            auto-claim: false
            join-reminder: false
            history-size: 5
            default-sort: lowest-price
            """;
        ConfigReader reader = new ConfigReader("features/auction.yml", yamlOf(text));
        AuctionSettings settings = AuctionSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(1, reader.problems().size());
        assertEquals("price.maximum-per-item", reader.problems().getFirst().path());
        assertEquals(new AuctionMath.PriceRules(1, 1_000_000, 10, 0), settings.price());
        assertEquals(250, settings.taxBasisPoints());
        assertEquals(0, settings.blacklist().size());
        assertEquals(SortOrder.LOWEST_PRICE, settings.defaultSort());
        assertFalse(settings.autoClaim());
        assertEquals(0, settings.maxItemBytes(), "0 turns the size limit off");
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = AuctionResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        ConfigReader iconReader = new ConfigReader("icons.yml", yaml("icons.yml"));
        assertTrue(icons.load(IconSettings.parse(iconReader).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(AuctionMessages.class);
        return lang;
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/auction.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/auction.yml");
        assertEquals(List.of(), problems);
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "Word")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + " leaves a tag behind: " + plain);
            assertFalse(plain.matches("(?s).*\\s[.,!?;:].*"), key.path() + " has a space before punctuation: " + plain);
            assertFalse(plain.isBlank(), key.path() + " is empty");
            String raw = String.join("\n", file.isList(key.path()) ? file.getStringList(key.path()) : List.of(file.getString(key.path())));
            assertFalse(raw.matches("(?s).*[A-Z]{3,}.*"), key.path() + " uses capitals: " + raw);
        }
    }

    /** Loads the bundled lang file into {@link #lang()} for rendering. */
    private static Lang loaded() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/auction.yml");
        assertEquals(List.of(), lang.load(file, file, "lang/auction.yml"));
        return lang;
    }

    @Test
    void untaxedSalesSayNothingAboutTax() throws Exception {
        Lang lang = loaded();
        Arg[] sale = {Arg.text("buyer", "Alex"), Arg.text("name", "Alex"), Arg.number("amount", 10), Arg.text("item", "Diamond"),
            Arg.money("price", 1_000), Arg.money("earned", 950), Arg.number("count", 3)};
        // As shipped (no tax): the seller's line, the join summary of one sale and of several.
        assertEquals("Alex bought your 10 Diamond for $1,000.", lang.plain(AuctionService.soldMessage(0), sale));
        assertEquals("While you were away, Alex bought your 10 Diamond for $1,000.", lang.plain(AuctionFeature.awayOne(0), sale));
        assertEquals("While you were away, 3 of your listings sold for $950:", lang.plain(AuctionFeature.awayMany(0), sale));
        // A taxed sale names what the seller got after it.
        assertEquals("Alex bought your 10 Diamond for $1,000. You got $950 after tax.", lang.plain(AuctionService.soldMessage(50), sale));
        assertTrue(lang.plain(AuctionFeature.awayOne(50), sale).endsWith("You got $950 after tax."));
        assertTrue(lang.plain(AuctionFeature.awayMany(1), sale).contains("You got $950 after tax"));
        // The listing confirmation's body and its List it tooltip: no tax there, the tax line is separate.
        String body = lang.plain(AuctionMessages.SELL_CONFIRM_BODY, sale);
        assertEquals("List 10 Diamond for $1,000?", body);
        assertEquals("Put it up for 2d.\nIf nobody buys it, it waits in your claim box.",
            lang.plain(AuctionMessages.SELL_CONFIRM_TOOLTIP, Arg.text("time", "2d")));
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/auction.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }
}
