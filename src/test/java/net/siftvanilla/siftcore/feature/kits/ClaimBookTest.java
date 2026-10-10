package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimBookTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void setReturnsWhatItReplacedSoTheUndoRestoresIt() {
        ClaimBook book = new ClaimBook();
        assertNull(book.set(ALEX, "daily", 100L));
        assertEquals(100L, book.set(ALEX, "daily", 200L));
        assertEquals(200L, book.last(ALEX, "daily"));
        book.restore(ALEX, "daily", 100L);
        assertEquals(100L, book.last(ALEX, "daily"));
        book.restore(ALEX, "daily", null);
        assertNull(book.last(ALEX, "daily"));
        assertTrue(book.snapshot().isEmpty());
    }

    @Test
    void removeAndRemoveAllCanBeUndone() {
        ClaimBook book = new ClaimBook();
        book.set(ALEX, "daily", 1L);
        book.set(ALEX, "starter", 2L);
        book.set(SAM, "daily", 3L);
        assertEquals(1L, book.remove(ALEX, "daily"));
        assertNull(book.remove(ALEX, "daily"));
        assertEquals(Map.of("starter", 2L), book.of(ALEX));
        Map<String, Long> removed = book.removeAll(ALEX);
        assertEquals(Map.of("starter", 2L), removed);
        assertEquals(Map.of(), book.of(ALEX));
        book.restoreAll(ALEX, removed);
        assertEquals(Map.of("starter", 2L), book.of(ALEX));
        assertEquals(2, book.size());
    }

    @Test
    void loadReplacesEverythingAndSnapshotsAreCopies() {
        ClaimBook book = new ClaimBook();
        book.set(SAM, "legend", 9L);
        book.load(Map.of(ALEX, Map.of("daily", 5L, "starter", 6L), SAM, Map.of()));
        assertNull(book.last(SAM, "legend"));
        Map<UUID, Map<String, Long>> snapshot = book.snapshot();
        assertEquals(Map.of(ALEX, Map.of("daily", 5L, "starter", 6L)), snapshot);
        book.set(ALEX, "daily", 50L);
        assertEquals(5L, snapshot.get(ALEX).get("daily"));
        assertEquals(Map.of(), book.removeAll(SAM));
    }
}
