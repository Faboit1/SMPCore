package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** How a player's rank, label and tab order are worked out from the rank integration and their groups. */
class RankOrderTest {

    private final RankOrder order = RankOrder.defaults();

    private static Predicate<String> groups(String... names) {
        return Set.of(names)::contains;
    }

    @Test
    void thePrimaryGroupFromTheIntegrationWins() {
        RankOrder.PlayerRank rank = this.order.resolve("Baron", "baron", groups("tycoon"));
        assertEquals("baron", rank.group());
        assertEquals("Baron", rank.label());
        assertEquals(1, rank.order());
    }

    @Test
    void withoutTheIntegrationTheHighestGroupThePlayerIsInCounts() {
        // Ranks inherit the one below, so a tycoon is in every group; the highest listed one is the rank.
        RankOrder.PlayerRank rank = this.order.resolve("", "default", groups("default", "prospector", "baron", "tycoon"));
        assertEquals("tycoon", rank.group());
        assertEquals("Tycoon", rank.label(), "the listed label is used when the integration gives none");
        assertEquals(0, rank.order());
    }

    @Test
    void theIntegrationsLabelBeatsTheListedOne() {
        RankOrder.PlayerRank rank = this.order.resolve("  Prospector+ ", "prospector", groups());
        assertEquals("Prospector+", rank.label());
        assertEquals("prospector", rank.group());
    }

    @Test
    void defaultPlayersHaveNoLabelButSortAboveUnknowns() {
        RankOrder.PlayerRank member = this.order.resolve("", "default", groups("default"));
        assertEquals("default", member.group());
        assertEquals("", member.label());
        assertEquals(3, member.order());
        RankOrder.PlayerRank stranger = this.order.resolve("", "default", groups());
        assertEquals("default", stranger.group(), "the integration's default group still places the player");
        RankOrder.PlayerRank nobody = this.order.resolve("", "", groups());
        assertEquals(RankOrder.PlayerRank.NONE, nobody);
        assertNull(nobody.group());
    }

    @Test
    void anUnlistedGroupWithALabelComesAfterTheListedRanks() {
        RankOrder.PlayerRank rank = this.order.resolve("Builder", "builder", groups());
        assertNull(rank.group());
        assertEquals("Builder", rank.label());
        assertEquals(this.order.ranks().size(), rank.order());
        assertEquals(0, this.order.listOrder(rank));
    }

    @Test
    void higherRanksGetAHigherTabListOrder() {
        int previous = Integer.MAX_VALUE;
        for (RankOrder.Rank listed : this.order.ranks()) {
            int listOrder = this.order.listOrder(this.order.resolve("", listed.group(), groups()));
            assertTrue(listOrder < previous && listOrder > 0, listed.group() + " -> " + listOrder);
            previous = listOrder;
        }
        assertEquals(0, this.order.listOrder(RankOrder.PlayerRank.NONE));
    }

    @Test
    void groupNamesAreMatchedCaseInsensitively() {
        assertEquals("baron", this.order.resolve("", "Baron", groups()).group());
        assertEquals("group.baron", RankOrder.groupPermission("baron"));
    }
}
