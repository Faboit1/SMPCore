package net.siftvanilla.siftcore.feature.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Point;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shipped AFK config and text, the zone geometry and the zone file. */
class AfkResourcesTest {

    @TempDir
    Path dir;

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = AfkResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static AfkSettings parse(YamlConfiguration yaml, List<ConfigProblem> problems) {
        ConfigReader reader = new ConfigReader("features/afk.yml", yaml);
        AfkSettings settings = AfkSettings.parse(reader);
        problems.addAll(reader.problems());
        return settings;
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        AfkSettings settings = parse(yaml("features/afk.yml"), problems);
        assertEquals(List.of(), problems);
        assertEquals(Duration.ofMinutes(5), settings.afkAfter());
        assertEquals(5.0, settings.lookThreshold());
        assertEquals(Duration.ofSeconds(3), settings.manualGrace());
        assertEquals(Duration.ofMinutes(15), settings.motionLimit());
        assertTrue(settings.kickEnabled());
        assertEquals(Duration.ofMinutes(30), settings.kickAfter());
        assertEquals(Duration.ofMinutes(1), settings.warnBefore());
        assertTrue(settings.zoneEnabled());
        assertEquals(AfkSettings.DEFAULT_ZONE, settings.zone());
        assertTrue(settings.zoneSafe());
        assertEquals(Duration.ofSeconds(60), settings.interval());
        assertEquals(1, settings.shards());
        assertEquals(Map.of(), settings.rankShards(), "ranks pay no extra shards (docs/monetization.md)");
        assertEquals(0, settings.dailyCap());
        assertEquals(Duration.ofSeconds(1), settings.statusEvery(), "the countdown is shown again every second");
        assertNotNull(settings.shardSound(), "a sound plays when a shard is paid");
        assertEquals("minecraft:block.amethyst_block.chime", settings.shardSound().name().asString());
        AfkClock.Timing timing = settings.timing();
        assertEquals(300_000, timing.afkAfterMillis());
        assertEquals(1_800_000, timing.kickAfterMillis());
        assertEquals(900_000, timing.motionLimitMillis());
    }

    @Test
    void theShardSoundCanBeChangedOrTurnedOff() throws Exception {
        YamlConfiguration yaml = yaml("features/afk.yml");
        yaml.set("rewards.sound.sound", "entity.experience_orb.pickup");
        yaml.set("rewards.sound.pitch", 1.5);
        AfkSettings changed = parse(yaml, new java.util.ArrayList<>());
        assertEquals("minecraft:entity.experience_orb.pickup", changed.shardSound().name().asString());
        assertEquals(1.5f, changed.shardSound().pitch());
        yaml.set("rewards.sound.enabled", false);
        assertNull(parse(yaml, new java.util.ArrayList<>()).shardSound(), "turned off: no sound");
    }

    @Test
    void rankTiersPayTheBestGrantedAmount() throws Exception {
        YamlConfiguration yaml = yaml("features/afk.yml");
        yaml.createSection("rewards.ranks", Map.of("supporter", 2, "patron", 2, "elite", 3, "legend", 4));
        AfkSettings settings = parse(yaml, new java.util.ArrayList<>());
        assertEquals(1, settings.shardsFor(tier -> false));
        assertEquals(2, settings.shardsFor(tier -> tier.equals("supporter")));
        assertEquals(4, settings.shardsFor(tier -> true));
        assertEquals(3, settings.shardsFor(tier -> tier.equals("elite") || tier.equals("patron")));
    }

