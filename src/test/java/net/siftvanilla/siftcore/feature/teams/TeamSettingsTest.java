package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The teams settings: ids, options and their place in Friends &amp; teams and Staff; who may invite (with and without a
 * friends system); teammate login alert decisions; and when team chat mode comes back at login.
 */
class TeamSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("teams-settings-test");
    private static final long MINUTE = 60_000;

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private final Relations relations = new Relations();

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = TeamSettingsTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        TeamPrefs.register(this.settings, TeamsFeature.SPY, this.relations);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    @Test
    void idsOptionsAndPlaces() {
        assertEquals(List.of("chat", "actionbar", "off"), TeamPrefs.NOTICES.optionIds());
        assertEquals(AlertStyle.CHAT, TeamPrefs.NOTICES.defaultValue());
        assertEquals(List.of("joins-and-leaves", "joins", "off"), TeamPrefs.MEMBER_ALERTS.optionIds());
        assertEquals(TeamPrefs.MemberAlerts.JOINS, TeamPrefs.MEMBER_ALERTS.defaultValue());
        assertEquals(List.of("everyone", "friends", "nobody"), TeamPrefs.INVITES.optionIds());
        assertFalse(TeamPrefs.CHAT_STICKY.defaultOn());
        Registry registry = this.settings.registry();
        Map<String, Integer> social = Map.of("team-notices", 3, "team-member-alerts", 5, "team-invites", 6, "team-chat-sticky", 11);
        social.forEach((id, order) -> {
            assertEquals(SettingCategories.SOCIAL, registry.entry(id).category(), id);
            assertEquals(order, registry.entry(id).options().order(), id);
        });
        Registry.Entry<?> spy = registry.entry("team-spy");
        assertEquals(SettingCategories.STAFF, spy.category(), "team chat spy is with the staff settings");
        assertEquals(5, spy.options().order());
        assertEquals(TeamChat.SPY_PERMISSION, spy.setting().permission());
        assertFalse(registry.entry("team-invites").placeholder(), "who-can settings stay out of placeholders");
        assertTrue(this.settings.hasReader(SharedSettings.SOUND_TEAM_CHAT), "team chat plays the team chat sound");
        assertTrue(this.settings.hasReader(SharedSettings.SEEN_PRIVACY), "the team member lists apply the last-seen privacy");
    }

    @Test
    void whoMayInvite() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        this.settings.load(owner).get();
        assertTrue(this.relations.allows(this.settings.get(owner, TeamPrefs.INVITES), owner, stranger), "everyone by default");
        this.settings.setRaw(owner, TeamPrefs.INVITES.id(), "friends");
        assertEquals(Audience.NOBODY, this.settings.get(owner, TeamPrefs.INVITES),
            "friends only reads as nobody on a server without friends: nobody can be their friend");
        this.relations.bind(new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return (a.equals(owner) && b.equals(friend)) || (a.equals(friend) && b.equals(owner));
            }

            @Override
            public java.util.Set<UUID> friendsOf(UUID player) {
                return java.util.Set.of();
            }
        }, TeamLookup.NONE, IgnoreLookup.NONE);
        Audience audience = this.settings.get(owner, TeamPrefs.INVITES);
        assertEquals(Audience.FRIENDS, audience);
        assertTrue(this.relations.allows(audience, owner, friend));
        assertFalse(this.relations.allows(audience, owner, stranger));
        this.settings.setRaw(owner, TeamPrefs.INVITES.id(), "nobody");
        assertFalse(this.relations.allows(this.settings.get(owner, TeamPrefs.INVITES), owner, friend));
    }

    @Test
    void loginAlertDecisions() {
        long start = 1_000 * MINUTE;
        long now = start + 10 * MINUTE;
        assertTrue(MemberAlertRules.announceJoin(false, 0, now, 2 * MINUTE, start, MINUTE));
        assertFalse(MemberAlertRules.announceJoin(true, 0, now, 2 * MINUTE, start, MINUTE), "never a vanished member");
        assertFalse(MemberAlertRules.announceJoin(false, now - MINUTE, now, 2 * MINUTE, start, MINUTE), "a relog");
        assertTrue(MemberAlertRules.announceJoin(false, now - 3 * MINUTE, now, 2 * MINUTE, start, MINUTE), "back after the grace");
        assertFalse(MemberAlertRules.announceJoin(false, 0, start + 30_000, 2 * MINUTE, start, MINUTE), "right after a restart");

        assertTrue(MemberAlertRules.tellJoin(TeamPrefs.MemberAlerts.JOINS, false, false));
        assertTrue(MemberAlertRules.tellJoin(TeamPrefs.MemberAlerts.JOINS_AND_LEAVES, false, false));
        assertFalse(MemberAlertRules.tellJoin(TeamPrefs.MemberAlerts.OFF, false, false));
        assertFalse(MemberAlertRules.tellJoin(TeamPrefs.MemberAlerts.JOINS, true, false), "never someone the viewer ignores");
        assertFalse(MemberAlertRules.tellJoin(TeamPrefs.MemberAlerts.JOINS, false, true), "the friend alert already told them");

        assertTrue(MemberAlertRules.tellLeave(TeamPrefs.MemberAlerts.JOINS_AND_LEAVES, false, false, false, false));
        assertFalse(MemberAlertRules.tellLeave(TeamPrefs.MemberAlerts.JOINS, false, false, false, false), "logins only");
        assertFalse(MemberAlertRules.tellLeave(TeamPrefs.MemberAlerts.JOINS_AND_LEAVES, false, false, false, true), "came back");
        assertFalse(MemberAlertRules.tellLeave(TeamPrefs.MemberAlerts.JOINS_AND_LEAVES, false, false, true, false), "vanished");
        assertFalse(MemberAlertRules.tellLeave(TeamPrefs.MemberAlerts.JOINS_AND_LEAVES, false, true, false, false), "friend alert");

        assertTrue(MemberAlertRules.friendsTellJoin(true, "all", "true", false));
        assertFalse(MemberAlertRules.friendsTellJoin(false, "all", "true", false), "not friends");
        assertFalse(MemberAlertRules.friendsTellJoin(true, "favourites", "true", false), "not a favourite: teams tells");
        assertTrue(MemberAlertRules.friendsTellJoin(true, "favourites", "true", true), "a favourite: the friends alert tells");
        assertFalse(MemberAlertRules.friendsTellJoin(true, "off", "true", true));
        assertFalse(MemberAlertRules.friendsTellJoin(true, "all", "false", false), "the member doesn't tell friends");
        assertFalse(MemberAlertRules.friendsTellJoin(true, null, null, false), "no friends feature");
        assertTrue(MemberAlertRules.friendsTellLeave(true, "true", "true"));
        assertFalse(MemberAlertRules.friendsTellLeave(true, "false", "true"), "friend leave alerts are off by default");
        assertFalse(MemberAlertRules.friendsTellLeave(true, null, null));
    }

    @Test
    void teamChatModeComesBack() {
        UUID player = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Team team = Team.create(7, "Seven", owner, 50, false).withMember(new TeamMember(player, TeamRole.MEMBER, 100));
        String membership = TeamChat.membership(team, player);
        assertEquals("7:100", membership, "the team and when the player joined it");
        assertTrue(TeamChat.restores(true, "7:100", "7:100"));
        assertFalse(TeamChat.restores(false, "7:100", "7:100"), "only for players who keep it");
        assertFalse(TeamChat.restores(true, TeamChat.MODE_OFF, "7:100"), "it was off when they left");
        assertFalse(TeamChat.restores(true, "7:100", "8:300"), "turned on in another team");
        assertFalse(TeamChat.restores(true, "7:100", "7:500"), "removed and invited back: another membership");
        assertFalse(TeamChat.restores(true, "7", "7:100"), "an old team-only value never matches");
        assertFalse(TeamChat.restores(true, "7:100", null), "no team now");
        assertFalse(TeamChat.restores(true, null, "7:100"));

        assertEquals("7:100", TeamChat.atQuit(true, "7:100"), "on when they left: remembered");
        assertEquals(TeamChat.MODE_OFF, TeamChat.atQuit(false, "7:100"), "off when they left: forgotten");
        assertEquals(TeamChat.MODE_OFF, TeamChat.atQuit(true, null), "no team: nothing to come back to");
    }

    @Test
    void teamNewsAlwaysConfirmsTheActor() {
        for (AlertStyle picked : List.of(AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)) {
            assertEquals(picked, TeamActions.newsStyle(picked, false), "the rest of the team as they picked: " + picked);
        }
        assertEquals(AlertStyle.CHAT, TeamActions.newsStyle(AlertStyle.OFF, true),
            "with team news off the actor still gets their confirmation, in chat");
        assertEquals(AlertStyle.ACTIONBAR, TeamActions.newsStyle(AlertStyle.ACTIONBAR, true), "where the actor picked");
        assertEquals(AlertStyle.CHAT, TeamActions.newsStyle(AlertStyle.CHAT, true));
    }
}
