package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SpawnersSettingsTest {

    /** Every key is valid except the ones named "fake". */
    static final SpawnersSettings.Catalog CATALOG = new SpawnersSettings.Catalog(
        key -> !key.contains("fake"), key -> !key.contains("fake"));

    static YamlConfiguration bundled() throws Exception {
        try (InputStream in = SpawnersSettingsTest.class.getClassLoader().getResourceAsStream("features/spawners.yml")) {
            assertNotNull(in, "features/spawners.yml is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    static SpawnersSettings parseBundled() throws Exception {
        ConfigReader reader = new ConfigReader("features/spawners.yml", bundled());
        SpawnersSettings settings = SpawnersSettings.parse(reader, CATALOG);
        assertEquals(List.of(), reader.problems(), "the bundled file has no problems");
        return settings;
    }

    private static List<ConfigProblem> problems(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        ConfigReader reader = new ConfigReader("features/spawners.yml", config);
        SpawnersSettings.parse(reader, CATALOG);
        return reader.problems();
    }

    @Test
    void bundledDefaults() throws Exception {
        SpawnersSettings settings = parseBundled();
        assertEquals(Duration.ofSeconds(30), settings.interval());
        assertEquals(32, settings.radius());
        assertEquals(9, settings.slotsPerSpawner());
        assertEquals(1000, settings.defaultCap());
        assertEquals(SpawnersSettings.BreakStorage.CLAIM_BOX, settings.breakStorage());
        assertTrue(settings.requireSilkTouch());
        assertTrue(settings.openRequiresSneak());
        assertTrue(settings.blockInCombat());
        assertEquals(32, settings.remoteRange());
        assertFalse(settings.naturalPickup());
        assertEquals(Set.of("zombie", "skeleton", "spider", "cave_spider", "creeper", "pig", "cow", "chicken", "sheep", "slime",
            "magma_cube", "blaze", "enderman", "witch", "zombified_piglin", "iron_golem"), settings.mobs().keySet());
        assertEquals(settings.mobs().keySet(), settings.enabledMobs());
        MobDef golem = settings.mob("iron_golem");
        assertEquals("Iron golem", golem.name());
        assertEquals("iron golem", golem.lowerName());
        assertEquals(Map.of("minecraft:iron_ingot", 3), golem.drops().stream()
            .filter(d -> d.item().equals("minecraft:iron_ingot")).collect(Collectors.toMap(DropEntry::item, DropEntry::min)));
        assertEquals(54, settings.slots("iron_golem"));
        assertEquals(9, settings.slots("zombie"));
        assertEquals(9, settings.slots("unknown"));
        assertEquals(1000, settings.cap("zombie"));
        DropEntry spiderEye = settings.mob("spider").drops().stream().filter(d -> d.item().equals("minecraft:spider_eye")).findFirst().orElseThrow();
        assertEquals(0.333, spiderEye.chance(), 1e-9);
    }

    @Test
    void mobsKeepFileOrderAndOptionalValues() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
            cycle: {interval: 10s}
            activation: {radius: 16, count-vanished: true, count-afk: false}
            storage: {slots-per-spawner: 3, xp-per-spawner: 100, flush-interval: 30s}
            stacking: {default-cap: 50}
            interaction: {open-requires-sneak: false, remote-range: 0, block-in-combat: false}
            breaking: {require-silk-touch: false, storage: sell, max-claim-stacks: 5}
            placement: {max-per-chunk: 4, disabled-worlds: [World_Nether]}
            natural-spawners: {silk-touch-pickup: true}
            xp: {apply-mending: false}
            list-limit: 20
            mobs:
              minecraft:pig:
                name: "Piggy"
                kills-per-cycle: 0.5
                xp-per-kill: 1
                stack-cap: 20
                slots: 2
                drops:
                  porkchop: {min: 1, max: 3}
              cow:
                enabled: false
                drops:
                  minecraft:leather: {max: 2, chance: 0.5}
            """);
        ConfigReader reader = new ConfigReader("features/spawners.yml", config);
        SpawnersSettings settings = SpawnersSettings.parse(reader, CATALOG);
        assertEquals(List.of(), reader.problems());
        assertEquals(List.of("pig", "cow"), List.copyOf(settings.mobs().keySet()));
        assertEquals(Set.of("pig"), settings.enabledMobs());
        assertEquals(Set.of("world_nether"), settings.disabledWorlds());
        assertEquals(SpawnersSettings.BreakStorage.SELL, settings.breakStorage());
        assertEquals(Duration.ofSeconds(10), settings.interval());
        assertTrue(settings.countVanished());
        assertFalse(settings.countAfk());
        assertTrue(settings.naturalPickup());
        assertFalse(settings.blockInCombat());
        assertEquals(0, settings.remoteRange());
        assertFalse(settings.applyMending());
        assertEquals(20, settings.listLimit());
        assertEquals(4, settings.maxPerChunk());
        assertEquals(20, settings.cap("pig"));
        assertEquals(50, settings.cap("cow"));
        assertEquals(2, settings.slots("pig"));
        assertEquals(3, settings.slots("cow"));
        assertEquals("Piggy", settings.mob("pig").name());
        assertEquals(0.5, settings.mob("pig").killsPerCycle());
        assertEquals(new DropEntry("minecraft:leather", 0, 2, 0.5), settings.mob("cow").drops().getFirst());
        assertEquals("Cow", settings.mob("cow").name(), "a missing name falls back to the id");
        assertNull(settings.mob("zombie"));
    }

    @Test
    void mistakesAreReportedPrecisely() throws Exception {
        List<ConfigProblem> found = problems("""
            cycle: {interval: 1s}
            activation: {radius: 32, count-vanished: false, count-afk: true}
            storage: {slots-per-spawner: 9, xp-per-spawner: 6000, flush-interval: 60s}
            stacking: {default-cap: 1000}
            interaction: {open-requires-sneak: true, remote-range: 32}
            breaking: {require-silk-touch: true, storage: lava, max-claim-stacks: 108}
            placement: {max-per-chunk: 0, disabled-worlds: []}
            mobs:
              fake_mob:
                drops:
                  bone: {max: 1}
              "Bad Id":
                drops:
                  bone: {max: 1}
              zombie:
                kills-per-cycle: 500
                drops:
                  fake_item: {max: 1}
                  bone: {min: 3, max: 1}
                  arrow: {min: 0, max: 0}
            """);
        Set<String> paths = found.stream().map(ConfigProblem::path).collect(Collectors.toSet());
        assertTrue(paths.contains("cycle.interval"), paths.toString());
        assertTrue(paths.contains("breaking.storage"), paths.toString());
        assertTrue(paths.contains("mobs.fake_mob"), paths.toString());
        assertTrue(paths.contains("mobs.Bad Id"), paths.toString());
        assertTrue(paths.contains("mobs.zombie.kills-per-cycle"), paths.toString());
        assertTrue(paths.contains("mobs.zombie.drops.fake_item"), paths.toString());
        assertTrue(paths.contains("mobs.zombie.drops.bone.max"), paths.toString());
        assertTrue(paths.contains("mobs.zombie.drops.arrow.max"), paths.toString());
        assertTrue(paths.contains("mobs.zombie.drops"), "a mob that makes nothing is reported: " + paths);
    }

    @Test
    void anEmptyMobListIsAProblem() throws Exception {
        List<ConfigProblem> found = problems("""
            cycle: {interval: 30s}
            activation: {radius: 32, count-vanished: false, count-afk: true}
            storage: {slots-per-spawner: 9, xp-per-spawner: 6000, flush-interval: 60s}
            stacking: {default-cap: 1000}
            interaction: {open-requires-sneak: true, remote-range: 32}
            breaking: {require-silk-touch: true, storage: claim-box, max-claim-stacks: 108}
            placement: {max-per-chunk: 0, disabled-worlds: []}
            mobs: {}
            """);
        assertTrue(found.stream().anyMatch(p -> p.path().equals("mobs")), found.toString());
    }
}
