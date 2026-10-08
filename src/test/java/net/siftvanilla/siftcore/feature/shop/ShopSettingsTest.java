package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.feature.sell.Pricing;
import net.siftvanilla.siftcore.feature.sell.RecipeDef;
import net.siftvanilla.siftcore.feature.sell.WorthCalculator;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ShopSettingsTest {

    private static final ShopSettings.Catalog CATALOG = new ShopSettings.Catalog(
        Set.of("minecraft:stone", "minecraft:dirt", "minecraft:raw_iron", "minecraft:iron_ingot", "minecraft:diamond",
            "minecraft:spawner", "minecraft:chest", "minecraft:name_tag"),
        Set.of("minecraft:zombie", "minecraft:skeleton"));

    private static final List<RecipeDef> RECIPES = List.of(new RecipeDef("minecraft:iron_ingot", RecipeDef.Kind.SMELTING,
        "minecraft:iron_ingot", 1, RecipeDef.slots(List.of(List.of("minecraft:raw_iron")))));

    private static final Pricing PRICING = new Pricing(WorthCalculator.calculate(
        Map.of("minecraft:stone", 2L, "minecraft:dirt", 1L, "minecraft:raw_iron", 20L, "minecraft:iron_ingot", 25L,
            "minecraft:diamond", 400L), Map.of(), RECIPES, 0.9, Long.MAX_VALUE).table(), 1.5, RECIPES);

    private static final String YAML = """
        confirm-above: 50k
        default-max: 640
        menu:
          rows: 4
        categories:
          blocks:
            name: "Blocks"
            description: "Building blocks"
            icon: stone
            slot: 10
            items:
              stone: {price: 6, max: 2304}
              dirt: {price: 1}
          ores:
            name: "Ores"
            icon: diamond
            slot: 12
            default-max: 64
            items:
              raw_iron: {price: 34}
              iron: {item: iron_ingot, price: 60}
              tag: {item: name_tag, price: 1}
          spawners:
            name: "Spawners"
            icon: spawner
            slot: 14
            items:
              zombie_spawner: {spawner: zombie, price: 60k, max: 16}
              dragon_spawner: {spawner: ender_dragon, price: 1m}
        """;

    static ShopSettings parse(String yaml, List<ConfigProblem> problems) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ConfigReader reader = new ConfigReader("features/shop.yml", config);
        ShopSettings settings = ShopSettings.parse(reader, CATALOG, PRICING, MoneyFormat.defaults());
        problems.addAll(reader.problems());
        return settings;
    }

    @Test
    void parsesCategoriesAndLeavesUnsafeEntriesOut() {
        List<ConfigProblem> problems = new ArrayList<>();
        ShopSettings shop = parse(YAML, problems);
        assertEquals(50_000, shop.confirmAbove());
        assertEquals(31, shop.balanceSlot());
        assertEquals(List.of("blocks", "ores", "spawners"), shop.categories().stream().map(ShopSettings.Category::id).toList());

        ShopSettings.Entry stone = shop.entry("blocks/stone");
        assertEquals("minecraft:stone", stone.item());
        assertEquals(6, stone.price());
        assertEquals(2304, stone.max());
        // dirt sells back for 1 (1.65 with the best bonus), so 1 is not a safe price
        assertNull(shop.entry("blocks/dirt"));
        // raw iron smelts into an ingot worth 25 (41.25 with the bonus)
        assertNull(shop.entry("ores/raw_iron"));
        ShopSettings.Entry iron = shop.entry("ores/iron");
        assertEquals("minecraft:iron_ingot", iron.item());
        assertEquals(64, iron.max());
        // unsellable items can have any price
        assertNotNull(shop.entry("ores/tag"));
        ShopSettings.Entry zombie = shop.entry("spawners/zombie_spawner");
        assertTrue(zombie.spawner());
        assertEquals("minecraft:zombie", zombie.mob());
        assertEquals(60_000, zombie.price());
        assertNull(shop.entry("spawners/dragon_spawner"));
        assertEquals(4, shop.entriesByRef().size());

        List<String> paths = problems.stream().map(ConfigProblem::path).toList();
        assertEquals(List.of("categories.blocks.items.dirt.price", "categories.ores.items.raw_iron.price",
            "categories.spawners.items.dragon_spawner.spawner"), paths);
        assertTrue(problems.get(1).message().contains("iron_ingot"), problems.get(1).message());
    }

    @Test
    void slotClashesAndBadCategoriesAreReported() {
        String yaml = """
            confirm-above: 0
            default-max: 64
            menu:
              rows: 3
            categories:
              a:
                name: "A"
                icon: stone
                slot: 22
                items:
                  stone: {price: 10}
              b:
                name: "B"
                icon: stone
                slot: 10
                items:
                  stone: {price: 10}
              c:
                name: "C"
                icon: not_an_item
                slot: 10
                items: {}
              "Bad Id":
                name: "D"
                icon: stone
                slot: 11
                items:
                  stone: {price: 10}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        ShopSettings shop = parse(yaml, problems);
        List<String> paths = problems.stream().map(ConfigProblem::path).toList();
        // 22 is the balance slot of a 3-row menu
        assertTrue(paths.contains("categories.a.slot"), paths.toString());
        assertTrue(paths.contains("categories.c.slot"), paths.toString());
        assertTrue(paths.contains("categories.c.icon"), paths.toString());
        assertTrue(paths.contains("categories.c.items"), paths.toString());
        assertTrue(paths.contains("categories.Bad Id"), paths.toString());
        assertEquals(0, shop.confirmAbove());
    }
}
