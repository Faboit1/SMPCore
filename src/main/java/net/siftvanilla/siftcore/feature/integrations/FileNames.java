package net.siftvanilla.siftcore.feature.integrations;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Names of backup and export files, and which old backups to delete. */
final class FileNames {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final Pattern BACKUP = Pattern.compile("siftcore-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-\\d+)?\\.db");

    private FileNames() {
    }

    /** {@code 2026-10-08_14-30-05} (UTC), sortable as text. */
    static String stamp(Instant time) {
        return STAMP.format(time);
    }

    /**
     * A backup file name that is not taken yet: {@code siftcore-<stamp>.db}, or with {@code -2}, {@code -3} ...
     * when two backups start in the same second.
     */
    static String backup(Instant time, Set<String> taken) {
        String base = "siftcore-" + stamp(time);
        String name = base + ".db";
        for (int i = 2; taken.contains(name); i++) {
            name = base + "-" + i + ".db";
        }
        return name;
    }

    static boolean isBackup(String name) {
        return BACKUP.matcher(name).matches();
    }

    /** {@code <kind>-<stamp>.csv}. */
    static String export(String kind, Instant time) {
        return kind + "-" + stamp(time) + ".csv";
    }

    /** The backups to delete so only the newest {@code keep} remain (none when keep is 0, which keeps all). */
    static List<String> prune(List<String> names, int keep) {
        if (keep <= 0) {
            return List.of();
        }
        List<String> backups = new ArrayList<>();
        for (String name : names) {
            if (isBackup(name)) {
                backups.add(name);
            }
        }
        backups.sort(Comparator.comparing(FileNames::sortKey).reversed());
        return backups.size() <= keep ? List.of() : List.copyOf(backups.subList(keep, backups.size()));
    }

    /** A file size for people: {@code 812 B}, {@code 14.2 KB}, {@code 3.1 MB}, {@code 1.2 GB}. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** Orders same-second backups by their counter ({@code -10} after {@code -9}). */
    private static String sortKey(String name) {
        String stem = name.substring("siftcore-".length(), name.length() - ".db".length());
        int dash = stem.indexOf('-', "yyyy-MM-dd_HH-mm-ss".length());
        if (dash < 0) {
            return stem + "-0000";
        }
        String counter = stem.substring(dash + 1);
        return stem.substring(0, dash) + "-" + "0".repeat(Math.max(0, 4 - counter.length())) + counter;
    }
}
