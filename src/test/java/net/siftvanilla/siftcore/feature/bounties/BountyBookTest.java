package net.siftvanilla.siftcore.feature.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stacking, sponsors, what a killer may claim, the ranking and removal. */
class BountyBookTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static BountyBook.Contribution c(long id, UUID target, UUID sponsor, long amount, long created) {
        return new BountyBook.Contribution(id, target, sponsor, amount, created);
    }

    @Test
    void contributionsStack() {
        BountyBook book = new BountyBook();
        book.add(c(1, TARGET, ALICE, 1_000, 100));
        book.add(c(2, TARGET, BOB, 2_500, 200));
        book.add(c(3, TARGET, ALICE, 500, 300));
        BountyBook.Bounty bounty = book.get(TARGET);
        assertEquals(4_000, bounty.total());
        assertEquals(2, bounty.sponsors(), "Alice counts once");
        assertEquals(1_500, bounty.from(ALICE));
        assertEquals(2_500, bounty.from(BOB));
        assertEquals(0, bounty.from(TARGET));
        assertEquals(100, bounty.oldest());
        assertEquals(4_000, book.totalActive());
        assertEquals(4_000, book.total(TARGET));
        assertEquals(0, book.total(ALICE));
    }

    @Test
    void addingTheSameContributionTwiceChangesNothing() {
        BountyBook book = new BountyBook();
        BountyBook.Contribution one = c(1, TARGET, ALICE, 1_000, 100);
        book.add(one);
        book.add(one);
        assertEquals(1_000, book.totalActive());
        assertEquals(1, book.get(TARGET).contributions().size());
    }

    @Test
    void sponsorsCannotClaimTheirOwnPart() {
        BountyBook book = new BountyBook();
        book.add(c(1, TARGET, ALICE, 1_000, 100));
        book.add(c(2, TARGET, BOB, 2_000, 200));
        List<BountyBook.Contribution> forAlice = book.get(TARGET).claimableBy(ALICE);
        assertEquals(1, forAlice.size());
        assertEquals(BOB, forAlice.getFirst().sponsor());
        assertEquals(2, book.get(TARGET).claimableBy(UUID.randomUUID()).size(), "a stranger may claim everything");
    }

    @Test
    void removalUpdatesTotalsAndDropsEmptyBounties() {
        BountyBook book = new BountyBook();
        BountyBook.Contribution one = c(1, TARGET, ALICE, 1_000, 100);
        BountyBook.Contribution two = c(2, TARGET, BOB, 2_000, 200);
        book.add(one);
        book.add(two);
        assertTrue(book.remove(one));
        assertFalse(book.remove(one), "already gone");
        assertEquals(2_000, book.totalActive());
        assertFalse(book.allActive(List.of(one, two)));
        assertTrue(book.allActive(List.of(two)));
        assertTrue(book.remove(two));
        assertNull(book.get(TARGET));
        assertEquals(0, book.targets());
        assertEquals(0, book.totalActive());
        assertFalse(book.remove(c(9, UUID.randomUUID(), ALICE, 5, 1)), "unknown target");
    }

    @Test
    void rankingIsBiggestFirstThenOldest() {
        BountyBook book = new BountyBook();
        UUID small = UUID.randomUUID();
        UUID big = UUID.randomUUID();
        UUID tieOld = UUID.randomUUID();
        UUID tieNew = UUID.randomUUID();
        book.add(c(1, small, ALICE, 1_000, 10));
        book.add(c(2, big, ALICE, 90_000, 20));
        book.add(c(3, tieNew, ALICE, 5_000, 40));
        book.add(c(4, tieOld, BOB, 5_000, 30));
        List<UUID> order = book.top(10).stream().map(BountyBook.Bounty::target).toList();
        assertEquals(List.of(big, tieOld, tieNew, small), order);
        assertEquals(2, book.top(2).size());
        assertEquals(0, book.top(0).size());
        assertEquals(1, book.rankOf(big));
        assertEquals(4, book.rankOf(small));
        assertEquals(0, book.rankOf(UUID.randomUUID()));
        book.add(c(5, small, BOB, 100_000, 50));
        assertEquals(small, book.top(1).getFirst().target(), "the ranking follows changes");
        assertEquals(1, book.rankOf(small));
    }

    @Test
    void everythingIsListedForExpiry() {
        BountyBook book = new BountyBook();
        book.add(c(1, TARGET, ALICE, 1_000, 100));
        book.add(c(2, UUID.randomUUID(), BOB, 2_000, 200));
        assertEquals(2, book.all().size());
        book.clear();
        assertTrue(book.all().isEmpty());
        assertEquals(0, book.totalActive());
    }
}
