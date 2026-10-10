package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
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
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The last-seen privacy of team member lists ({@code seen-privacy} in {@code /team} and {@code /team info}): the rule,
 * how a stored row resolves, and the members a viewer may see, loaded or offline (read in one query), with the staff
 * bypass and the server's lock and hidden list.
 */
class TeamSeenTest {

    private static final Logger LOGGER = Logger.getLogger("team-seen-test");

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private final Relations relations = new Relations();
    private TeamSeen seen;

    private final UUID viewer = UUID.randomUUID();
    private final UUID friendFriendsOnly = UUID.randomUUID();
    private final UUID strangerFriendsOnly = UUID.randomUUID();
    private final UUID friendNobody = UUID.randomUUID();
    private final UUID strangerDefault = UUID.randomUUID();
    private final UUID loadedNobody = UUID.randomUUID();
    private final UUID loadedDefault = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        this.database = TeamsTestDatabase.open(this.dir);
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        SharedSettings.register(this.settings, this.relations);
        TeamPrefs.register(this.settings, TeamsFeature.SPY, this.relations);
        Set<UUID> viewerFriends = Set.of(this.friendFriendsOnly, this.friendNobody);
        this.relations.bind(new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return a.equals(TeamSeenTest.this.viewer) && viewerFriends.contains(b)
                    || b.equals(TeamSeenTest.this.viewer) && viewerFriends.contains(a);
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return player.equals(TeamSeenTest.this.viewer) ? viewerFriends : Set.of();
            }
        }, TeamLookup.NONE, IgnoreLookup.NONE);
        this.seen = new TeamSeen(this.settings, this.relations, new TeamStore(this.database));
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private static Player player(UUID uuid, Set<String> nodes) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Viewer";
                case "hasPermission" -> args[0] instanceof String node && nodes.contains(node);
                case "hashCode" -> uuid.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player(" + uuid + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private List<UUID> members() {
        return List.of(this.viewer, this.friendFriendsOnly, this.strangerFriendsOnly, this.friendNobody, this.strangerDefault,
            this.loadedNobody, this.loadedDefault);
    }

    /** Stores the members' choices: three offline rows, one loaded player who picked nobody, one loaded on the default. */
    private void storeChoices() throws Exception {
        Change change = Change.api("test");
        assertTrue(this.settings.set(this.friendFriendsOnly, SharedSettings.SEEN_PRIVACY, Audience.FRIENDS, change).succeeded());
        assertTrue(this.settings.set(this.strangerFriendsOnly, SharedSettings.SEEN_PRIVACY, Audience.FRIENDS, change).succeeded());
        assertTrue(this.settings.set(this.friendNobody, SharedSettings.SEEN_PRIVACY, Audience.NOBODY, change).succeeded());
        this.settings.load(this.loadedNobody).get(10, TimeUnit.SECONDS);
        this.settings.load(this.loadedDefault).get(10, TimeUnit.SECONDS);
        assertTrue(this.settings.set(this.loadedNobody, SharedSettings.SEEN_PRIVACY, Audience.NOBODY, change).succeeded());
        assertFalse(this.settings.loaded(this.friendFriendsOnly), "offline members are read from the table");
    }

    private Set<UUID> visible(Set<String> nodes) throws Exception {
        return this.seen.visible(player(this.viewer, nodes), members()).get(10, TimeUnit.SECONDS);
    }

    @Test
    void theRule() {
        assertTrue(TeamSeen.shows(Audience.EVERYONE, false, false, false));
        assertFalse(TeamSeen.shows(Audience.FRIENDS, false, false, false), "friends only, a teammate who is no friend");
        assertTrue(TeamSeen.shows(Audience.FRIENDS, false, true, false), "friends only, a friend");
        assertFalse(TeamSeen.shows(Audience.NOBODY, false, true, false), "nobody, even friends");
        assertTrue(TeamSeen.shows(Audience.NOBODY, true, false, false), "never hidden from yourself");
        assertTrue(TeamSeen.shows(Audience.NOBODY, false, false, true), "staff who look players up always see it");
    }

    @Test
    void storedRowsResolveLikeTheSettings() {
        assertEquals(Audience.FRIENDS, TeamSeen.resolve("friends", null, Audience.EVERYONE));
        assertEquals(Audience.EVERYONE, TeamSeen.resolve(null, null, Audience.EVERYONE), "no row: the server's default");
        assertEquals(Audience.NOBODY, TeamSeen.resolve(null, null, Audience.NOBODY), "no row: a configured default");
        assertEquals(Audience.EVERYONE, TeamSeen.resolve("not-a-value", null, Audience.EVERYONE), "an unreadable row");
        assertEquals(Audience.EVERYONE, TeamSeen.resolve("nobody", Audience.EVERYONE, Audience.EVERYONE), "the lock wins");
    }

    @Test
    void membersAViewerMaySee() throws Exception {
        storeChoices();
        assertEquals(Set.of(this.viewer, this.friendFriendsOnly, this.strangerDefault, this.loadedDefault), visible(Set.of()),
            "friends-only shows to friends, nobody hides from everyone, no row is the default (everyone)");
        assertEquals(Set.copyOf(members()), visible(Set.of(TeamSeen.BYPASS)), "staff who look players up see every time");
        assertEquals(Set.of(), this.seen.visible(player(this.viewer, Set.of()), List.of()).get(10, TimeUnit.SECONDS));
    }

    @Test
    void theServersLockAndHiddenListApply() throws Exception {
        storeChoices();
        this.settings.overrides(new Overrides(Map.of(), Map.of("seen-privacy", "everyone"), Set.of()));
        assertEquals(Set.copyOf(members()), visible(Set.of()), "locked to everyone: every stored choice is ignored");
        this.settings.overrides(new Overrides(Map.of("seen-privacy", "nobody"), Map.of(), Set.of("seen-privacy")));
        assertEquals(Set.of(this.viewer), visible(Set.of()), "hidden with the default nobody: only yourself");
        this.settings.overrides(new Overrides(Map.of("seen-privacy", "friends"), Map.of(), Set.of()));
        assertEquals(Set.of(this.viewer, this.friendFriendsOnly), visible(Set.of()),
            "a configured default (friends) applies to members without a row, loaded or offline");
    }
}
