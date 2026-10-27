package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The home store against a real SQLite file migrated with the bundled migrations, plus the shipped files. */
class HomeStoreTest {

    @TempDir
    Path folder;

    private JdbcDatabase database;
    private HomeStore store;

    @BeforeEach
    void open() throws Exception {
        Logger logger = Logger.getLogger("homes-test");
        logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.folder.resolve("homes.db"), 2), logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.store = new HomeStore(this.database);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private static Home home(String name, double x) {
        return new Home(name, "world", x, 64, -x, 90f, 10f, 1_000L);
    }

    private Map<String, Home> fetch(UUID player) throws Exception {
        return this.store.fetch(player).get(10, TimeUnit.SECONDS);
    }

    @Test
    void notLoadedPlayersChangeNothing() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(HomeStore.Outcome.NOT_LOADED, this.store.set(player, home("home", 1), 2).outcome());
        assertTrue(fetch(player).isEmpty());
    }

    @Test
    void setMoveLimitDeleteAndPersist() throws Exception {
        UUID player = UUID.randomUUID();
        this.store.put(player, fetch(player));
        assertEquals(new HomeStore.SetResult(HomeStore.Outcome.CREATED, 1), this.store.set(player, home("home", 1), 2));
        assertEquals(new HomeStore.SetResult(HomeStore.Outcome.CREATED, 2), this.store.set(player, home("base", 2), 2));
        assertEquals(new HomeStore.SetResult(HomeStore.Outcome.LIMIT, 2), this.store.set(player, home("farm", 3), 2));
        assertEquals(new HomeStore.SetResult(HomeStore.Outcome.MOVED, 2), this.store.set(player, home("home", 9), 2));
        assertEquals(9, this.store.get(player, "home").orElseThrow().x());

        Map<String, Home> stored = fetch(player);
        assertEquals(List.of("base", "home"), List.copyOf(stored.keySet()), "sorted by name");
        Home saved = stored.get("home");
        assertEquals(9, saved.x());
        assertEquals(-9, saved.z());
        assertEquals(90f, saved.yaw());
        assertEquals(10f, saved.pitch());
        assertEquals("world", saved.world());

        assertTrue(this.store.delete(player, "base").get(10, TimeUnit.SECONDS));
        assertFalse(this.store.delete(player, "base").get(10, TimeUnit.SECONDS), "already gone");
        assertEquals(Set.of("home"), this.store.homes(player).orElseThrow().keySet());
        assertEquals(Set.of("home"), fetch(player).keySet());
    }

    @Test
    void homesSurviveARestart() throws Exception {
        UUID player = UUID.randomUUID();
        this.store.put(player, fetch(player));
        this.store.set(player, home("a", 1), 5);
        this.store.set(player, home("b", 2), 5);
        this.database.flush();
        this.store.forget(player);
        HomeStore fresh = new HomeStore(this.database);
        assertEquals(Set.of("a", "b"), fresh.fetch(player).get(10, TimeUnit.SECONDS).keySet());
    }

    @Test
    void aLoadRightAfterAChangeSeesTheChange() throws Exception {
        UUID player = UUID.randomUUID();
        this.store.put(player, fetch(player));
        for (int i = 0; i < 50; i++) {
            this.store.set(player, home("h" + i, i), 100);
            assertEquals(i + 1, fetch(player).size(), "load " + i + " is ordered after the write queued before it");
        }
    }

    @Test
    void offlineDeletesOnlyTouchStorage() throws Exception {
        UUID player = UUID.randomUUID();
        this.store.put(player, fetch(player));
        this.store.set(player, home("home", 1), 2);
        this.store.forget(player);
        assertFalse(this.store.isLoaded(player));
        assertTrue(this.store.delete(player, "home").get(10, TimeUnit.SECONDS));
        assertTrue(fetch(player).isEmpty());
    }

    @Test
    void concurrentSetsNeverPassTheLimit() throws Exception {
        UUID player = UUID.randomUUID();
        this.store.put(player, fetch(player));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HomeStore.SetResult>> results = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String name = "h" + i;
            results.add(pool.submit(() -> {
                start.await();
                return this.store.set(player, home(name, 1), 3);
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<HomeStore.SetResult> result : results) {
            if (result.get(10, TimeUnit.SECONDS).outcome() == HomeStore.Outcome.CREATED) {
                created++;
            }
        }
        pool.shutdown();
        assertEquals(3, created);
        assertEquals(3, this.store.count(player));
        assertEquals(3, fetch(player).size(), "memory and storage agree");
    }

    @Test
    void retainDropsPlayersWhoLeft() throws Exception {
        UUID stays = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        this.store.put(stays, Map.of());
        this.store.put(left, Map.of());
        this.store.retain(stays::equals);
        assertTrue(this.store.isLoaded(stays));
        assertFalse(this.store.isLoaded(left));
        assertEquals(1, this.store.loadedPlayers());
    }

    // ------------------------------------------------------------------ shipped files

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = HomeStoreTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void shippedConfigAndTextLoadCleanly() throws Exception {
        ConfigReader reader = new ConfigReader("features/homes.yml", yaml("features/homes.yml"));
        HomesSettings settings = HomesSettings.parse(reader, world -> true);
        assertEquals(List.of(), reader.problems());
        assertEquals(2, settings.defaultLimit());
        assertEquals(java.time.Duration.ofSeconds(3), settings.warmup());
        assertEquals(java.time.Duration.ofSeconds(5), settings.cooldown());
        assertTrue(settings.disabledWorlds().isEmpty());

        YamlConfiguration broken = yaml("features/homes.yml");
        broken.set("disabled-worlds", List.of("nowhere"));
        broken.set("default-limit", -1);
        ConfigReader brokenReader = new ConfigReader("features/homes.yml", broken);
        HomesSettings.parse(brokenReader, world -> !world.equals("nowhere"));
        assertEquals(2, brokenReader.problems().size(), brokenReader.problems().toString());

        Icons icons = new Icons(Icons.readIndex(HomeStoreTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(HomesMessages.class);
        YamlConfiguration langYaml = yaml("lang/homes.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/homes.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("homes.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }
}
