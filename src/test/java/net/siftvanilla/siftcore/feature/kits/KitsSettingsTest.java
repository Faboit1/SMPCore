package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class KitsSettingsTest {

    static final KitsSettings.Catalog CATALOG = new KitsSettings.Catalog(
        Set.of("minecraft:stone_sword", "minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_shovel", "minecraft:bread",
            "minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots",
            "minecraft:cooked_beef", "minecraft:baked_potato", "minecraft:apple", "minecraft:iron_helmet", "minecraft:iron_chestplate",
            "minecraft:iron_leggings", "minecraft:iron_boots", "minecraft:iron_sword", "minecraft:iron_pickaxe", "minecraft:iron_axe",
            "minecraft:golden_apple", "minecraft:golden_carrot", "minecraft:experience_bottle", "minecraft:diamond_helmet",
            "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:diamond_sword",
            "minecraft:diamond_pickaxe", "minecraft:diamond_axe", "minecraft:ender_pearl", "minecraft:enchanted_book",
            "minecraft:chest", "minecraft:carved_pumpkin", "minecraft:dirt", "minecraft:diamond", "minecraft:torch", "minecraft:oak_log",
            "minecraft:glass", "minecraft:lantern", "minecraft:bone_meal", "minecraft:name_tag"),
        Map.of("minecraft:efficiency", 5, "minecraft:protection", 4, "minecraft:sharpness", 5, "minecraft:unbreaking", 3,
            "minecraft:feather_falling", 4, "minecraft:looting", 3, "minecraft:fortune", 3, "minecraft:mending", 1),
        Set.of("basic", "rare", "epic", "legendary"));

    static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    static YamlConfiguration bundled() throws Exception {
        try (InputStream in = KitsSettingsTest.class.getClassLoader().getResourceAsStream("features/kits.yml")) {
            assertNotNull(in, "features/kits.yml is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static KitsSettings parse(YamlConfiguration yaml, List<ConfigProblem> problems) {
        return parse(yaml, problems, CATALOG);
    }

    private static KitsSettings parse(YamlConfiguration yaml, List<ConfigProblem> problems, KitsSettings.Catalog catalog) {
        ConfigReader reader = new ConfigReader("features/kits.yml", yaml);
        KitsSettings settings = KitsSettings.parse(reader, catalog);
        problems.addAll(reader.problems());
        return settings;
    }

    private static String problemsText(List<ConfigProblem> problems) {
        List<String> lines = new ArrayList<>();
        for (ConfigProblem problem : problems) {
            lines.add(problem.toString());
        }
        return String.join("\n", lines);
    }

    /** A minimal valid file with the given kits section (indented under kits:). */
    private static String file(String kits) {
        return """
            block-in-combat: true
            reminders: true
            locked-kits: hide
            kits:
            %s
            perks:
              blocked-in-combat: [ec, craft]
              close-on-combat: true
              hat:
                blocked: []
            """.formatted(kits.indent(2));
    }

    @Test
    void theBundledFileHasNoProblems() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(bundled(), problems);
        assertEquals("", problemsText(problems));
        assertEquals(List.of("starter", "daily", "prospector", "baron", "tycoon"),
            settings.kits().stream().map(Kit::id).toList());
        assertTrue(settings.blockInCombat());
        assertTrue(settings.reminders());
        assertFalse(settings.showLocked());
    }

    @Test
    void theBundledKitsMatchTheRanks() throws Exception {
        KitsSettings settings = parse(bundled(), new ArrayList<>());
        Kit starter = settings.kit("starter");
        assertTrue(starter.everyone());
        assertTrue(starter.cooldown().once());
        assertEquals("siftcore.kit.starter", starter.permission());
        assertTrue(starter.items().stream().anyMatch(item -> item.material().equals("minecraft:stone_pickaxe")));
        assertTrue(starter.items().stream().anyMatch(item -> item.material().equals("minecraft:leather_chestplate")));
        assertTrue(starter.items().stream().anyMatch(item -> item.material().equals("minecraft:bread") && item.amount() == 16));
        Kit daily = settings.kit("daily");
        assertTrue(daily.everyone());
        assertEquals(Duration.ofHours(24), daily.cooldown().every());

        // Rank kits, named after the LuckPerms groups (prospector < baron < tycoon): only for their rank (and higher ranks
        // through inheritance), once a day, each giving more than the one below, and supplies only: a paid rank never
        // gives gear that helps in a fight, nor crate keys (random rewards can't be sold).
        List<String> ranks = List.of("prospector", "baron", "tycoon");
        Set<String> combatItems = Set.of("golden_apple", "enchanted_golden_apple", "totem_of_undying", "ender_pearl", "experience_bottle",
            "end_crystal", "respawn_anchor", "elytra", "firework_rocket", "potion", "splash_potion", "lingering_potion", "bow", "crossbow",
            "trident", "mace", "shield", "arrow", "tipped_arrow", "spectral_arrow", "tnt", "obsidian");
        int previousItems = 0;
        for (String rank : ranks) {
            Kit kit = settings.kit(rank);
            assertNotNull(kit, rank);
            assertFalse(kit.everyone(), rank + " is not for everyone");
            assertEquals("siftcore.kit." + rank, kit.permission());
            assertEquals(Duration.ofHours(24), kit.cooldown().every(), rank + " is daily");
            assertEquals(Map.of(), kit.keys(), rank + " gives no crate keys");
            int total = 0;
            for (KitItem item : kit.items()) {
                String path = item.material().substring(item.material().indexOf(':') + 1);
                assertFalse(path.matches(".*_(sword|axe|helmet|chestplate|leggings|boots|horse_armor)") || combatItems.contains(path),
                    rank + " gives combat gear: " + path);
                assertTrue(item.enchantments().isEmpty(), rank + " gives enchanted " + path);
                total += item.amount();
            }
            assertTrue(total > previousItems, rank + " gives more than the rank below");
            previousItems = total;
        }
        assertTrue(settings.kit("tycoon").items().stream().anyMatch(item -> item.material().equals("minecraft:name_tag")));
        assertEquals(java.util.EnumSet.allOf(Perk.class), settings.perks().blockedInCombat(), "every paid perk is refused in combat");
        assertTrue(settings.perks().closeOnCombat());
        assertEquals(Set.of(), settings.perks().hatBlocked());
    }

    @Test
    void readsEveryItemSetting() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(yaml(file("""
            pvp:
              name: "PvP"
              description: "For fighting"
              icon: diamond
              everyone: true
              cooldown: 1d12h
              items:
                diamond_sword:
                  amount: 1
                  name: "Blade"
                  lore: ["Sharp", "Very sharp"]
                  enchantments:
                    sharpness: 5
                    MINECRAFT:Unbreaking: 3
                  unbreakable: true
                spare:
                  material: diamond_sword
                book:
                  material: enchanted_book
                  enchantments:
                    mending: 1
                bread: {amount: 200}
              keys:
                Rare: 2
            """)), problems);
        assertEquals("", problemsText(problems));
        Kit kit = settings.kit("PVP");
        assertNotNull(kit);
        assertEquals("PvP", kit.name());
        assertEquals("For fighting", kit.description());
        assertEquals("minecraft:diamond", kit.icon());
        assertTrue(kit.everyone());
        assertEquals(Duration.ofHours(36), kit.cooldown().every());
        assertEquals(4, kit.items().size());
        KitItem blade = kit.items().getFirst();
        assertEquals(new KitItem("minecraft:diamond_sword", 1, "Blade", List.of("Sharp", "Very sharp"),
            Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), true), blade);
        assertEquals(List.of("minecraft:sharpness", "minecraft:unbreaking"), List.copyOf(blade.enchantments().keySet()));
        assertEquals("minecraft:diamond_sword", kit.items().get(1).material());
        assertEquals(200, kit.items().get(3).amount());
        assertEquals(Map.of("rare", 2), kit.keys());
    }

    @Test
    void theIconDefaultsToTheFirstItemAndTheNameToTheId() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(yaml(file("""
            food_pack:
              cooldown: 2h
              items:
                bread: {amount: 3}
            keys_only:
              cooldown: once
              keys:
                basic: 1
            """)), problems);
        assertEquals("", problemsText(problems));
        Kit food = settings.kit("food_pack");
        assertEquals("Food pack", food.name());
        assertEquals("minecraft:bread", food.icon());
        assertNull(food.description());
        assertFalse(food.everyone());
        assertEquals("minecraft:chest", settings.kit("keys_only").icon());
        assertTrue(settings.kit("keys_only").items().isEmpty());
    }

    @Test
    void reportsEveryMistakePrecisely() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(yaml(file("""
            good:
              cooldown: 24h
              colour: red
              items:
                bread: {}
                stone_sward: {}
                sword:
                  material: diamond_sword
                  amount: 0
                  name: "<red>Blade"
                  enchantments:
                    sharpness: 6
                    smite: 1
                  shiny: true
              keys:
                gold: 1
                rare: 99
            Bad Id:
              cooldown: once
              items:
                bread: {}
            give:
              cooldown: once
              items:
                bread: {}
            no_cooldown:
              items:
                bread: {}
            zero:
              cooldown: 0s
              items:
                bread: {}
            empty:
              cooldown: 1h
              items:
                mystery_item: {}
            """)), problems);
        String text = problemsText(problems);
        assertTrue(text.contains("'kits.good.colour' is not a kit setting"), text);
        assertTrue(text.contains("'kits.good.items.stone_sward.material' is missing and 'stone_sward' is not an item"), text);
        assertTrue(text.contains("'kits.good.items.sword.amount' must be between 1 and 640"), text);
        assertTrue(text.contains("'kits.good.items.sword.name' uses formatting tags"), text);
        assertTrue(text.contains("'kits.good.items.sword.enchantments.sharpness' must be between 1 and 5"), text);
        assertTrue(text.contains("'kits.good.items.sword.enchantments.smite' is not an enchantment"), text);
        assertTrue(text.contains("'kits.good.items.sword.shiny' is not an item setting"), text);
        assertTrue(text.contains("'kits.good.keys.gold' is not a crate"), text);
        assertTrue(text.contains("'kits.good.keys.rare' must be between 1 and 64"), text);
        assertTrue(text.contains("'kits.Bad Id' is not a valid kit id"), text);
        assertTrue(text.contains("'kits.give' uses a reserved id"), text);
        assertTrue(text.contains("'kits.no_cooldown.cooldown' is missing"), text);
        assertTrue(text.contains("'kits.zero.cooldown' must be between 1s and 365d"), text);
        assertTrue(text.contains("'kits.empty.items' gives nothing that works"), text);
        // Broken items are left out; a kit that still gives something stays.
        Kit good = settings.kit("good");
        assertNotNull(good);
        assertEquals(List.of("minecraft:bread"), good.items().stream().map(KitItem::material).toList());
        assertEquals(Map.of(), good.keys());
        assertNull(settings.kit("give"));
        assertNull(settings.kit("no_cooldown"));
        assertNull(settings.kit("zero"));
        assertNull(settings.kit("empty"));
    }

    @Test
    void unsafeEnchantmentsAllowHigherLevels() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(yaml(file("""
            op:
              cooldown: once
              items:
                diamond_sword:
                  unsafe-enchantments: true
                  enchantments:
                    sharpness: 10
            """)), problems);
        assertEquals("", problemsText(problems));
        assertEquals(10, settings.kit("op").items().getFirst().enchantments().get("minecraft:sharpness"));
    }

    @Test
    void keysNeedCrates() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings.Catalog noCrates = new KitsSettings.Catalog(CATALOG.items(), CATALOG.enchantments(), Set.of());
        parse(yaml(file("""
            k:
              cooldown: once
              items:
                bread: {}
              keys:
                basic: 1
            """)), problems, noCrates);
        assertTrue(problemsText(problems).contains("'kits.k.keys.basic' gives crate keys, but there are no crates"), problemsText(problems));
    }

    @Test
    void perksAreValidated() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(yaml("""
            block-in-combat: false
            reminders: false
            locked-kits: show
            kits:
              a:
                cooldown: once
                items:
                  bread: {}
            perks:
              blocked-in-combat: [ec, feed, HAT]
              close-on-combat: false
              hat:
                blocked: [carved_pumpkin, not_an_item]
            """), problems);
        String text = problemsText(problems);
        assertTrue(text.contains("'perks.blocked-in-combat' has 'feed', which is not a perk"), text);
        assertTrue(text.contains("'perks.hat.blocked' has 'not_an_item'"), text);
        assertEquals(2, problems.size(), text);
        assertEquals(EnumSet.of(Perk.EC, Perk.HAT), settings.perks().blockedInCombat());
        assertFalse(settings.perks().closeOnCombat());
        assertEquals(Set.of("minecraft:carved_pumpkin"), settings.perks().hatBlocked());
        assertFalse(settings.blockInCombat());
        assertFalse(settings.reminders());
        assertTrue(settings.showLocked());
    }

    @Test
    void unknownTopLevelSettingsAreReported() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        YamlConfiguration yaml = yaml(file("""
            a:
              cooldown: once
              items:
                bread: {}
            """));
        yaml.set("kit-cooldown", "1h");
        parse(yaml, problems);
        assertTrue(problemsText(problems).contains("'kit-cooldown' is not a kits setting"), problemsText(problems));
    }

    @Test
    void perkIdsAndNodes() {
        assertEquals(Perk.EC, Perk.byId(" EC "));
        assertNull(Perk.byId("feed"));
        assertNull(Perk.byId("heal"));
        Set<String> nodes = new java.util.HashSet<>();
        Set<String> labels = new java.util.HashSet<>();
        for (Perk perk : Perk.values()) {
            assertEquals("siftcore.perk." + perk.id(), perk.node());
            assertTrue(nodes.add(perk.node()));
            assertTrue(labels.add(perk.id()));
            for (String alias : perk.aliases()) {
                assertTrue(labels.add(alias), "alias " + alias + " is used once");
            }
        }
        assertFalse(Perk.HAT.opensScreen());
        assertTrue(Perk.TRASH.opensScreen());
        assertEquals("siftcore.perk.ec.others", Perk.EC_OTHERS);
    }

    @Test
    void plainTextRules() {
        assertEquals("minecraft:diamond", PlainText.id("DIAMOND"));
        assertEquals("minecraft:diamond", PlainText.id(" minecraft:diamond "));
        assertNull(PlainText.id("bad id"));
        assertNull(PlainText.id(""));
        assertNotNull(PlainText.problem("&cRed"));
        assertNotNull(PlainText.problem("§cRed"));
        assertNotNull(PlainText.problem("<bold>Hi"));
        assertNotNull(PlainText.problem("   "));
        assertNull(PlainText.problem("Salt & pepper"));
        assertNull(PlainText.problem("3 < 4"));
        assertEquals("Daily food", PlainText.capitalize("daily_food"));
    }
}
