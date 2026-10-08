package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CratesSettingsTest {

    static final CratesSettings.Catalog CATALOG = new CratesSettings.Catalog(
        Set.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:cooked_beef", "minecraft:experience_bottle",
            "minecraft:iron_pickaxe", "minecraft:iron_chestplate", "minecraft:diamond", "minecraft:golden_apple", "minecraft:chest",
            "minecraft:emerald", "minecraft:diamond_pickaxe", "minecraft:diamond_sword", "minecraft:diamond_chestplate",
            "minecraft:enchanted_book", "minecraft:totem_of_undying", "minecraft:ender_chest", "minecraft:diamond_block",
            "minecraft:netherite_scrap", "minecraft:diamond_helmet", "minecraft:diamond_leggings", "minecraft:diamond_boots",
            "minecraft:enchanted_golden_apple", "minecraft:elytra", "minecraft:shulker_box", "minecraft:netherite_ingot",
            "minecraft:netherite_sword", "minecraft:netherite_pickaxe", "minecraft:netherite_chestplate", "minecraft:beacon",
            "minecraft:name_tag", "minecraft:stone"),
        Map.of("minecraft:efficiency", 5, "minecraft:unbreaking", 3, "minecraft:protection", 4, "minecraft:fortune", 3,
            "minecraft:sharpness", 5, "minecraft:mending", 1, "minecraft:looting", 3, "minecraft:feather_falling", 4),
        Set.of("minecraft:zombie", "minecraft:skeleton", "minecraft:blaze"),
        Set.of("world", "world_nether"));

    static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    static YamlConfiguration bundled() throws Exception {
        try (InputStream in = CratesSettingsTest.class.getClassLoader().getResourceAsStream("features/crates.yml")) {
            assertNotNull(in, "features/crates.yml is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static CratesSettings parse(YamlConfiguration yaml, List<ConfigProblem> problems) {
        ConfigReader reader = new ConfigReader("features/crates.yml", yaml);
        CratesSettings settings = CratesSettings.parse(reader, CATALOG, MoneyFormat.defaults());
        problems.addAll(reader.problems());
        return settings;
    }

    private static Set<String> paths(List<ConfigProblem> problems) {
        Set<String> paths = new TreeSet<>();
        for (ConfigProblem problem : problems) {
            paths.add(problem.path());
        }
        return paths;
    }

    private static final String HEAD = """
        open-cooldown: 1s
        block-in-combat: true
        bulk-open: 10
        quick-open: true
        join-reminder: true
        grants: {remember: 90d}
        rarities:
          common: {label: "Common"}
          rare: {label: "Rare", audit: true, announce: true}
        keyall:
          enabled: true
          interval: 4h
          crate: basic
          amount: 1
          missed-delay: 10m
          include-vanished: false
          include-afk: true
          countdown: {chat: [5m, 1m], action-bar: 10s}
        """;

    @Test
    void bundledFileParsesWithoutProblems() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(bundled(), problems);
        assertEquals(List.of(), problems);
        assertEquals(List.of("basic", "rare", "epic", "legendary"), settings.crates().stream().map(Crate::id).toList());
        assertEquals(List.of("common", "uncommon", "rare", "epic", "legendary"), settings.rarities().stream().map(Rarity::id).toList());
        for (Crate crate : settings.crates()) {
            double total = crate.rewards().stream().mapToDouble(Reward::weight).sum();
            assertEquals(100.0, total, 1e-9, crate.id() + " weights add up to 100, so weights read as percentages");
            assertTrue(crate.rewards().stream().anyMatch(r -> r.kind() instanceof Reward.Money), crate.id() + " pays some money");
            assertTrue(crate.rewards().stream().anyMatch(r -> r.kind() instanceof Reward.Shards), crate.id() + " pays some shards");
            assertTrue(crate.rewards().stream().anyMatch(r -> r.kind() instanceof Reward.Keys), crate.id() + " can give keys");
        }
        CratesSettings.Keyall keyall = settings.keyall();
        assertTrue(keyall.enabled());
        assertEquals(Duration.ofHours(4), keyall.interval());
        assertEquals("basic", keyall.crate());
        assertEquals(1, keyall.amount());
        assertEquals(List.of(Duration.ofMinutes(5), Duration.ofMinutes(1)), keyall.chatAt());
        assertEquals(Duration.ofSeconds(10), keyall.actionBarFrom());
        assertEquals(Duration.ofSeconds(1), settings.openCooldown());
        assertTrue(settings.blockInCombat(), "crates can't be opened in combat");
        assertEquals(10, settings.bulkOpen(), "up to 10 keys open in a row");
        assertTrue(keyall.includeAfk(), "AFK players get keyall keys");
        assertFalse(keyall.includeVanished());
        assertEquals(Duration.ofDays(90), settings.rememberGrants());
        assertTrue(settings.rarity("legendary").announce());
        assertFalse(settings.rarity("uncommon").audit());
        assertTrue(settings.rarity("rare").audit());

        Reward pickaxe = settings.crate("basic").reward("pickaxe");
        Reward.Item item = assertInstanceOf(Reward.Item.class, pickaxe.kind());
        assertEquals("minecraft:iron_pickaxe", item.item());
        assertEquals(Map.of("minecraft:efficiency", 3, "minecraft:unbreaking", 2), item.enchants());
        Reward money = settings.crate("basic").reward("money-small");
        assertEquals("$750", money.display());
        assertFalse(money.customDisplay());
        assertEquals("1 Rare key", settings.crate("basic").reward("rare-key").display());
        assertEquals("3 Basic keys", settings.crate("rare").reward("basic-keys").display());
        assertEquals("10 shards", settings.crate("basic").reward("shards").display());
        Reward spawner = settings.crate("legendary").reward("blaze-spawner");
        assertEquals("blaze", assertInstanceOf(Reward.Spawner.class, spawner.kind()).mobId());
    }

    @Test
    void everyRewardKindAndDefaultDisplays() throws Exception {
        String text = HEAD + """
            crates:
              basic:
                name: "Basic"
                icon: chest
                blocks: ["world 10 64 -5", "world_nether 0 70 0"]
                rewards:
                  stone: {item: stone, amount: 32, weight: 2.5}
                  named: {item: diamond_sword, name: "Starter blade", lore: ["Sharp", "Old"], weight: 1, rarity: rare}
                  book: {item: enchanted_book, enchants: {mending: 1}, weight: 1}
                  cash: {money: 1.5k, weight: 1}
                  shards: {shards: 1, weight: 1}
                  key: {keys: basic, amount: 2, weight: 1}
                  zombies: {spawner: zombie, amount: 2, weight: 1}
                  rank: {commands: ["/say %player% won", "lp user %player% parent add patron"], display: "the Patron rank", icon: name_tag, weight: 0.5}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml(text), problems);
        assertEquals(List.of(), problems);
        Crate crate = settings.crate("basic");
        assertEquals(List.of(new BlockKey("world", 10, 64, -5), new BlockKey("world_nether", 0, 70, 0)), crate.blocks());
        assertEquals("32 stone", crate.reward("stone").display());
        assertEquals(2.5, crate.reward("stone").weight());
        assertEquals("common", crate.reward("stone").rarity(), "the first rarity is the default");
        Reward.Item named = assertInstanceOf(Reward.Item.class, crate.reward("named").kind());
        assertEquals("Starter blade", named.name());
        assertEquals(List.of("Sharp", "Old"), named.lore());
        assertEquals("Starter blade", crate.reward("named").display());
        assertEquals("$1,500", crate.reward("cash").display());
        assertEquals(1500, assertInstanceOf(Reward.Money.class, crate.reward("cash").kind()).amount());
        assertEquals("1 shard", crate.reward("shards").display());
        assertEquals("2 Basic keys", crate.reward("key").display());
        assertEquals("2 zombie spawners", crate.reward("zombies").display());
        Reward.Command command = assertInstanceOf(Reward.Command.class, crate.reward("rank").kind());
        assertEquals(List.of("say %player% won", "lp user %player% parent add patron"), command.commands(), "a leading slash is dropped");
        assertEquals("the Patron rank", crate.reward("rank").display());
        assertEquals("minecraft:name_tag", crate.reward("rank").icon());
    }

    @Test
    void mistakesAreReportedPreciselyAndTheRestStillLoads() throws Exception {
        String text = HEAD + """
            crates:
              basic:
                name: "Basic"
                icon: not_an_item
                blocks: ["world 1 2", "mars 0 0 0", "world 5 5 5", "world 5 5 5"]
                surprise: true
                rewards:
                  good: {item: diamond, weight: 1}
                  two-kinds: {item: diamond, money: 5, weight: 1}
                  no-kind: {weight: 1}
                  bad-item: {item: diamond_shovel_of_doom, weight: 1}
                  bad-weight: {money: 5, weight: 0}
                  bad-rarity: {money: 5, weight: 1, rarity: mythic}
                  bad-money: {money: 1.5, weight: 1}
                  bad-enchant: {item: diamond_sword, enchants: {sharpness: 9, sweeping_fire: 1}, weight: 1}
                  unknown-crate: {keys: mythic, weight: 1}
                  bad-mob: {spawner: dragon_king, weight: 1}
                  command-no-display: {commands: ["say hi"], weight: 1}
                  tagged: {money: 5, weight: 1, display: "<red>Five"}
                  typo: {money: 5, wieght: 1}
                  Bad_Id: {money: 5, weight: 1}
              total:
                name: "Total"
                rewards:
                  a: {money: 5, weight: 1}
              empty:
                name: "Empty"
                rewards:
                  broken: {item: nope, weight: 1}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml(text), problems);
        assertEquals(Set.of(
            "crates.basic.icon",
            "crates.basic.blocks",
            "crates.basic.surprise",
            "crates.basic.rewards.two-kinds",
            "crates.basic.rewards.no-kind",
            "crates.basic.rewards.bad-item.item",
            "crates.basic.rewards.bad-weight.weight",
            "crates.basic.rewards.bad-rarity.rarity",
            "crates.basic.rewards.bad-money.money",
            "crates.basic.rewards.bad-enchant.enchants.sharpness",
            "crates.basic.rewards.bad-enchant.enchants.sweeping_fire",
            "crates.basic.rewards.unknown-crate.keys",
            "crates.basic.rewards.bad-mob.spawner",
            "crates.basic.rewards.command-no-display.display",
            "crates.basic.rewards.command-no-display.icon",
            "crates.basic.rewards.tagged.display",
            "crates.basic.rewards.typo.wieght",
            "crates.basic.rewards.typo.weight",
            "crates.basic.rewards.Bad_Id",
            "crates.total",
            "crates.empty.rewards.broken.item",
            "crates.empty.rewards"), paths(problems));
        assertEquals(List.of("basic"), settings.crates().stream().map(Crate::id).toList(),
            "a reserved id and a crate without a working reward are left out");
        Crate basic = settings.crate("basic");
        assertEquals(List.of("good"), basic.rewards().stream().map(Reward::id).toList(),
            "every reward with a problem is left out, the rest of the crate still works");
        assertEquals("minecraft:chest", basic.icon(), "a bad icon falls back to a chest");
        assertEquals(List.of(new BlockKey("world", 5, 5, 5)), basic.blocks());
        assertEquals(3, problems.stream().filter(p -> p.path().equals("crates.basic.blocks")).count(),
            "bad format, unknown world and a duplicate are each reported");
    }

    @Test
    void unsafeEnchantmentsNeedToBeAllowed() throws Exception {
        String text = HEAD + """
            crates:
              basic:
                rewards:
                  sword: {item: diamond_sword, enchants: {sharpness: 10}, unsafe-enchants: true, weight: 1}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml(text), problems);
        assertEquals(List.of(), problems);
        Reward.Item item = assertInstanceOf(Reward.Item.class, settings.crate("basic").reward("sword").kind());
        assertEquals(Map.of("minecraft:sharpness", 10), item.enchants());
        assertEquals("Basic", settings.crate("basic").name(), "the name defaults to the id");
    }

    @Test
    void aKeyallWithAnUnknownCrateIsSwitchedOff() throws Exception {
        String text = HEAD.replace("crate: basic", "crate: mythic").replace("chat: [5m, 1m]", "chat: [5m, 5s, 5h, soon]") + """
            crates:
              basic:
                rewards:
                  a: {money: 5, weight: 1}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml(text), problems);
        assertEquals(Set.of("keyall.crate", "keyall.countdown.chat"), paths(problems));
        assertEquals(3, problems.stream().filter(p -> p.path().equals("keyall.countdown.chat")).count());
        assertFalse(settings.keyall().enabled());
        assertEquals(List.of(Duration.ofMinutes(5)), settings.keyall().chatAt());
    }

    @Test
    void duplicateBlocksAcrossCratesAreReported() throws Exception {
        String text = HEAD + """
            crates:
              basic:
                blocks: ["world 1 1 1"]
                rewards:
                  a: {money: 5, weight: 1}
              rare:
                blocks: ["world 1 1 1"]
                rewards:
                  a: {money: 5, weight: 1}
            """;
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml(text), problems);
        assertEquals(Set.of("crates.rare.blocks"), paths(problems));
        assertEquals(List.of(new BlockKey("world", 1, 1, 1)), settings.crate("basic").blocks());
        assertTrue(settings.crate("rare").blocks().isEmpty());
    }

    @Test
    void noCratesAndNoRaritiesAreReported() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        CratesSettings settings = parse(yaml("""
            open-cooldown: 1s
            block-in-combat: false
            bulk-open: 0
            quick-open: true
            join-reminder: true
            grants: {remember: 90d}
            keyall: {enabled: false, interval: 4h, crate: basic, amount: 1, missed-delay: 10m, include-vanished: false,
                     include-afk: false, countdown: {chat: [], action-bar: 0s}}
            """), problems);
        assertTrue(paths(problems).containsAll(Set.of("rarities", "crates", "keyall.crate")), paths(problems).toString());
        assertFalse(settings.blockInCombat(), "block-in-combat: false is read");
        assertEquals(0, settings.bulkOpen(), "bulk-open: 0 is read");
        assertFalse(settings.keyall().includeAfk(), "include-afk: false is read");
        assertTrue(settings.crates().isEmpty());
        assertEquals("common", settings.rarities().getFirst().id());
        assertNull(settings.crate("basic"));
    }

    @Test
    void blockKeysParse() {
        assertEquals(new BlockKey("world", -10, 64, 300), BlockKey.parse("  world  -10 64 300 "));
        assertEquals("world -10 64 300", new BlockKey("world", -10, 64, 300).toString());
        for (String bad : List.of("world 1 2", "world a b c", "", "world 1 2 3 4")) {
            boolean refused;
            try {
                BlockKey.parse(bad);
                refused = false;
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            assertTrue(refused, "'" + bad + "' is refused");
        }
    }

    @Test
    void plainTextCheck() {
        assertNull(Ids.plainTextProblem("8 diamonds"));
        assertNull(Ids.plainTextProblem("a <3 shaped cake"));
        assertNotNull(Ids.plainTextProblem("<red>Hot"));
        assertNotNull(Ids.plainTextProblem("§cHot"));
        assertNotNull(Ids.plainTextProblem("&cHot"));
        assertNotNull(Ids.plainTextProblem("   "));
        assertEquals("minecraft:diamond", Ids.normalize(" DIAMOND "));
        assertEquals("custom:thing", Ids.normalize("custom:thing"));
        assertNull(Ids.normalize("bad item"));
        assertEquals("diamond sword", Ids.plainName("minecraft:diamond_sword"));
    }
}
