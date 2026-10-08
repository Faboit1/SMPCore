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
        assertEquals(1.5, settings.highestMultiplier());
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
}