    @Test
    void mistakesAreReportedPrecisely() throws Exception {
        YamlConfiguration yaml = yaml("features/afk.yml");
        yaml.set("detection.afk-after", "2s");
        yaml.set("kick.warn-before", "40m");
        yaml.set("zone.anchor", "nowhere");
        yaml.set("zone.from", "1 2");
        yaml.set("zone.arrival", "100 70 100");
        yaml.set("rewards.interval", "1s");
        yaml.set("rewards.ranks.Bad Tier", 3);
        yaml.set("rewards.status-every", "500ms");
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        AfkSettings settings = parse(yaml, problems);
        List<String> text = problems.stream().map(ConfigProblem::toString).toList();
        assertEquals(8, problems.size(), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'detection.afk-after'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'kick.warn-before'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'zone.anchor'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'zone.from'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'zone.arrival'") && p.contains("outside")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'rewards.interval'")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("Bad Tier")), text.toString());
        assertTrue(text.stream().anyMatch(p -> p.contains("'rewards.status-every'")), text.toString());
        assertEquals(Duration.ofMinutes(5), settings.afkAfter(), "fallbacks are used");
        assertEquals(Duration.ZERO, settings.warnBefore());
        assertNull(settings.zone().arrival(), "an arrival outside the zone is dropped");
    }

    @Test
    void zoneAnchoredToTheSpawnFollowsIt() {
        ZoneSpec spec = new ZoneSpec(ZoneSpec.Anchor.SPAWN, "world", new Corner(16, -16, -6), new Corner(28, 24, 6),
            new Point(22.5, 0, 0.5, 90, 0));
        ZoneBox box = spec.resolve(100, 70, -200);
        assertEquals(new ZoneBox("world", 116, 54, -206, 128, 94, -194, new Point(122.5, 70, -199.5, 90, 0)), box);
        assertTrue(box.contains("world", 120.3, 70, -200));
        assertTrue(box.contains("world", 128.99, 94.9, -193.01), "both corners are included");
        assertFalse(box.contains("world", 129.0, 70, -200));
        assertFalse(box.contains("world_nether", 120, 70, -200));
        assertEquals(122.5, box.centerX());
        assertEquals(-199.5, box.centerZ());
        assertEquals(13L * 41 * 13, box.volume());
        ZoneSpec absolute = new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, "afk", new Corner(5, 60, 5), new Corner(-5, 70, -5), null);
        assertEquals(new ZoneBox("afk", -5, 60, -5, 5, 70, 5, null), absolute.resolve(1_000, 1_000, 1_000), "corners in any order");
        assertEquals(8, absolute.resolve(0, 0, 0).cornerPoints().length);
    }

    @Test
    void pointsAndCornersParseStrictly() {
        assertEquals(new Point(1.5, 64, -3, 0, 0), Point.parse(" 1.5 64 -3 "));
        assertEquals(new Point(1, 2, 3, 90, 90), Point.parse("1 2 3 90 120"), "pitch is clamped");
        assertThrows(IllegalArgumentException.class, () -> Point.parse("1 2"));
        assertThrows(IllegalArgumentException.class, () -> Point.parse("1 2 NaN"));
        assertThrows(IllegalArgumentException.class, () -> Point.parse("1 2 99999999999"));
        assertEquals(new Corner(-4, 70, 12), Corner.parse("-4 70 12"));
        assertThrows(IllegalArgumentException.class, () -> Corner.parse("1.5 2 3"));
        assertThrows(IllegalArgumentException.class, () -> Corner.parse("1 2 3 4"));
    }

    @Test
    void theZoneSetInGameSurvivesARestart() throws Exception {
        Path file = this.dir.resolve("data/afk-zone.yml");
        ZoneStore store = new ZoneStore(file, Runnable::run, Logger.getLogger("afk-test"));
        assertEquals(Optional.empty(), store.load());
        ZoneSpec spec = new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, "world", new Corner(10, 60, 10), new Corner(20, 70, 20),
            new Point(15.5, 61, 15.5, 180, 10));
        store.save(spec).get();
        assertEquals(Optional.of(spec), new ZoneStore(file, Runnable::run, Logger.getLogger("afk-test")).load());
        store.save(new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, "world", new Corner(0, 0, 0), new Corner(1, 1, 1), null)).get();
        assertNull(store.load().orElseThrow().arrival());
        store.clear().get();
        assertFalse(Files.exists(file));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "world: world\nfrom: '1 2'\nto: '3 4 5'\n");
        assertThrows(java.io.IOException.class, store::load, "a broken file is reported, not guessed");
    }

    @Test
    void textLoadsAndEveryEntryBelongsToAMessage() throws Exception {
        Icons icons = new Icons(Icons.readIndex(AfkResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(AfkMessages.class);
        YamlConfiguration langYaml = yaml("lang/afk.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/afk.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("afk.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
        assertEquals("AFK", lang.plain(AfkMessages.PLACEHOLDER));
    }
}
