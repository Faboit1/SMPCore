package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rows players stored before the teleport settings became choices: the old {@code tpa-requests} switch reads as a
 * who-can choice, and the retired {@code tpa-friends} switch moves to the shared {@code friends-tpa} when the player
 * loads (on SQLite, through the real settings store).
 */
class TpaSettingsStoreTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = TpaSettingsStoreTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        Relations relations = new Relations();
        relations.bind(new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return true;
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return Set.of();
            }
        }, TeamLookup.NONE, IgnoreLookup.NONE);
        SharedSettings.register(this.settings, relations);
        new Teleports(null, null, CombatStatus.NONE, this.settings);
        TpaFeature.registerSettings(this.settings, relations);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private void insert(UUID player, String setting, String value) throws Exception {
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                ps.setString(3, value);
                ps.executeUpdate();
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    private Map<String, String> rows(UUID player) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            Map<String, String> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT setting, value FROM settings WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return map;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void oldSwitchRowsReadAsTheirChoices() throws Exception {
        insert(ALEX, "tpa-requests", "false");
        insert(ALEX, "tpa-friends", "true");
        insert(SAM, "tpa-requests", "true");
        insert(SAM, "tpa-friends", "false");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.load(SAM).get(5, TimeUnit.SECONDS);
        assertEquals(Audience.NOBODY, this.settings.get(ALEX, TpaFeature.REQUESTS), "off reads as nobody");
        assertEquals(AutoAccept.ALL, this.settings.get(ALEX, SharedSettings.FRIENDS_TPA), "friends skipped requests: all friends");
        assertEquals(Audience.EVERYONE, this.settings.get(SAM, TpaFeature.REQUESTS), "on reads as everyone");
        assertEquals(AutoAccept.NOBODY, this.settings.get(SAM, SharedSettings.FRIENDS_TPA));
        assertEquals("all", rows(ALEX).get("friends-tpa"), "the retired switch's row moved to friends-tpa");
        assertFalse(rows(SAM).containsKey("friends-tpa"), "off is the default: no row");
    }

    @Test
    void theNextChangeRewritesTheOldRow() throws Exception {
        insert(ALEX, "tpa-requests", "false");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, TpaFeature.REQUESTS, Audience.FRIENDS, Change.dialog("Alex")));
        assertEquals("friends", rows(ALEX).get("tpa-requests"));
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, TpaFeature.REQUESTS, Audience.EVERYONE, Change.feature()));
        assertFalse(rows(ALEX).containsKey("tpa-requests"), "back to the default: the row is gone");
    }
}
