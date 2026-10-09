package net.siftvanilla.siftcore.feature.integrations;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Pure helpers of {@code /purchases}: shortened references, dates and pages. */
final class PurchaseText {

    /** References longer than this are shortened. */
    static final int SHORT_REF = 16;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private PurchaseText() {
    }

    /**
     * A reference short enough for a dialog line, keeping both ends (store references start with the store and end
     * with the package): {@code tebex-tbx-9f3a21c4d8-6512345} becomes {@code tebex-tb...6512345}.
     */
    static String shortRef(String ref) {
        if (ref == null) {
            return "";
        }
        if (ref.length() <= SHORT_REF) {
            return ref;
        }
        return ref.substring(0, 8) + "..." + ref.substring(ref.length() - 7);
    }

    /** The day of a purchase in UTC, like {@code 2026-10-09}. */
    static String date(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** How many pages {@code size} entries fill, at least one. */
    static int pages(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    /** The entries of a page (1-based, clamped to the pages there are). */
    static <T> List<T> page(List<T> entries, int page, int pageSize) {
        int current = Math.clamp(page, 1, pages(entries.size(), pageSize));
        int from = (current - 1) * pageSize;
        return entries.subList(Math.min(from, entries.size()), Math.min(from + pageSize, entries.size()));
    }
}
