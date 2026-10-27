package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The combat settings: their groups and order, legacy values, config-dependent offering and the pure deciders. */
class CombatPlayerSettingsTest {

    private static final UUID VIEWER = new UUID(1, 1);
    private static final UUID FRIEND = new UUID(1, 2);
    private static final UUID MATE = new UUID(1, 3);
    private static final UUID STRANGER = new UUID(1, 4);
    private static final UUID OTHER = new UUID(1, 5);

    private static CombatSettings config(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/combat.yml", yaml);
        CombatSettings settings = CombatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static PlayerSettings registered(Relations relations, AtomicReference<CombatSettings> config) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, relations);
        CombatFeature.registerSettings(settings, relations, config::get);
        return settings;
    }

    private static List<String> ids(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().map(Registry.Entry::id).toList();
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    /** Friends: VIEWER and FRIEND. Team: VIEWER and MATE. */
    private static Relations related() {
        Relations relations = new Relations();
        FriendLookup friends = new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return Set.of(a, b).equals(Set.of(VIEWER, FRIEND));
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return player.equals(VIEWER) ? Set.of(FRIEND) : Set.of();
            }
        };
        TeamLookup teams = new TeamLookup() {
            @Override
            public Optional<Long> team(UUID player) {
                return player.equals(VIEWER) || player.equals(MATE) ? Optional.of(7L) : Optional.empty();
            }

            @Override
            public Optional<String> teamName(UUID player) {
                return team(player).map(id -> "Seven");
            }

            @Override
            public boolean friendlyFire(long team) {
                return false;
            }

            @Override
            public Set<UUID> members(long team) {
                return Set.of(VIEWER, MATE);
            }
        };
        relations.bind(friends, teams, IgnoreLookup.NONE);
        return relations;
    }

    @Test
    void settingsSitInTheirGroupsInCatalogOrder() throws Exception {
        AtomicReference<CombatSettings> config = new AtomicReference<>(config(CombatResourcesTest.yaml("features/combat.yml")));
        PlayerSettings settings = registered(new Relations(), config);
        assertEquals(List.of("combat-timer-display", "combat-tag-alert", "kill-feedback", "death-coordinates", "combat-end-notice",
            "quiet-in-combat", "death-recap"), ids(settings, SettingCategories.COMBAT.id()));
        assertEquals(List.of("death-messages", "kill-streak-announcements", "combat-log-announcements"),
            ids(settings, SettingCategories.ANNOUNCEMENTS.id()));
        assertEquals(List.of("staff-combat-alerts"), ids(settings, SettingCategories.STAFF.id()));
        assertEquals(CombatCommands.ADMIN, CombatFeature.STAFF_ALERTS.permission(), "only staff with the combat tools see it");
        assertTrue(settings.hasReader(SharedSettings.HIDE_COORDINATES), "death locations read streamer mode");
        assertEquals(ids(settings, SettingCategories.COMBAT.id()), offered(settings, SettingCategories.COMBAT.id()),
            "everything is offered with the shipped config");
        for (Registry.Entry<?> entry : settings.registry().entries()) {
            if (entry.setting() instanceof Choice<?> choice) {
                assertTrue(choice.options().size() <= Choice.MAX_OPTIONS, entry.id());
            }
        }
    }

    @Test
    void settingsTheConfigTurnsOffAreNotOffered() throws Exception {
        YamlConfiguration yaml = CombatResourcesTest.yaml("features/combat.yml");
        yaml.set("tag.action-bar", false);
        yaml.set("death-messages.enabled", false);
        yaml.set("logout.announce", false);
        yaml.set("streaks.announce-at", List.of());
        yaml.set("streaks.announce-ended-from", 0);
        AtomicReference<CombatSettings> config = new AtomicReference<>(config(yaml));
        PlayerSettings settings = registered(new Relations(), config);
        assertFalse(offered(settings, SettingCategories.COMBAT.id()).contains("combat-timer-display"), "no timer at all: nothing to place");
        assertEquals(List.of(), offered(settings, SettingCategories.ANNOUNCEMENTS.id()), "no line to filter");

        config.set(config(CombatResourcesTest.yaml("features/combat.yml")));
        assertTrue(offered(settings, SettingCategories.COMBAT.id()).contains("combat-timer-display"), "follows a reload");
        assertEquals(3, offered(settings, SettingCategories.ANNOUNCEMENTS.id()).size());
    }

    @Test
    void deathMessagesKeepsItsIdAndOldValues() throws Exception {
        Choice<DeathFilter> setting = CombatFeature.DEATH_MESSAGES;
        assertEquals("death-messages", setting.id());
        assertEquals(List.of("all", "pvp", "friends-team", "off"), setting.optionIds());
        assertEquals(DeathFilter.ALL, setting.defaultValue());
        assertEquals(Optional.of(DeathFilter.ALL), setting.decode("true"), "a stored 'on' row");
        assertEquals(Optional.of(DeathFilter.OFF), setting.decode("false"), "a stored 'off' row");
        assertEquals(Optional.of(DeathFilter.PVP), setting.decode(" PVP "));
        assertEquals(Optional.empty(), setting.decode("loud"));
        assertEquals("friends-team", setting.encode(DeathFilter.FRIENDS_TEAM));

        AtomicReference<CombatSettings> config = new AtomicReference<>(config(CombatResourcesTest.yaml("features/combat.yml")));
        PlayerSettings settings = registered(new Relations(), config);
        settings.overrides(new Overrides(Map.of("death-messages", "false"), Map.of(), Set.of()));
        assertEquals(DeathFilter.OFF, settings.get(STRANGER, setting), "a config default written as a switch still works");
        settings.overrides(new Overrides(Map.of(), Map.of("death-messages", "true"), Set.of()));
        assertTrue(settings.locked(setting));
        assertEquals(DeathFilter.ALL, settings.get(STRANGER, setting), "and so does a lock");
    }

    @Test
    @SuppressWarnings("unchecked")
    void friendsAndTeammatesIsOnlyOfferedWithFriendsOrTeams() throws Exception {
        AtomicReference<CombatSettings> config = new AtomicReference<>(config(CombatResourcesTest.yaml("features/combat.yml")));
        Relations relations = new Relations();
        PlayerSettings settings = registered(relations, config);
        Registry.Entry<DeathFilter> entry = (Registry.Entry<DeathFilter>) settings.registry().entry("death-messages");
        assertEquals(List.of("all", "pvp", "off"), settings.options(entry, node -> true).stream().map(Choice.Option::id).toList(),
            "no friends and no teams: the option would mean nobody");
        relations.bind(FriendLookup.NONE, related().teams(), IgnoreLookup.NONE);
        assertEquals(List.of("all", "pvp", "friends-team", "off"),
            settings.options(entry, node -> true).stream().map(Choice.Option::id).toList(), "teams alone are enough");
        assertFalse(CombatFeature.relationsAvailable(new Relations()));
        assertTrue(CombatFeature.relationsAvailable(related()));
    }

    @Test
    void deathFilterDecidesPerViewer() {
        assertTrue(DeathFilter.ALL.shows(false, false));
        assertTrue(DeathFilter.PVP.shows(true, false));
        assertFalse(DeathFilter.PVP.shows(false, true), "a fall death is not a player kill");
        assertTrue(DeathFilter.FRIENDS_TEAM.shows(false, true));
        assertFalse(DeathFilter.FRIENDS_TEAM.shows(true, false));
        assertFalse(DeathFilter.OFF.shows(true, true));

        Relations relations = related();
        assertTrue(DeathMessages.related(relations, VIEWER, FRIEND, null), "a friend died");
        assertTrue(DeathMessages.related(relations, VIEWER, STRANGER, MATE), "a teammate killed someone");
        assertFalse(DeathMessages.related(relations, VIEWER, STRANGER, OTHER), "strangers on both sides");
        assertFalse(DeathMessages.related(relations, VIEWER, STRANGER, null));
        assertFalse(DeathMessages.related(new Relations(), VIEWER, FRIEND, MATE), "no friends or teams on the server");
    }

    @Test
    void killNoticesNeverNameASharedAddress() {
        assertEquals(KillNotice.COUNTED, KillNotice.of(AntiFarm.Decision.COUNTED));
        assertEquals(KillNotice.NOT_COUNTED, KillNotice.of(AntiFarm.Decision.denied(AntiFarm.Reason.SAME_TEAM)));
        assertEquals(KillNotice.NOT_COUNTED, KillNotice.of(AntiFarm.Decision.denied(AntiFarm.Reason.FRIENDS)));
        assertEquals(KillNotice.NOT_COUNTED, KillNotice.of(AntiFarm.Decision.denied(AntiFarm.Reason.REPEATED_PAIR)));
        assertEquals(KillNotice.NOT_COUNTED_PLAIN, KillNotice.of(AntiFarm.Decision.denied(AntiFarm.Reason.SAME_IP)));
        assertEquals(KillNotice.NOT_COUNTED_PLAIN, KillNotice.of(AntiFarm.Decision.denied(AntiFarm.Reason.CANCELLED)));
    }

    @Test
    void killFeedbackFollowsTheKillersStyle() {
        assertNull(KillNotice.COUNTED.key(AlertStyle.OFF), "off tells the killer nothing");
        assertNull(KillNotice.NOT_COUNTED.key(AlertStyle.OFF));
        assertNull(KillNotice.NOT_COUNTED_PLAIN.key(AlertStyle.OFF));
        assertEquals(CombatMessages.KILL_COUNTED, KillNotice.COUNTED.key(AlertStyle.ACTIONBAR));
        assertEquals(CombatMessages.KILL_COUNTED, KillNotice.COUNTED.key(AlertStyle.CHAT));
        assertEquals(CombatMessages.KILL_COUNTED_TITLE, KillNotice.COUNTED.key(AlertStyle.TITLE));
        assertEquals(CombatMessages.KILL_NOT_COUNTED, KillNotice.NOT_COUNTED.key(AlertStyle.CHAT));
        assertEquals(CombatMessages.KILL_NOT_COUNTED_TITLE, KillNotice.NOT_COUNTED.key(AlertStyle.TITLE), "a title never gives the reason");
        assertEquals(CombatMessages.KILL_NOT_COUNTED_PLAIN, KillNotice.NOT_COUNTED_PLAIN.key(AlertStyle.ACTIONBAR));
        assertEquals(CombatMessages.KILL_NOT_COUNTED_TITLE, KillNotice.NOT_COUNTED_PLAIN.key(AlertStyle.TITLE));
    }

    @Test
    void deathLocationFollowsDeathCoordinatesAndStreamerMode() {
        assertNull(CombatListener.locationLine(false, false), "death-coordinates off: no line at all");
        assertNull(CombatListener.locationLine(false, true));
        assertEquals(CombatMessages.DEATH_LOCATION, CombatListener.locationLine(true, false));
        assertEquals(CombatMessages.DEATH_LOCATION_HIDDEN, CombatListener.locationLine(true, true), "streamer mode: only the world");
    }

    @Test
    void theEndNoticeFollowsItsStyle() {
        assertNull(CombatTimer.endNotice(AlertStyle.OFF));
        assertEquals(CombatMessages.TAG_ENDED, CombatTimer.endNotice(AlertStyle.ACTIONBAR));
        assertEquals(CombatMessages.TAG_ENDED, CombatTimer.endNotice(AlertStyle.CHAT));
        assertEquals(CombatMessages.TAG_ENDED_TITLE, CombatTimer.endNotice(AlertStyle.TITLE));
    }

    @Test
    void timerStylesAndTheBossBar() {
        assertEquals(List.of("actionbar", "bossbar", "both", "off"), CombatFeature.TIMER_DISPLAY.optionIds());
        assertTrue(TimerDisplay.actionBar(AlertStyle.ACTIONBAR) && !TimerDisplay.bossBar(AlertStyle.ACTIONBAR));
        assertTrue(TimerDisplay.bossBar(AlertStyle.BOSSBAR) && !TimerDisplay.actionBar(AlertStyle.BOSSBAR));
        assertTrue(TimerDisplay.actionBar(AlertStyle.BOTH) && TimerDisplay.bossBar(AlertStyle.BOTH));
        assertFalse(TimerDisplay.actionBar(AlertStyle.OFF) || TimerDisplay.bossBar(AlertStyle.OFF));
        Duration twenty = Duration.ofSeconds(20);
        assertEquals(1f, TimerDisplay.progress(20, twenty));
        assertEquals(0.5f, TimerDisplay.progress(10, twenty));
        assertEquals(0.05f, TimerDisplay.progress(1, twenty), 1e-6);
        assertEquals(1f, TimerDisplay.progress(3_600, twenty), "a long staff tag shows a full bar");
        assertEquals(0f, TimerDisplay.progress(0, Duration.ZERO));
    }

    @Test
    void alertsOfferTheSharedStyles() {
        assertEquals(List.of("chat", "actionbar", "title", "off"), CombatFeature.TAG_ALERT.optionIds());
        assertEquals(AlertStyle.CHAT, CombatFeature.TAG_ALERT.defaultValue());
        assertEquals(List.of("actionbar", "chat", "title", "off"), CombatFeature.KILL_FEEDBACK.optionIds());
        assertEquals(List.of("actionbar", "chat", "title", "off"), CombatFeature.END_NOTICE.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, CombatFeature.END_NOTICE.defaultValue());
        assertTrue(CombatFeature.DEATH_COORDINATES.defaultOn() && CombatFeature.DEATH_RECAP.defaultOn());
        assertTrue(CombatFeature.STREAK_ANNOUNCEMENTS.defaultOn() && CombatFeature.LOG_ANNOUNCEMENTS.defaultOn());
    }

    @Test
    void staffAlertChoices() {
        assertEquals(List.of("combat-logs", "combat-logs-and-farming", "off"), CombatFeature.STAFF_ALERTS.optionIds());
        assertEquals(StaffAlerts.COMBAT_LOGS, CombatFeature.STAFF_ALERTS.defaultValue());
        assertTrue(StaffAlerts.COMBAT_LOGS.combatLogs() && !StaffAlerts.COMBAT_LOGS.farming());
        assertTrue(StaffAlerts.LOGS_AND_FARMING.combatLogs() && StaffAlerts.LOGS_AND_FARMING.farming());
        assertFalse(StaffAlerts.OFF.combatLogs() || StaffAlerts.OFF.farming());
    }

    @Test
    void recapHearts() {
        assertEquals(10.0, CombatListener.hearts(20, 0));
        assertEquals(6.5, CombatListener.hearts(9, 4), "absorption counts");
        assertEquals(0.5, CombatListener.hearts(1, 0));
        assertEquals(6.7, CombatListener.hearts(13.4, 0), "one decimal");
        assertEquals(0.0, CombatListener.hearts(-1, -2));
    }

    @Test
    void streakAnnouncementsDependOnTheConfig() {
        assertTrue(CombatFeature.announcesStreaks(new CombatSettings.Streaks(List.of(5), 0)));
        assertTrue(CombatFeature.announcesStreaks(new CombatSettings.Streaks(List.of(), 5)));
        assertFalse(CombatFeature.announcesStreaks(CombatSettings.Streaks.OFF));
        assertNull(CombatFeature.STREAK_ANNOUNCEMENTS.permission());
    }
}
