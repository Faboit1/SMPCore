package net.siftvanilla.siftcore;

import java.util.List;
import java.util.Optional;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.storage.Database;

/** What the admin feature may do with the plugin itself (reload, self-test, metrics, debug). */
public interface CoreControl {

    List<ConfigProblem> reload();

    /**
     * Whether the last {@link #reload()} applied the config files. A reload whose config files are broken applies
     * nothing; one whose config files are fine applies them even when icons or lang entries have problems (those
     * entries keep their shipped values).
     */
    default boolean lastReloadApplied() {
        return false;
    }

    SelfTest selfTest();

    boolean debug();

    void debug(boolean on);

    Metrics metrics();

    Database database();

    /**
     * The config problems the startup found (every one it logged as {@code Config problem: ...}). Empty until the
     * startup is done, and when the plugin does not keep them: the admin feature then counts them from the log. For
     * the "Config problem alerts" setting ({@code admin-config-alerts}).
     */
    default Optional<List<ConfigProblem>> startupProblems() {
        return Optional.empty();
    }

    /** A snapshot of internal counters for /sift metrics. */
    record Metrics(long uptimeMillis, int pendingWrites, long committedWrites, long failedWrites, long avgGroupMicros,
                   long transactions, long storeFailures, int accounts, int dialogSessions, long dialogClicks,
                   long dialogRejected, int features, int commandLabels) {
    }
}
