package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import org.junit.jupiter.api.Test;

class ListingBookTest {

    private static final UUID SELLER = new UUID(1, 1);
    private static final UUID BUYER = new UUID(2, 2);
    private static final UUID OTHER = new UUID(3, 3);

    private static Listing<String> listing(long id, UUID seller, long price, long created, long expires) {
        return new Listing<>(id, seller, "item" + id, "minecraft:stone", "stone", ItemCategory.BLOCKS, 1, price, created, expires);
    }

    @Test
    void openListingIsActiveAndCounted() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), true);
        book.open(listing(2, SELLER, 100, 0, 1000), true);
        book.open(listing(3, OTHER, 100, 0, 1000), true);
        assertEquals(3, book.size());
        assertEquals(2, book.count(SELLER));
        assertEquals(1, book.count(OTHER));
        assertEquals(0, book.count(BUYER));
        assertEquals(2, book.sellers());
        assertEquals(2, book.of(SELLER).size());
    }

    @Test
    void openingTwiceIsRejected() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), true);
        assertThrows(IllegalStateException.class, () -> book.open(listing(1, SELLER, 100, 0, 1000), true));
    }

    @Test
    void closesExactlyOnce() {
        ListingBook<String> book = new ListingBook<>();
        Listing<String> listing = listing(1, SELLER, 100, 0, 1000);
        book.open(listing, true);
        assertSame(listing, book.close(1));
        assertNull(book.close(1));
        assertEquals(Refusal.GONE, book.check(1, new ListingBook.Sale(BUYER, 100, 10)));
        assertEquals(Refusal.GONE, book.check(1, new ListingBook.Cancellation(SELLER)));
        assertEquals(Refusal.GONE, book.check(1, new ListingBook.Expiry(5000)));
        assertEquals(0, book.count(SELLER));
        assertEquals(0, book.sellers());
    }

    @Test
    void reopenRestoresAClosedListing() {
        ListingBook<String> book = new ListingBook<>();
        Listing<String> listing = listing(1, SELLER, 100, 0, 1000);
        book.open(listing, true);
        book.close(1);
        book.reopen(listing);
        assertSame(listing, book.get(1));
        assertTrue(book.saved(1));
        assertEquals(1, book.count(SELLER));
        book.reopen(listing);
        assertEquals(1, book.count(SELLER), "reopening twice must not count twice");
    }

    @Test
    void unsavedListingsHoldASlotButCannotBeClosed() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), false);
        assertEquals(1, book.count(SELLER));
        assertEquals(1, book.unsavedCount());
        assertFalse(book.saved(1));
        assertEquals(Refusal.PENDING, book.check(1, new ListingBook.Sale(BUYER, 100, 10)));
        assertEquals(Refusal.PENDING, book.check(1, new ListingBook.Cancellation(SELLER)));
        assertEquals(Refusal.PENDING, book.check(1, new ListingBook.Expiry(5000)));
        assertTrue(book.available(10).isEmpty(), "buyers never see unsaved listings");
        assertTrue(book.due(5000).isEmpty(), "unsaved listings are never expired");
        book.markSaved(1);
        assertTrue(book.saved(1));
        assertNull(book.check(1, new ListingBook.Sale(BUYER, 100, 10)));
        assertEquals(1, book.available(10).size());
    }

    @Test
    void discardUndoesAnOpen() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), false);
        book.discard(1);
        assertEquals(0, book.size());
        assertEquals(0, book.count(SELLER));
        assertEquals(0, book.unsavedCount());
    }

    @Test
    void saleRules() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), true);
        assertEquals(Refusal.OWN_LISTING, book.check(1, new ListingBook.Sale(SELLER, 100, 10)));
        assertEquals(Refusal.PRICE_CHANGED, book.check(1, new ListingBook.Sale(BUYER, 99, 10)));
        assertEquals(Refusal.EXPIRED, book.check(1, new ListingBook.Sale(BUYER, 100, 1000)));
        assertNull(book.check(1, new ListingBook.Sale(BUYER, 100, 999)));
    }

    @Test
    void cancellationRules() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), true);
        assertEquals(Refusal.NOT_OWNER, book.check(1, new ListingBook.Cancellation(OTHER)));
        assertNull(book.check(1, new ListingBook.Cancellation(SELLER)));
        assertNull(book.check(1, new ListingBook.Cancellation(null)), "staff may cancel any listing");
        assertNull(book.check(1, new ListingBook.Cancellation(SELLER)), "an expired listing can still be taken down");
    }

    @Test
    void expiryRules() {
        ListingBook<String> book = new ListingBook<>();
        book.open(listing(1, SELLER, 100, 0, 1000), true);
        book.open(listing(2, SELLER, 100, 0, 2000), true);
        assertEquals(Refusal.NOT_EXPIRED, book.check(1, new ListingBook.Expiry(999)));
        assertNull(book.check(1, new ListingBook.Expiry(1000)));
        assertEquals(List.of(1L), book.due(1500).stream().map(Listing::id).toList());
        assertEquals(1, book.available(1500).size());
        assertEquals(1000, book.nextExpiry());
    }

    @Test
    void concurrentClosingAttemptsHaveExactlyOneWinner() throws Exception {
        ListingBook<String> book = new ListingBook<>();
        ReentrantLock lock = new ReentrantLock();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 1; round <= 500; round++) {
                long expires = 1000;
                book.open(listing(round, SELLER, 100, 0, expires), true);
                long now = round % 2 == 0 ? expires - 1 : expires;
                List<ListingBook.Closing> attempts = List.of(new ListingBook.Sale(BUYER, 100, now),
                    new ListingBook.Sale(OTHER, 100, now), new ListingBook.Cancellation(SELLER),
                    new ListingBook.Cancellation(null), new ListingBook.Expiry(now), new ListingBook.Sale(BUYER, 100, now));
                CyclicBarrier barrier = new CyclicBarrier(attempts.size());
                List<Future<Boolean>> results = new ArrayList<>();
                long id = round;
                for (ListingBook.Closing attempt : attempts) {
                    results.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        lock.lock();
                        try {
                            return book.check(id, attempt) == null && book.close(id) != null;
                        } finally {
                            lock.unlock();
                        }
                    }));
                }
                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertEquals(1, winners, "round " + round);
                assertNull(book.get(id));
            }
            assertEquals(0, book.size());
            assertEquals(0, book.count(SELLER));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void selfTestRaceCheckPasses() {
        assertNull(AuctionFeature.raceCheck());
    }

    @Test
    void listingValidatesItsFields() {
        assertThrows(IllegalArgumentException.class, () -> listing(0, SELLER, 100, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> listing(1, SELLER, 0, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> listing(1, SELLER, 100, 1000, 1000));
        assertThrows(IllegalArgumentException.class,
            () -> new Listing<>(1, SELLER, "x", "minecraft:stone", "", ItemCategory.BLOCKS, 0, 1, 0, 10));
        Listing<String> listing = listing(7, SELLER, 100, 0, 1000);
        assertEquals("listing:7", listing.ref());
        assertEquals(400, listing.millisLeft(600));
        assertEquals(0, listing.millisLeft(5000));
        assertTrue(listing.expired(1000));
        assertFalse(listing.expired(999));
    }

    @Test
    void everyClosingEndsInItsOwnFinalState() {
        assertEquals(ListingState.SOLD, new ListingBook.Sale(BUYER, 100, 10).result());
        assertEquals(ListingState.CANCELLED, new ListingBook.Cancellation(SELLER).result());
        assertEquals(ListingState.CANCELLED, new ListingBook.Cancellation(null).result());
        assertEquals(ListingState.EXPIRED, new ListingBook.Expiry(10).result());
        for (ListingBook.Closing closing : List.of(new ListingBook.Sale(BUYER, 1, 1), new ListingBook.Cancellation(SELLER),
            new ListingBook.Expiry(1))) {
            assertTrue(closing.result().closed(), closing + " leads to a final state");
        }
    }

    @Test
    void statesAndRefusalsRoundTrip() {
        for (ListingState state : ListingState.values()) {
            assertEquals(state, ListingState.parse(state.name().toLowerCase()));
        }
        assertFalse(ListingState.ACTIVE.closed());
        assertTrue(ListingState.SOLD.closed());
        assertThrows(IllegalArgumentException.class, () -> ListingState.parse("gone"));
        for (Refusal refusal : Refusal.values()) {
            assertEquals(refusal, Refusal.from(refusal.id()));
        }
        assertNull(Refusal.from("overflow"));
        for (ItemCategory category : ItemCategory.values()) {
            assertEquals(category, ItemCategory.byId(category.id()));
        }
        assertNull(ItemCategory.byId("weapons"));
    }
}
