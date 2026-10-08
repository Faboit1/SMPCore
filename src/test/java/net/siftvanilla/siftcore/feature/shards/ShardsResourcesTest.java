package net.siftvanilla.siftcore.feature.shards;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shipped shard shop config and text, and the purchase arithmetic. */
class ShardsResourcesTest {

    private static final Set<String> ITEMS = Set.of("minecraft:experience_bottle", "minecraft:golden_apple",
        "minecraft:totem_of_undying", "minecraft:shulker_box", "minecraft:diamond");
    private static final Predicate<String> EXISTS = ITEMS::contains;

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = ShardsResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static ShardsSettings parse(YamlConfiguration yaml, List<ConfigProblem> problems) {
        ConfigReader reader = new ConfigReader("features/shards.yml", yaml);
        ShardsSettings settings = ShardsSettings.parse(reader, EXISTS);
        problems.addAll(reader.problems());
        return settings;
    }

    @Test
    void defaultShopParsesWithoutProblems() throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        ShardsSettings settings = parse(yaml("features/shards.yml"), problems);
        assertEquals(List.of(), problems);
        assertEquals(500, settings.confirmAbove());
        assertEquals(List.of("basic-key", "rare-key", "epic-key", "legendary-key", "experience", "golden-apples", "totem", "shulker-box"),
            settings.offers().stream().map(ShardOffer::id).toList(), "in file order");
        ShardOffer basic = settings.offer("basic-key");
        assertEquals(new ShardOffer("basic-key", ShardOffer.Kind.KEY, "Basic key", "Opens a basic crate at spawn", "basic", 1, 50, 16, ""),
            basic);
        assertEquals(200, settings.offer("rare-key").price());
        assertEquals(600, settings.offer("epic-key").price());
        assertEquals(1500, settings.offer("legendary-key").price());
        ShardOffer bottles = settings.offer("experience");
        assertEquals(ShardOffer.Kind.ITEM, bottles.kind());
        assertEquals("minecraft:experience_bottle", bottles.target());
        assertEquals(32, bottles.amount());
        assertEquals("", bottles.name(), "an empty name means the item's own name");
        assertNull(settings.offer("money"), "shards never buy money");
    }

    @Test
    void brokenOffersAreLeftOutAndReported() throws Exception {
        YamlConfiguration yaml = yaml("features/shards.yml");
        yaml.set("shop.offers.Bad Id.type", "item");
        yaml.set("shop.offers.bad-item.type", "item");
        yaml.set("shop.offers.bad-item.item", "unobtainium");
        yaml.set("shop.offers.bad-item.price", 10);
        yaml.set("shop.offers.bad-crate.type", "key");
        yaml.set("shop.offers.bad-crate.crate", "Not A Crate");
        yaml.set("shop.offers.bad-crate.price", 10);
        yaml.set("shop.offers.free.type", "item");
        yaml.set("shop.offers.free.item", "diamond");
        yaml.set("shop.offers.free.price", 0);
        yaml.set("shop.offers.money.type", "money");
        yaml.set("shop.offers.money.price", 10);
        yaml.set("shop.offers.too-many.type", "item");
        yaml.set("shop.offers.too-many.item", "diamond");
        yaml.set("shop.offers.too-many.price", 10);
        yaml.set("shop.offers.too-many.max", 65);
        yaml.set("shop.offers.diamonds.type", "item");
        yaml.set("shop.offers.diamonds.item", "minecraft:diamond");
        yaml.set("shop.offers.diamonds.price", 100);
        yaml.set("shop.offers.diamonds.max", 3);
        yaml.set("shop.offers.diamonds.permission", "siftcore.shards.vip");
        List<ConfigProblem> problems = new ArrayList<>();
        ShardsSettings settings = parse(yaml, problems);
        List<String> text = problems.stream().map(ConfigProblem::toString).toList();
        assertTrue(text.stream().anyMatch(p -> p.contains("Bad Id")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("unobtainium")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("Not A Crate")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'shop.offers.free.price'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'shop.offers.money.type'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'shop.offers.too-many.max'")), text.toString());
        for (String broken : List.of("bad-item", "bad-crate", "free", "money", "too-many")) {
            assertNull(settings.offer(broken), broken + " is not sold");
        }
        ShardOffer diamonds = settings.offer("diamonds");
        assertNotNull(diamonds, "the valid offer is sold");
        assertEquals("siftcore.shards.vip", diamonds.permission());
        assertEquals(8 + 1, settings.offers().size());
    }

    @Test
    void purchaseArithmetic() {
        ShardOffer bottles = new ShardOffer("x", ShardOffer.Kind.ITEM, "", "", "minecraft:experience_bottle", 32, 40, 16, "");
        assertEquals(640, bottles.total(16).orElseThrow());
        assertEquals(512, bottles.given(16));
        ShardOffer huge = new ShardOffer("y", ShardOffer.Kind.KEY, "", "", "rare", 1, Long.MAX_VALUE / 2, 64, "");
        assertTrue(huge.total(3).isEmpty(), "an overflowing total is refused, not wrapped");
        assertEquals(1, ShardOffer.clampUnits(0, 16));
        assertEquals(16, ShardOffer.clampUnits(500, 16));
        assertEquals(7, ShardOffer.clampUnits(7, 16));
        assertEquals(64 * 3 + 10, ShardMath.capacity(new int[] {54, 64}, 3, 64), "free room in matching stacks plus empty slots");
        assertEquals(0, ShardMath.capacity(new int[] {}, 0, 64));
        assertEquals(0, ShardMath.capacity(new int[] {1}, 2, 0), "a broken stack size fits nothing");
        assertArrayEquals(new boolean[] {true, true, true, true, false, false, false, false},
            ShardMath.pick(new int[] {64, 64, 64, 64, 64, 64, 64, 64}, 300), "512 items with room for 300: four whole stacks");
        assertArrayEquals(new boolean[] {true, false, true}, ShardMath.pick(new int[] {64, 64, 32}, 100),
            "a smaller last stack still fits after a big one didn't");
        assertArrayEquals(new boolean[] {true}, ShardMath.pick(new int[] {32}, 1_000));
        assertArrayEquals(new boolean[] {false}, ShardMath.pick(new int[] {32}, 0), "a full inventory takes nothing");
        assertArrayEquals(new boolean[] {false}, ShardMath.pick(new int[] {32}, -5));
        assertEquals(0, ShardMath.left(10, 50));
        assertEquals(40, ShardMath.left(90, 50));
        assertTrue(ShardMath.needsConfirmation(10, 0), "0 asks every time");
        assertFalse(ShardMath.needsConfirmation(499, 500));
        assertTrue(ShardMath.needsConfirmation(500, 500));
    }

    @Test
    void textLoadsAndEveryEntryBelongsToAMessage() throws Exception {
        Icons icons = new Icons(Icons.readIndex(ShardsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(ShardsMessages.class);
        YamlConfiguration langYaml = yaml("lang/shards.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/shards.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("shards.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }
}
