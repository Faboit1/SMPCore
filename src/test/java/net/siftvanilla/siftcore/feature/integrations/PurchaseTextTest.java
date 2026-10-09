package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** /purchases: shortened references, UTC dates and pages. */
class PurchaseTextTest {

    @Test
    void longReferencesKeepBothEnds() {
        assertEquals("tbx-1", PurchaseText.shortRef("tbx-1"));
        assertEquals("exactly16chars!!".length(), PurchaseText.SHORT_REF);
        assertEquals("tebex-9f...6512345", PurchaseText.shortRef("tebex-9f3a21c4d8-6512345"));
        assertEquals("", PurchaseText.shortRef(null));
    }

    @Test
    void datesAreUtcDays() {
        assertEquals("2026-10-09", PurchaseText.date(1_791_504_000_000L + 3_600_000L));
        assertEquals("1970-01-01", PurchaseText.date(0));
    }

    @Test
    void pagesHoldAFixedNumberAndClamp() {
        List<Integer> entries = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        assertEquals(3, PurchaseText.pages(entries.size(), 6));
        assertEquals(1, PurchaseText.pages(0, 6));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), PurchaseText.page(entries, 1, 6));
        assertEquals(List.of(13), PurchaseText.page(entries, 3, 6));
        assertEquals(List.of(13), PurchaseText.page(entries, 9, 6), "past the end shows the last page");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), PurchaseText.page(entries, 0, 6));
        assertEquals(List.of(), PurchaseText.page(List.of(), 1, 6));
    }
}
