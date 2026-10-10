package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.config.TestSettings;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Kill credit between players who just stopped being friends: removing a friend, killing them and claiming the bounty
 * on them gives nothing, because the anti-farm rules ask whether the two were friends within the friends window.
 */
class KillTrackerFriendsTest {

    private static final UUID KILLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long NOW = 1_000_000_000L;

    /** Friends graph after "/friend remove": no longer friends, but removed a moment ago. */
    private static final class JustUnfriended implements FriendLookup {

        final List<Duration> windows = new CopyOnWriteArrayList<>();

        @Override
        public boolean friends(UUID a, UUID b) {
            return false;
        }

        @Override
        public Set<UUID> friendsOf(UUID player) {
            return Set.of();
        }

        @Override
        public boolean recentlyFriends(UUID a, UUID b, Duration window) {
            this.windows.add(window);
            return !window.isZero();
        }
    }

    private static Setting<CombatSettings> settings(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/combat.yml", yaml);
        CombatSettings settings = CombatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return TestSettings.of(settings);
    }

    private static KillTracker tracker(Setting<CombatSettings> settings, FriendLookup friends) {
        return new KillTracker(settings, null, new RecentPairs(), null, null, TeamLookup.NONE, friends,
            new PlayerDirectory(null, new byte[32]), null, null, Logger.getLogger("kills-test"));
    }

    @Test
    void aFriendRemovedJustBeforeTheKillStillCountsAsAFriend() {
        JustUnfriended friends = new JustUnfriended();
        Setting<CombatSettings> settings = settings(Fakes.yaml("features/combat.yml"));
        AntiFarm.Facts facts = tracker(settings, friends).facts(KILLER, VICTIM);
        assertTrue(facts.friends(), "recently friends counts as friends");
        assertEquals(List.of(Duration.ofHours(24)), friends.windows, "the shipped window is 24h");
        AntiFarm.Decision decision = AntiFarm.decide(settings.get().antiFarm(), facts, NOW);
        assertFalse(decision.counted(), "no kill, no streak and no bounty claim");
        assertEquals(AntiFarm.Reason.FRIENDS, decision.reason());
    }

    @Test
    void theWindowComesFromTheConfig() {
        YamlConfiguration yaml = Fakes.yaml("features/combat.yml");
        yaml.set("anti-farm.friends-window", "0s");
        JustUnfriended friends = new JustUnfriended();
        AntiFarm.Facts facts = tracker(settings(yaml), friends).facts(KILLER, VICTIM);
        assertFalse(facts.friends(), "0s counts only current friends");
        assertEquals(List.of(Duration.ZERO), friends.windows);

        yaml.set("anti-farm.friends-window", null);
        assertEquals(Duration.ofHours(24), settings(yaml).get().antiFarm().friendsWindow(), "an older combat.yml keeps 24h");
    }

    @Test
    void theWindowIsBounded() {
        YamlConfiguration yaml = Fakes.yaml("features/combat.yml");
        yaml.set("anti-farm.friends-window", "90d");
        ConfigReader reader = new ConfigReader("features/combat.yml", yaml);
        CombatSettings settings = CombatSettings.parse(reader);
        assertEquals(1, reader.problems().size(), reader.problems().toString());
        assertEquals(Duration.ofHours(24), settings.antiFarm().friendsWindow(), "falls back to the default");
    }
}
