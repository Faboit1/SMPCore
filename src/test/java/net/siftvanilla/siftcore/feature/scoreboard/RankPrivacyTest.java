package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.feature.integrations.IntegrationsFeature;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the scoreboard reads Show my rank (registered by the integrations feature): a stored "off" hides the rank only
 * while the setting is offered (LuckPerms connected), the same condition under which chat and the placeholders apply
 * it, so a player is never left hidden on the scoreboard without a switch to undo it.
 */
class RankPrivacyTest {

    private static final Logger LOGGER = Logger.getLogger("scoreboard-rank-privacy-test");
    private static final UUID HIDER = new UUID(3, 3);

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private final AtomicBoolean connected = new AtomicBoolean(true);

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = RankPrivacyTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        // As IntegrationsFeature.registerSettings does: Privacy, offered while LuckPerms is connected.
        this.settings.register(SettingCategories.PRIVACY, IntegrationsFeature.SHOW_MY_RANK, SettingOptions.<Boolean>builder()
            .order(5).availableWhen(this.connected::get).placeholder(false).build());
        this.settings.load(HIDER).get();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private static Player player(UUID uuid, Set<String> nodes) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Hider";
                case "isOnline" -> true;
                case "hasPermission" -> args[0] instanceof String node && nodes.contains(node);
                case "hashCode" -> uuid.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player(" + uuid + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void theScoreboardLooksTheSettingUpByTheIntegrationsId() {
        assertEquals(IntegrationsFeature.SHOW_MY_RANK.id(), ScoreboardFeature.SHOW_MY_RANK_ID,
            "the scoreboard reads the setting the integrations feature registers");
        assertSame(IntegrationsFeature.SHOW_MY_RANK, Boards.RankPrivacy.offered(this.settings, ScoreboardFeature.SHOW_MY_RANK_ID));
    }

    @Test
    void aStoredOffHidesTheRankOnlyWhileTheSwitchIsOffered() {
        Player hider = player(HIDER, Set.of(SharedSettings.HIDE_RANK_NODE));
        Boards.RankPrivacy privacy = Boards.RankPrivacy.of(this.settings, ScoreboardFeature.SHOW_MY_RANK_ID);
        assertTrue(privacy.shown().test(hider) && privacy.chosen().test(HIDER), "shown by default");

        assertEquals(SetResult.CHANGED, this.settings.set(hider, IntegrationsFeature.SHOW_MY_RANK, false, Change.dialog("Hider")));
        assertFalse(privacy.shown().test(hider), "turned off while offered: the scoreboard shows them as a member");
        assertFalse(privacy.chosen().test(HIDER));

        this.connected.set(false);
        assertNull(Boards.RankPrivacy.offered(this.settings, ScoreboardFeature.SHOW_MY_RANK_ID), "not offered without LuckPerms");
        assertTrue(privacy.shown().test(hider),
            "LuckPerms not connected: the switch is gone, so the stored choice no longer hides the rank the scoreboard reads"
                + " from group permissions");
        assertTrue(privacy.chosen().test(HIDER), "the global thread's change check sees the same, so the rank is read again");
        assertEquals(Boolean.FALSE, this.settings.get(HIDER, IntegrationsFeature.SHOW_MY_RANK), "the choice itself is kept");

        this.connected.set(true);
        assertFalse(privacy.shown().test(hider), "LuckPerms back: the kept choice applies again");
        assertFalse(privacy.chosen().test(HIDER));
    }

    @Test
    void thePermissionAndTheServerStillDecideWhileOffered() {
        Player hider = player(HIDER, Set.of(SharedSettings.HIDE_RANK_NODE));
        Boards.RankPrivacy privacy = Boards.RankPrivacy.of(this.settings, ScoreboardFeature.SHOW_MY_RANK_ID);
        this.settings.set(hider, IntegrationsFeature.SHOW_MY_RANK, false, Change.dialog("Hider"));

        assertTrue(privacy.shown().test(player(HIDER, Set.of())), "without siftcore.settings.hide-rank they read the default");

        this.settings.overrides(new Overrides(Map.of(), Map.of(), Set.of(ScoreboardFeature.SHOW_MY_RANK_ID)));
        assertTrue(privacy.shown().test(hider), "a setting the server hides reads the server's value (the default)");
        assertTrue(privacy.chosen().test(HIDER));

        this.settings.overrides(new Overrides(Map.of(), Map.of(), Set.of()));
        assertFalse(privacy.shown().test(hider));
    }

    @Test
    void withoutTheSettingEveryoneShowsTheirRank() {
        PlayerSettings bare = new PlayerSettings(null, null, LOGGER);
        Boards.RankPrivacy privacy = Boards.RankPrivacy.of(bare, ScoreboardFeature.SHOW_MY_RANK_ID);
        assertTrue(privacy.shown().test(player(HIDER, Set.of(SharedSettings.HIDE_RANK_NODE))));
        assertTrue(privacy.chosen().test(HIDER));
    }
}
