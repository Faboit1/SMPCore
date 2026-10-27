package net.siftvanilla.siftcore.feature.staff;

import java.util.Locale;

/** A report is open until staff mark it handled or dismiss it. */
public enum ReportState {
    OPEN,
    HANDLED,
    DISMISSED;

    /** Parses a stored value; unknown values return null. */
    public static ReportState parse(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
