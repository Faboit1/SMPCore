package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SellCategoriesTest {

    private static final ItemCatalog CATALOG = new ItemCatalog(
        Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:oak_planks", "minecraft:wheat", "minecraft:bread",
            "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:raw_iron", "minecraft:iron_block", "minecraft:diamond",
            "minecraft:dirt", "minecraft:paper"),
        Map.of("minecraft:logs", Set.of("minecraft:oak_log", "minecraft:birch_log")),
        List.of());

    private static SellCategories parse(String yaml, List<ConfigProblem> problems) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ConfigReader reader = new ConfigReader("features/sell.yml", config);
        SellCategories categories = SellCategories.parse(reader, CATALOG);
        problems.addAll(reader.problems());
        return categories;
    }

    private static final String VALID = """
        categories:
          farming:
            name: "Farming"
            icon: wheat
            items: [wheat]
          wood:
            name: "Wood"
            icon: oak_log
            items: ["#minecraft:logs"]
          mining:
            name: "Mining"
            items: ["*_ingot", "raw_*", diamond]
          other:
            name: "Blocks and other"
            icon: dirt
            items: []
        """;

    @Test
    void parsesIdsTagsAndPatterns() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellCategories categories = parse(VALID, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("farming", "wood", "mining", "other"), categories.ids());
        assertEquals("farming", categories.listed("minecraft:wheat"));
        assertEquals("wood", categories.listed("minecraft:birch_log"));
        assertEquals("mining", categories.listed("minecraft:iron_ingot"));
        assertEquals("mining", categories.listed("minecraft:gold_ingot"));
        assertEquals("mining", categories.listed("minecraft:raw_iron"));
        assertNull(categories.listed("minecraft:iron_block"));
        assertEquals("Mining", categories.name("mining"));
        assertEquals("minecraft:paper", categories.category("mining").icon());
        // a category that no longer exists shows its id
        assertEquals("fishing", categories.name("fishing"));
    }

    @Test
    void anItemInTwoCategoriesIsAProblem() {
        List<ConfigProblem> problems = new ArrayList<>();
        parse(VALID.replace("items: [wheat]", "items: [wheat, diamond]"), problems);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().toString().contains("diamond is also in the category"), problems.toString());
    }

    @Test
    void unknownItemsTagsAndEmptyPatternsAreProblems() {
        List<ConfigProblem> problems = new ArrayList<>();
        parse(VALID.replace("items: [wheat]", "items: [wheet, \"#minecraft:nope\", \"*_nothing\"]"), problems);
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void theFallbackAlwaysExistsAndComesLast() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellCategories categories = parse("""
            categories:
              other:
                name: "Everything else"
              mining:
                items: [diamond]
            """, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("mining", "other"), categories.ids());
        assertEquals("Everything else", categories.name("other"));
        // a missing section means the built-in defaults
        SellCategories defaults = parse("craft-loss: 0.9\n", problems);
        assertTrue(defaults.ids().containsAll(List.of("farming", "wood", "mining", "mob_drops", "fishing", "other")));
        assertEquals("other", defaults.ids().getLast());
    }

    @Test
    void derivedItemsInheritTheCategoryOfTheirMostValuableIngredient() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellCategories categories = parse(VALID, problems);
        List<RecipeDef> recipes = List.of(
            new RecipeDef("t:iron_block", RecipeDef.Kind.CRAFTING, "minecraft:iron_block", 1,
                List.of(new RecipeDef.Ingredient(List.of("minecraft:iron_ingot"), 9))),
            new RecipeDef("t:planks", RecipeDef.Kind.CRAFTING, "minecraft:oak_planks", 4,
                RecipeDef.slots(List.of(List.of("minecraft:oak_log")))),
            // bread: three wheat ($6 each) and, made up for the test, a dirt block ($1): wheat is worth more
            new RecipeDef("t:bread", RecipeDef.Kind.CRAFTING, "minecraft:bread", 1,
                List.of(new RecipeDef.Ingredient(List.of("minecraft:wheat"), 3), new RecipeDef.Ingredient(List.of("minecraft:dirt"), 1))));
        WorthTable table = WorthCalculator.calculate(Map.of("minecraft:iron_ingot", 25L, "minecraft:oak_log", 6L,
            "minecraft:wheat", 6L, "minecraft:dirt", 1L, "minecraft:diamond", 400L), Map.of(), recipes, 0.9, Long.MAX_VALUE,
            categories::listed).table();
        assertEquals("mining", table.category("minecraft:iron_block"));
        assertEquals("wood", table.category("minecraft:oak_planks"));
        assertEquals("farming", table.category("minecraft:bread"));
        // base-priced items nobody lists are in the fallback
        assertEquals(SellCategories.OTHER, table.category("minecraft:dirt"));
        assertEquals("mining", table.category("minecraft:diamond"));
        assertEquals(Map.of("mining", 3, "wood", 2, "farming", 2, "other", 1), table.categorySizes());
    }
}
