package net.siftvanilla.siftcore.feature.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

/** The shipped spawn config and text load without a single problem. */
class SpawnResourcesTest {

    private static final Set<String> WORLDS = Set.of("world", "world_nether", "world_the_end");

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = SpawnResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static SpawnSettings parse(YamlConfiguration yaml, ConfigReader[] reader) {
        reader[0] = new ConfigReader("features/spawn.yml", yaml);
        return SpawnSettings.parse(reader[0], WORLDS::contains, key -> !key.contains("unknown"));
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader[] reader = new ConfigReader[1];
        SpawnSettings settings = parse(yaml("features/spawn.yml"), reader);
        assertEquals(List.of(), reader[0].problems());
        assertEquals("world", settings.defaultWorld());
        assertEquals(Duration.ofSeconds(3), settings.warmup());
        assertEquals(Duration.ZERO, settings.cooldown());
        assertTrue(settings.firstJoinAtSpawn() && settings.firstJoinWelcome() && settings.respawnAtSpawn());
        assertTrue(settings.protection().enabled());
        assertEquals(SpawnSettings.Shape.RADIUS, settings.protection().shape());
        assertEquals(64, settings.protection().radius());
        assertEquals(SpawnSettings.DEFAULT_ALLOWED, settings.protection().allowedInteractions());
        assertEquals(SpawnSettings.DEFAULT_BLOCKED_REASONS, settings.protection().blockedSpawnReasons());
        assertTrue(settings.borders().enabled());
        assertEquals(new BorderSpec(0, 0, 10_000), settings.borders().border("world"));
        assertEquals(new BorderSpec(0, 0, 5_000), settings.borders().border("world_nether"));
        assertEquals(new BorderSpec(0, 0, 6_000), settings.borders().border("world_the_end"));
    }

    @Test
    void radiusRegionFollowsTheSpawnPoint() throws Exception {
        ConfigReader[] reader = new ConfigReader[1];
        SpawnSettings settings = parse(yaml("features/spawn.yml"), reader);
        ProtectedRegion region = settings.region(new SpawnPoint("world", 250.5, 80, -100.5, 90, 0));
        assertInstanceOf(ProtectedRegion.Radius.class, region);
        assertTrue(region.contains("world", 250.5 + 60, 10, -100.5));
        assertFalse(region.contains("world", 0, 80, 0));
        assertEquals(ProtectedRegion.NONE, settings.region(null), "no spawn point, nothing to protect");
    }

    @Test
    void cuboidShapeAndDisabledProtection() throws Exception {
        YamlConfiguration yaml = yaml("features/spawn.yml");
        yaml.set("protection.shape", "cuboid");
        yaml.set("protection.cuboid.from", "-20 0 -20");
        yaml.set("protection.cuboid.to", "20 100 20");
        ConfigReader[] reader = new ConfigReader[1];
        SpawnSettings settings = parse(yaml, reader);
        assertEquals(List.of(), reader[0].problems());
        ProtectedRegion region = settings.region(new SpawnPoint("world", 500, 64, 500, 0, 0));
        assertTrue(region.contains("world", 0, 50, 0), "a cuboid ignores where the spawn point is");
        assertFalse(region.contains("world", 500, 64, 500));
        yaml.set("protection.enabled", false);
        assertEquals(ProtectedRegion.NONE, parse(yaml, reader).region(new SpawnPoint("world", 0, 64, 0, 0, 0)));
    }

    @Test
    void mistakesAreReportedPrecisely() throws Exception {
        YamlConfiguration yaml = yaml("features/spawn.yml");
        yaml.set("spawn.default-world", "lobby");
        yaml.set("protection.radius", -5);
        yaml.set("protection.cuboid.from", "1 2");
        yaml.set("protection.allowed-interactions", List.of("#minecraft:buttons", "Not A Block!", "unknown_block"));
        yaml.set("protection.blocked-spawn-reasons", List.of("natural", "sneezing"));
        yaml.set("world-border.worlds.world.size", 0);
        yaml.set("world-border.worlds.creative.size", 1000);
        ConfigReader[] reader = new ConfigReader[1];
        SpawnSettings settings = parse(yaml, reader);
        List<String> problems = reader[0].problems().stream().map(ConfigProblem::toString).toList();
        assertEquals(8, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'spawn.default-world'") && p.contains("lobby")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'protection.radius'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'protection.cuboid.from'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Not A Block!")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown_block")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("sneezing")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'world-border.worlds.world.size'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'world-border.worlds.creative'")), problems.toString());
        assertEquals(List.of("#minecraft:buttons"), settings.protection().allowedInteractions(), "valid entries are kept");
        assertEquals(Set.of(SpawnReason.NATURAL), settings.protection().blockedSpawnReasons());
    }

    @Test
    void textLoadsAndEveryEntryBelongsToAMessage() throws Exception {
        Icons icons = new Icons(Icons.readIndex(SpawnResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(SpawnMessages.class);
        YamlConfiguration langYaml = yaml("lang/spawn.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/spawn.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("spawn.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }
}
