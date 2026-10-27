package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The top sellers list: places, ranks and players who hide from leaderboards. */
class TopSellersTest {

    private static final UUID STAFF = new UUID(0, 1);
    private static final UUID ALICE = new UUID(0, 2);
    private static final UUID BOB = new UUID(0, 3);
    private static final UUID CAROL = new UUID(0, 4);

    private static TopSellers.Snapshot build(Predicate<UUID> hidden) {
        return TopSellers.build(List.of(STAFF, ALICE, BOB, CAROL), List.of(9_000L, 500L, 300L, 300L), hidden,
            uuid -> uuid.equals(CAROL) ? null : "p" + uuid.getLeastSignificantBits(), 42);
    }

    @Test
    void everyoneHasAPlaceWhenNobodyHides() {
        TopSellers.Snapshot snapshot = build(HiddenSellers.NONE);
        assertEquals(List.of(STAFF, ALICE, BOB, CAROL), snapshot.top().stream().map(TopSellers.Entry::uuid).toList());
        assertEquals("p1", snapshot.place(1).orElseThrow().name());
        assertEquals(CAROL.toString().substring(0, 8), snapshot.place(4).orElseThrow().name(), "unknown names show the uuid start");
        assertEquals(1, snapshot.rankOf(9_000));
        assertEquals(3, snapshot.rankOf(300), "ties share the better place");
        assertEquals(42, snapshot.at());
    }

    @Test
    void hiddenPlayersTakeNoPlaceAndNoRank() {
        TopSellers.Snapshot snapshot = build(STAFF::equals);
        assertEquals(List.of(ALICE, BOB, CAROL), snapshot.top().stream().map(TopSellers.Entry::uuid).toList(),
            "the hidden top seller is left out of the list");
        assertEquals(ALICE, snapshot.place(1).orElseThrow().uuid(), "sell_top_name_1 is the best seller who does not hide");
        assertFalse(snapshot.place(4).isPresent(), "the places below move up instead of leaving a gap");
        assertArrayEquals(new long[] {500, 300, 300}, snapshot.sorted());
        assertEquals(1, snapshot.rankOf(500), "a hidden total above does not push anyone down");
        assertEquals(1, snapshot.rankOf(9_000));
        assertEquals(9_000L, snapshot.byUuid().get(STAFF), "a hidden player still knows what they sold");
    }

    @Test
    void onlyTenPlacesAreNamed() {
        List<UUID> uuids = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            uuids.add(new UUID(1, i));
            totals.add(1_000L - i);
        }
        TopSellers.Snapshot snapshot = TopSellers.build(uuids, totals, uuid -> uuid.getLeastSignificantBits() < 3,
            uuid -> "x", 0);
        assertEquals(TopSellers.PLACES, snapshot.top().size());
        assertEquals(new UUID(1, 3), snapshot.top().getFirst().uuid(), "the three hidden best sellers are skipped");
        assertEquals(12, snapshot.sorted().length);
    }

    @Test
    void whoIsHidden() {
        Predicate<UUID> hidden = HiddenSellers.decide(Map.of(ALICE, true, BOB, false), Map.of(BOB, true, CAROL, false), false, false);
        assertTrue(hidden.test(ALICE), "an offline player's stored choice");
        assertTrue(hidden.test(BOB), "an online player's loaded value wins over the stored row");
        assertFalse(hidden.test(CAROL));
        assertFalse(hidden.test(STAFF), "never chose: the default");

        Predicate<UUID> everyone = HiddenSellers.decide(Map.of(ALICE, false), Map.of(), true, false);
        assertTrue(everyone.test(STAFF), "the server's default hides everyone who never chose");
        assertFalse(everyone.test(ALICE));

        Predicate<UUID> locked = HiddenSellers.decide(Map.of(ALICE, true), Map.of(BOB, true), false, true);
        assertFalse(locked.test(ALICE), "a locked or hidden setting ignores stored choices");
        assertTrue(locked.test(BOB), "online players count with their effective value");
    }
}
