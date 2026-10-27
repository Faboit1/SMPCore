package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The scoreboard's own placeholders come from the connected services; everything else from the registry. */
class ValuesTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private record Subject(UUID id, String name, String rankLabel) implements Values.Subject {
        @Override
        public Player player() {
            return null;
        }
    }

    /** Stats in memory, as the stats feature keeps them. */
    private static final class FakeStats implements StatsRecorder {
        final Map<Stat, Long> values = new EnumMap<>(Stat.class);

        @Override
        public void add(UUID player, Stat stat, long amount) {
            this.values.merge(stat, amount, Long::sum);
        }

        @Override
        public void kill(UUID killer, UUID victim) {
        }

        @Override
        public void death(UUID victim) {
        }

        @Override
        public long get(UUID player, Stat stat) {
            return this.values.getOrDefault(stat, 0L);
        }

        @Override
        public int streak(UUID player) {
            return 4;
        }

        @Override
        public int bestStreak(UUID player) {
            return 9;
        }
    }

    private static TeamLookup teamNamed(String name) {
        return new TeamLookup() {
            @Override
            public Optional<Long> team(UUID player) {
                return name == null ? Optional.empty() : Optional.of(1L);
            }

            @Override
            public Optional<String> teamName(UUID player) {
                return Optional.ofNullable(name);
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
    }

    @Test
    void localValuesComeFromTheConnectedServices() {
        FakeStats stats = new FakeStats();
        stats.add(ALEX, StatsRecorder.Stat.KILLS, 1234);
        stats.add(ALEX, StatsRecorder.Stat.DEATHS, 5);
        stats.add(ALEX, StatsRecorder.Stat.PLAYTIME_SECONDS, 3 * 3600 + 25 * 60);
        CombatTags combat = new CombatTags();
        combat.tag(ALEX, UUID.randomUUID(), Duration.ofMillis(11_200));
        Values values = new Values(new Placeholders(), stats, teamNamed("Alpha"), combat);
        Subject alex = new Subject(ALEX, "Alex", "Elite");
        List<String> resolved = values.resolve(alex,
            List.of("player", "online", "rank", "team", "kills", "deaths", "streak", "best_streak", "playtime", "combat"), 42);
        assertEquals(List.of("Alex", "42", "Elite", "Alpha", "1,234", "5", "4", "9", "3h 25m", "12s"), resolved);
    }

    @Test
    void emptyValuesHideTheirLines() {
        Values values = new Values(new Placeholders(), StatsRecorder.NONE, teamNamed(null), new CombatTags());
        Subject alex = new Subject(ALEX, "Alex", "");
        assertEquals(List.of("", "", ""), values.resolve(alex, List.of("team", "combat", "rank"), 1));
        assertTrue(LineTemplate.hidden(values.resolve(alex, List.of("team"), 1)));
    }

    @Test
    void registryPlaceholdersAreUsedAndFailuresCountAsMissing() {
        Placeholders registry = new Placeholders();
        registry.register("balance", "test", player -> "$1,500");
        registry.register("broken", "test", player -> {
            throw new IllegalStateException("boom");
        });
        Values values = new Values(registry, StatsRecorder.NONE, TeamLookup.NONE, new CombatTags());
        Subject alex = new Subject(ALEX, "Alex", "");
        assertEquals("$1,500", values.value(alex, "balance", 1));
        assertNull(values.value(alex, "broken", 1));
        assertNull(values.value(alex, "nothing_provides_this", 1));
        assertTrue(values.known("balance"));
        assertTrue(values.known("broken"), "a placeholder that needs a player is still provided");
        assertTrue(values.known("kills"));
        assertFalse(values.known("nothing_provides_this"));
    }

    @Test
    void combatTimeRoundsUp() {
        assertEquals("1s", Values.combatLeft(Duration.ofMillis(1)));
        assertEquals("15s", Values.combatLeft(Duration.ofSeconds(15)));
        assertEquals("", Values.combatLeft(Duration.ofSeconds(-1)));
        assertEquals("", Values.combatLeft(null));
    }
}
