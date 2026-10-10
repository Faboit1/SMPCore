package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The anti-farm decision matrix: every rule, every combination, the order of reasons and the switches. */
class AntiFarmTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final AntiFarm.Rules ALL_ON = new AntiFarm.Rules(true, true, true, Duration.ofMinutes(10));

    private static OptionalLong ago(long minutes) {
        return OptionalLong.of(NOW - minutes * MINUTE);
    }

    private static AntiFarm.Facts facts(boolean sameTeam, boolean friends, boolean sameIp, OptionalLong last) {
        return new AntiFarm.Facts(sameTeam, friends, sameIp, last);
    }

    private static AntiFarm.Facts pair(OptionalLong last) {
        return facts(false, false, false, last);
    }

    /**
     * sameTeam, friends, sameIp, minutes since the pair's last counted kill (-1 = never), expected reason
     * ("counted" when the kill counts). Precedence: same team, then friends, then same IP, then the repeated pair.
     */
    @ParameterizedTest(name = "team={0} friends={1} ip={2} last={3}m -> {4}")
    @CsvSource({
        "false, false, false, -1, counted",
        "false, false, false, 11, counted",
        "false, false, false, 10, counted",
        "false, false, false, 9, repeated_pair",
        "false, false, false, 0, repeated_pair",
        "true, false, false, -1, same_team",
        "true, false, false, 5, same_team",
        "false, true, false, -1, friends",
        "false, true, false, 5, friends",
        "false, false, true, -1, same_ip",
        "false, false, true, 5, same_ip",
        "true, true, false, -1, same_team",
        "true, false, true, -1, same_team",
        "false, true, true, -1, friends",
        "true, true, true, 5, same_team",
        "false, true, true, 5, friends",
        "false, false, true, 11, same_ip",
    })
    void matrix(boolean sameTeam, boolean friends, boolean sameIp, long minutesAgo, String expected) {
        OptionalLong last = minutesAgo < 0 ? OptionalLong.empty() : ago(minutesAgo);
        AntiFarm.Decision decision = AntiFarm.decide(ALL_ON, facts(sameTeam, friends, sameIp, last), NOW);
        if (expected.equals("counted")) {
            assertTrue(decision.counted());
            assertNull(decision.reason());
        } else {
            assertFalse(decision.counted());
            assertEquals(expected, decision.reason().id());
        }
    }

    @Test
    void everyCombinationOfFactsUnderEveryRuleSwitch() {
        // Exhaustive: 2^3 rule switches x 2 cooldowns x 2^3 facts x 3 pair timings, against an independent oracle.
        OptionalLong[] timings = {OptionalLong.empty(), ago(3), ago(30)};
        for (int rules = 0; rules < 8; rules++) {
            for (Duration cooldown : new Duration[] {Duration.ZERO, Duration.ofMinutes(10)}) {
                AntiFarm.Rules r = new AntiFarm.Rules((rules & 1) != 0, (rules & 2) != 0, (rules & 4) != 0, cooldown);
                for (int known = 0; known < 8; known++) {
                    for (OptionalLong last : timings) {
                        AntiFarm.Facts f = facts((known & 1) != 0, (known & 2) != 0, (known & 4) != 0, last);
                        AntiFarm.Reason expected = null;
                        if (r.sameTeam() && f.sameTeam()) {
                            expected = AntiFarm.Reason.SAME_TEAM;
                        } else if (r.friends() && f.friends()) {
                            expected = AntiFarm.Reason.FRIENDS;
                        } else if (r.sameIp() && f.sameIp()) {
                            expected = AntiFarm.Reason.SAME_IP;
                        } else if (!cooldown.isZero() && last.isPresent() && NOW - last.getAsLong() < cooldown.toMillis()) {
                            expected = AntiFarm.Reason.REPEATED_PAIR;
                        }
                        AntiFarm.Decision decision = AntiFarm.decide(r, f, NOW);
                        assertEquals(expected == null, decision.counted(), r + " " + f);
                        assertEquals(expected, decision.reason(), r + " " + f);
                    }
                }
            }
        }
    }

    @Test
    void cooldownBoundaryIsExclusive() {
        long cooldown = Duration.ofMinutes(10).toMillis();
        assertFalse(AntiFarm.decide(ALL_ON, pair(OptionalLong.of(NOW - cooldown + 1)), NOW).counted(),
            "one millisecond before the cooldown ends");
        assertTrue(AntiFarm.decide(ALL_ON, pair(OptionalLong.of(NOW - cooldown)), NOW).counted(),
            "exactly when the cooldown ends");
    }

    @Test
    void aKillTimedAfterNowStillCountsAsRepeated() {
        // Clocks of different threads can disagree by a little; a "future" last kill must not open the gate.
        assertEquals(AntiFarm.Reason.REPEATED_PAIR, AntiFarm.decide(ALL_ON, pair(OptionalLong.of(NOW + 500)), NOW).reason());
    }

    @Test
    void switchedOffRulesNeverDeny() {
        AntiFarm.Facts everything = facts(true, true, true, ago(0));
        AntiFarm.Rules off = new AntiFarm.Rules(false, false, false, Duration.ZERO);
        assertTrue(AntiFarm.decide(off, everything, NOW).counted());
        AntiFarm.Rules teamOnly = new AntiFarm.Rules(true, false, false, Duration.ZERO);
        assertEquals(AntiFarm.Reason.SAME_TEAM, AntiFarm.decide(teamOnly, everything, NOW).reason());
        assertTrue(AntiFarm.decide(teamOnly, facts(false, true, true, ago(0)), NOW).counted(), "friends and IP are ignored when off");
        AntiFarm.Rules friendsOnly = new AntiFarm.Rules(false, true, false, Duration.ZERO);
        assertEquals(AntiFarm.Reason.FRIENDS, AntiFarm.decide(friendsOnly, everything, NOW).reason(),
            "same team is skipped when off, so the friends rule decides");
        AntiFarm.Rules ipOnly = new AntiFarm.Rules(false, false, true, Duration.ZERO);
        assertEquals(AntiFarm.Reason.SAME_IP, AntiFarm.decide(ipOnly, everything, NOW).reason());
        AntiFarm.Rules pairOnly = new AntiFarm.Rules(false, false, false, Duration.ofMinutes(10));
        assertEquals(AntiFarm.Reason.REPEATED_PAIR, AntiFarm.decide(pairOnly, facts(true, true, true, ago(1)), NOW).reason());
    }

    @Test
    void fairKillsCount() {
        assertTrue(AntiFarm.decide(ALL_ON, AntiFarm.Facts.FAIR, NOW).counted());
    }

    @Test
    void reasonIdsRoundTrip() {
        for (AntiFarm.Reason reason : AntiFarm.Reason.values()) {
            assertEquals(reason, AntiFarm.Reason.byId(reason.id()));
            assertTrue(reason.id().length() <= 32, "fits the kills.reason column");
        }
        assertNull(AntiFarm.Reason.byId("something_else"));
    }

    @Test
    void repeatedPairsFollowTheLastCountedKill() {
        RecentPairs pairs = new RecentPairs();
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        long t0 = NOW;
        assertTrue(AntiFarm.decide(ALL_ON, pair(pairs.last(killer, victim)), t0).counted(), "first kill counts");
        pairs.record(killer, victim, t0);
        assertFalse(AntiFarm.decide(ALL_ON, pair(pairs.last(killer, victim)), t0 + 4 * MINUTE).counted());
        // A kill that did not count does not move the window: 10 minutes after the counted one, it counts again.
        assertTrue(AntiFarm.decide(ALL_ON, pair(pairs.last(killer, victim)), t0 + 10 * MINUTE).counted());
        assertTrue(AntiFarm.decide(ALL_ON, pair(pairs.last(victim, killer)), t0 + MINUTE).counted(),
            "the other direction is a different pair");
    }

    @Test
    void recentPairsKeepTheNewestKillAndPrune() {
        RecentPairs pairs = new RecentPairs();
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        pairs.record(killer, victim, NOW);
        pairs.record(killer, victim, NOW - 5 * MINUTE);
        assertEquals(NOW, pairs.last(killer, victim).getAsLong(), "an older kill never replaces a newer one");
        pairs.record(victim, killer, NOW - 60 * MINUTE);
        pairs.prune(NOW - 30 * MINUTE);
        assertEquals(1, pairs.size());
        assertTrue(pairs.last(victim, killer).isEmpty(), "old pairs are forgotten");
    }
}
