package net.siftvanilla.siftcore.feature.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.siftvanilla.siftcore.core.config.ConfigProblem;

/**
 * The config problems the last startup or {@code /sift reload} found, for the join alert of admins
 * ("Config problem alerts", {@link AdminFeature#CONFIG_ALERTS}).
 * <p>
 * A reload hands its problems over directly ({@link #reloaded}), and so does the startup when the plugin keeps its
 * list ({@link #startup}, from {@code CoreControl#startupProblems}, which SiftCore keeps). This also listens to
 * SiftCore's own logger: every {@code Config problem: ...} line the startup writes ({@link #STARTUP_PREFIX}), and the {@code features/settings.yml: ...} warnings the settings feature writes for bad
 * server overrides after startup and on each reload ({@link #SETTINGS_PREFIX}). Both log formats are pinned by a test
 * against their sources. The same problem from two sources counts once. Thread-safe: records arrive from any thread.
 */
final class ConfigProblemLog extends Handler {

    /** How the startup logs each problem ({@code SiftCore#enable}: {@code "Config problem: " + problem}). */
    static final String STARTUP_PREFIX = "Config problem: ";
    /** How the settings feature logs a bad entry of features/settings.yml. */
    static final String SETTINGS_PREFIX = "features/settings.yml: ";
    /** More than this many problems are counted but not kept for the hover (the console has them all). */
    static final int KEEP = 50;

    private final Object lock = new Object();
    /** Every problem found since the last reload began (or startup), to count each once. */
    private final Set<String> seen = new HashSet<>();
    /** The first {@link #KEEP} of them, in the order found. */
    private final List<String> kept = new ArrayList<>();
    /** The startup's own list was taken over (once). */
    private boolean seeded;
    /** A reload replaced what the startup found. */
    private boolean replaced;

    @Override
    public void publish(LogRecord record) {
        String problem = problem(record == null ? null : record.getMessage());
        if (problem != null) {
            add(problem);
        }
    }

    /** The problem a log line reports, or null when it reports none. Pure. */
    static String problem(String message) {
        if (message == null) {
            return null;
        }
        int startup = message.indexOf(STARTUP_PREFIX);
        if (startup >= 0) {
            String problem = message.substring(startup + STARTUP_PREFIX.length()).strip();
            return problem.isEmpty() ? null : problem;
        }
        int settings = message.indexOf(SETTINGS_PREFIX);
        if (settings >= 0 && message.length() > settings + SETTINGS_PREFIX.length()) {
            return message.substring(settings).strip();
        }
        return null;
    }

    private void add(String problem) {
        synchronized (this.lock) {
            if (!this.seen.add(problem)) {
                return;
            }
            if (this.kept.size() < KEEP) {
                this.kept.add(problem);
            }
        }
    }

    /**
     * The problems the startup found, from the plugin's own list. Taken once, and ignored after a reload (which
     * replaced them); lines already read from the log count once.
     */
    void startup(List<ConfigProblem> found) {
        synchronized (this.lock) {
            if (this.seeded || this.replaced) {
                return;
            }
            this.seeded = true;
            for (ConfigProblem problem : found) {
                add(problem.toString());
            }
        }
    }

    /** A reload starts: what it finds replaces what was found before (settings warnings it causes are added). */
    void reloading() {
        synchronized (this.lock) {
            this.replaced = true;
            this.seen.clear();
            this.kept.clear();
        }
    }

    /** The problems a reload returned (empty when it was applied cleanly). */
    void reloaded(List<ConfigProblem> found) {
        for (ConfigProblem problem : found) {
            add(problem.toString());
        }
    }

    /** How many problems the last startup or reload found. */
    int count() {
        synchronized (this.lock) {
            return this.seen.size();
        }
    }

    /** The first {@code limit} of them. */
    List<String> first(int limit) {
        synchronized (this.lock) {
            return List.copyOf(this.kept.subList(0, Math.min(Math.max(0, limit), this.kept.size())));
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
