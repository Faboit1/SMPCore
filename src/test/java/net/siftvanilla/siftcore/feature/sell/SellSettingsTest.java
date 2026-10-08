package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SellSettingsTest {

    private static final ItemCatalog CATALOG = new ItemCatalog(
        Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:oak_planks", "minecraft:stick",
            "minecraft:diamond", "minecraft:iron_ingot", "minecraft:iron_block", "minecraft:raw_iron"),
        Map.of("minecraft:logs", Set.of("minecraft:oak_log", "minecraft:birch_log"), "minecraft:empty", Set.of()),
        List.of(
            new RecipeDef("minecraft:oak_planks", RecipeDef.Kind.CRAFTING, "minecraft:oak_planks", 4,
                RecipeDef.slots(List.of(List.of("minecraft:oak_log", "minecraft:birch_log")))),
            new RecipeDef("minecraft:iron_block", RecipeDef.Kind.CRAFTING, "minecraft:iron_block", 1,
                List.of(new RecipeDef.Ingredient(List.of("minecraft:iron_ingot"), 9))),
            new RecipeDef("minecraft:iron_ingot_from_smelting_raw_iron", RecipeDef.Kind.SMELTING, "minecraft:iron_ingot", 1,
                RecipeDef.slots(List.of(List.of("minecraft:raw_iron"))))));

    private static final String VALID = """
        craft-loss: 0.9
        recipe-types: [crafting, smelting]
        multipliers:
          vip: 1.1
          elite: 1.5
        sell-all:
          skip-unstackable: true
          skip-hotbar: false
        base-prices:
          "#minecraft:logs": 6
          birch_log: 4
          diamond: 400
          raw_iron: 20
        overrides:
          stick: 1
          iron_block: 0
        """;

    static SellSettings parse(String yaml, List<ConfigProblem> problems) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ConfigReader reader = new ConfigReader("features/sell.yml", config);
        SellSettings settings = SellSettings.parse(reader, CATALOG, MoneyFormat.defaults());
        problems.addAll(reader.problems());
        return settings;
    }

    @Test
    void parsesAndGeneratesTheTable() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = parse(VALID, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        WorthTable table = settings.table();
        assertEquals(6, table.price("minecraft:oak_log"));
        // an item listed by id wins over the tag
        assertEquals(4, table.price("minecraft:birch_log"));
        assertEquals(400, table.price("minecraft:diamond"));
        // smelting is enabled: raw iron 20 -> ingot 18
        assertEquals(18, table.price("minecraft:iron_ingot"));
        assertEquals(WorthTable.Origin.DERIVED, table.entry("minecraft:iron_ingot").origin());
        assertEquals(1, table.price("minecraft:stick"));
        assertFalse(table.sellable("minecraft:iron_block"));
        assertEquals(1.5, settings.highestRankMultiplier());
        // the best rank plus the top mastery bonus (five levels of 0.05)
        assertEquals(1.75, settings.highestMultiplier(), 1e-12);
        assertEquals(Set.of(RecipeDef.Kind.CRAFTING, RecipeDef.Kind.SMELTING), settings.recipeKinds());
        assertEquals(3, settings.recipes().size());
    }

    @Test
    void recipeTypesThatAreOffAreNotUsed() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = parse(VALID.replace("[crafting, smelting]", "[crafting]"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertFalse(settings.table().sellable("minecraft:iron_ingot"));
    }

    @Test
    void reportsEveryMistakePrecisely() {
        String yaml = """
            craft-loss: 1.5
            recipe-types: [crafting, baking]
            multipliers:
              VIP Rank: 1.2
              cheap: 0.5
            sell-all:
              skip-unstackable: maybe
              skip-hotbar: false
            base-prices:
              "#minecraft:not_a_tag": 5
              "#minecraft:empty": 5
              diamond: 400
              minecraft:diamond: 410
              dirt: 1
              iron_ingot: 0
              stick: lots
              oak_log: 1.5
            overrides:
              stone: 3
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = parse(yaml, problems);
        List<String> paths = problems.stream().map(ConfigProblem::path).toList();
        assertTrue(paths.contains("craft-loss"), paths.toString());
        assertTrue(paths.contains("recipe-types"), paths.toString());
        assertTrue(paths.contains("multipliers.VIP Rank"), paths.toString());
        assertTrue(paths.contains("multipliers.cheap"), paths.toString());
        assertTrue(paths.contains("sell-all.skip-unstackable"), paths.toString());
        assertTrue(paths.contains("base-prices.#minecraft:not_a_tag"), paths.toString());
        assertTrue(paths.contains("base-prices.#minecraft:empty"), paths.toString());
        assertTrue(paths.contains("base-prices.minecraft:diamond"), paths.toString());
        assertTrue(paths.contains("base-prices.dirt"), paths.toString());
        assertTrue(paths.contains("base-prices.iron_ingot"), paths.toString());
        assertTrue(paths.contains("base-prices.stick"), paths.toString());
        assertTrue(paths.contains("base-prices.oak_log"), paths.toString());
        assertTrue(paths.contains("overrides.stone"), paths.toString());
        // what was valid still applies
        assertEquals(400, settings.table().price("minecraft:diamond"));
        assertEquals(0.9, settings.craftLoss());
    }
    @Test
    void newKeysAreOptionalWithTheirDefaults() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = parse(VALID, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(SellSettings.Confirm.ABOVE, settings.sellAll().confirm());
        assertEquals(10_000, settings.sellAll().confirmAbove());
        assertTrue(settings.sellAll().shulkerContents());
        assertTrue(settings.shulkerContents());
        assertFalse(settings.bundleContents());
        assertTrue(settings.blockInCombat());
        assertTrue(settings.markTrades());
        assertFalse(settings.actionBarTotal());
        assertEquals(Mastery.DEFAULT, settings.mastery());
        assertEquals(java.time.Duration.ofMinutes(5), settings.topRefresh());
    }

    @Test
    void sellAllAsksAccordingToTheSettingAndThePlayer() {
        SellSettings.SellAll above = new SellSettings.SellAll(true, false, SellSettings.Confirm.ABOVE, 10_000, true);
        assertTrue(above.asks(10_000, true));
        assertFalse(above.asks(9_999, true));
        assertFalse(above.asks(1_000_000, false), "players who turned asking off sell right away");
        SellSettings.SellAll always = new SellSettings.SellAll(true, false, SellSettings.Confirm.ALWAYS, 10_000, true);
        assertTrue(always.asks(1, false));
        SellSettings.SellAll never = new SellSettings.SellAll(true, false, SellSettings.Confirm.NEVER, 10_000, true);
        assertFalse(never.asks(Long.MAX_VALUE, true));
    }

    @Test
    void readsTheNewKeys() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = parse(VALID + """
            block-in-combat: false
            mark-villager-trades: false
            shulker-contents: false
            bundle-contents: true
            feedback:
              action-bar: true
            top:
              refresh: 2m
            mastery:
              enabled: false
            """, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertFalse(settings.blockInCombat());
        assertFalse(settings.markTrades());
        assertFalse(settings.shulkerContents());
        assertTrue(settings.bundleContents());
        assertTrue(settings.actionBarTotal());
        assertEquals(java.time.Duration.ofMinutes(2), settings.topRefresh());
        assertFalse(settings.mastery().enabled());
        // without mastery the best multiplier is the best rank
        assertEquals(1.5, settings.highestMultiplier(), 1e-12);
        problems.clear();
        parse(VALID.replace("skip-hotbar: false", "skip-hotbar: false\n  confirm: sometimes"), problems);
        assertEquals(List.of("sell-all.confirm"), problems.stream().map(ConfigProblem::path).toList());
    }

    @Test
    void patternsPriceWhatIdsAndTagsLeaveOpen() {
        ItemCatalog catalog = new ItemCatalog(
            Set.of("minecraft:music_disc_13", "minecraft:music_disc_cat", "minecraft:oak_log", "minecraft:birch_log",
                "minecraft:brain_coral", "minecraft:brain_coral_block", "minecraft:dead_brain_coral"),
            Map.of("minecraft:logs", Set.of("minecraft:oak_log", "minecraft:birch_log")), List.of());
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                base-prices:
                  "music_disc_*": 200
                  music_disc_cat: 250
                  "*_log": 1
                  "#minecraft:logs": 6
                  "*_coral": 1
                  "*_coral_block": 3
                  "*_nothing": 5
                  "bad pattern*": 5
                """);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ConfigReader reader = new ConfigReader("features/sell.yml", config);
        Map<String, Long> prices = SellSettings.prices(reader.section("base-prices"), catalog, MoneyFormat.defaults(), false);
        assertEquals(200L, prices.get("minecraft:music_disc_13"));
        // an id wins over a pattern
        assertEquals(250L, prices.get("minecraft:music_disc_cat"));
        // a tag wins over a pattern
        assertEquals(6L, prices.get("minecraft:oak_log"));
        assertEquals(1L, prices.get("minecraft:brain_coral"));
        assertEquals(1L, prices.get("minecraft:dead_brain_coral"));
        assertEquals(3L, prices.get("minecraft:brain_coral_block"));
        // patterns match whole ids: *_coral doesn't price coral blocks
        List<String> paths = reader.problems().stream().map(ConfigProblem::path).toList();
        assertEquals(List.of("base-prices.*_nothing", "base-prices.bad pattern*"), paths);
    }
}
