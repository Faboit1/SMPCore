package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.feature.homes.HomesFeature;
import net.siftvanilla.siftcore.feature.rtp.RtpFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The teleport request settings: the old switch's rows still read, the settings sit in the Teleports &amp; homes group in
 * the catalog's order, the retired tpa-friends switch is gone (so friends-tpa takes over), and the friend options are
 * only offered while the server has friends (or teams), reading as nobody otherwise.
 */
class TpaSettingsTest {

    private static final UUID ALEX = UUID.randomUUID();

    private PlayerSettings settings;
    private Relations relations;

    @BeforeEach
    void setUp() {
        this.settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        this.relations = new Relations();
        SharedSettings.register(this.settings, this.relations);
        new Teleports(null, null, CombatStatus.NONE, this.settings);
        TpaFeature.registerSettings(this.settings, this.relations);
    }

    private static FriendLookup friends(boolean favourites) {
        return new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return false;
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return Set.of();
            }

            @Override
            public boolean favouritesEnabled() {
                return favourites;
            }
        };
    }

    private static final TeamLookup TEAMS = new TeamLookup() {
        @Override
        public Optional<Long> team(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> teamName(UUID player) {
            return Optional.empty();
        }

        @Override
        public boolean friendlyFire(long team) {
            return false;
        }

        @Override
        public Set<UUID> members(long team) {
            return Set.of();
        }
    };

    private List<String> options(Choice<?> setting) {
        return ids(this.settings.registry().entry(setting.id()));
    }

    private <T> List<String> ids(Registry.Entry<T> entry) {
        return this.settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    @Test
    void theOldSwitchStillReads() {
        assertEquals(Audience.EVERYONE, TpaFeature.REQUESTS.decodeOrNull("true"));
        assertEquals(Audience.NOBODY, TpaFeature.REQUESTS.decodeOrNull("false"));
        assertEquals(Audience.FRIENDS, TpaFeature.REQUESTS.decodeOrNull(" Friends "));
        assertNull(TpaFeature.REQUESTS.decodeOrNull("on"), "only the old stored values are aliases");
        assertEquals(Audience.EVERYONE, TpaFeature.REQUESTS.defaultValue());
        assertEquals(Audience.EVERYONE, TpaFeature.HERE_REQUESTS.defaultValue());
        assertNull(TpaFeature.HERE_REQUESTS.decodeOrNull("true"), "the new setting has no old values");
        assertFalse(TpaFeature.POPUP.defaultOn());
        assertTrue(TpaFeature.CONFIRM_HERE.defaultOn());
    }

    @Test
    void aServerLockWrittenTheOldWayStillWorks() {
        this.settings.overrides(new Overrides(Map.of(), Map.of("tpa-requests", "false"), Set.of()));
        assertEquals(Audience.NOBODY, this.settings.get(ALEX, TpaFeature.REQUESTS));
        assertTrue(this.settings.locked(TpaFeature.REQUESTS));
    }

    @Test
    void theSettingsSitInTheTeleportGroupInCatalogOrder() {
        List<String> ids = this.settings.registry().in("teleport").stream().map(Registry.Entry::id).toList();
        assertEquals(List.of("tpa-requests", "friends-tpa", "teleport-display", "tpahere-requests", "tpa-popup",
            "tpaccept-confirm-here"), ids);
        assertNull(this.settings.setting("tpa-friends"), "the retired switch is not registered any more");
        assertFalse(this.settings.registry().entry("friends-tpa").superseded(), "so friends-tpa takes over its rows");
        assertTrue(this.settings.hasReader(SharedSettings.FRIENDS_TPA), "teleport requests read the shared auto-accept");
        assertFalse(this.settings.registry().entry("tpa-requests").placeholder(), "who-can settings stay private");
        assertFalse(this.settings.registry().entry("tpahere-requests").placeholder());
    }

    /**
     * The whole Teleports &amp; homes group as the server builds it: shared settings, the teleports' countdown, then TPA,
     * homes and random teleport (in the composition root's order), giving the catalog's ten settings in its order.
     */
    @Test
    void theWholeGroupFollowsTheCatalog() {
        PlayerSettings all = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Relations withFriends = new Relations();
        withFriends.bind(friends(true), TeamLookup.NONE, IgnoreLookup.NONE);
        SharedSettings.register(all, withFriends);
        new Teleports(null, null, CombatStatus.NONE, all);
        boolean[] paidRegion = {false};
        HomesFeature.registerSettings(all);
        RtpFeature.registerSettings(all, () -> paidRegion[0]);
        TpaFeature.registerSettings(all, withFriends);

        List<String> ids = all.registry().in("teleport").stream().map(Registry.Entry::id).toList();
        assertEquals(List.of("tpa-requests", "friends-tpa", "homes-confirm-overwrite", "teleport-display", "homes-bare-command",
            "tpahere-requests", "tpa-popup", "tpaccept-confirm-here", "rtp-confirm-cost", "rtp-default"), ids);
        assertTrue(all.hasReader(SharedSettings.HIDE_COORDINATES), "homes and random teleport read streamer mode");

        Registry.Entry<?> confirmCost = all.registry().entry("rtp-confirm-cost");
        assertFalse(confirmCost.offered(), "no region costs money: the price question is not offered");
        paidRegion[0] = true;
        assertTrue(confirmCost.offered(), "follows the regions (a reload that adds a price)");
        long visible = all.registry().in("teleport").stream().filter(entry -> all.visible(entry, permission -> true)).count();
        assertEquals(10, visible, "all ten show with friends and a paid region");
    }

    @Test
    void homesAndRandomTeleportDeclareStreamerModeOnTheirOwn() {
        PlayerSettings homesOnly = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(homesOnly, new Relations());
        HomesFeature.registerSettings(homesOnly);
        assertTrue(homesOnly.hasReader(SharedSettings.HIDE_COORDINATES));
        PlayerSettings rtpOnly = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(rtpOnly, new Relations());
        RtpFeature.registerSettings(rtpOnly, () -> true);
        assertTrue(rtpOnly.hasReader(SharedSettings.HIDE_COORDINATES));
    }

    @Test
    void withoutFriendsTheFriendOptionsAreNotOfferedAndReadAsNobody() {
        assertEquals(List.of("everyone", "nobody"), options(TpaFeature.REQUESTS));
        assertFalse(this.settings.registry().entry("friends-tpa").offered(), "no auto-accept without a friends list");
        this.settings.overrides(new Overrides(Map.of("tpa-requests", "friends", "tpahere-requests", "friends-team"), Map.of(), Set.of()));
        assertEquals(Audience.NOBODY, this.settings.get(ALEX, TpaFeature.REQUESTS), "never opened up to everyone");
        assertEquals(Audience.NOBODY, this.settings.get(ALEX, TpaFeature.HERE_REQUESTS));
    }

    @Test
    void teamsAloneOfferFriendsAndTeammates() {
        this.relations.bind(FriendLookup.NONE, TEAMS, IgnoreLookup.NONE);
        assertEquals(List.of("everyone", "friends-team", "nobody"), options(TpaFeature.REQUESTS));
        assertEquals(List.of("everyone", "friends-team", "nobody"), options(TpaFeature.HERE_REQUESTS));
    }

    @Test
    void withFriendsEveryOptionIsOffered() {
        this.relations.bind(friends(true), TeamLookup.NONE, IgnoreLookup.NONE);
        assertEquals(List.of("everyone", "friends-team", "friends", "nobody"), options(TpaFeature.REQUESTS));
        Registry.Entry<?> auto = this.settings.registry().entry("friends-tpa");
        assertNotNull(auto);
        assertTrue(auto.offered(), "auto-accept is offered once friends exist and TPA reads it");
        assertEquals(List.of("nobody", "favourites", "all", "friends-team"), ids(auto));
        this.relations.bind(friends(false), TeamLookup.NONE, IgnoreLookup.NONE);
        assertEquals(List.of("nobody", "all", "friends-team"), ids(auto), "favourites only while the server has them");
    }
}
