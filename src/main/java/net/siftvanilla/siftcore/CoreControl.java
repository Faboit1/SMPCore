package net.siftvanilla.siftcore;

import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.storage.Database;

/** What the admin feature may do with the plugin itself (reload, self-test, metrics, debug). */
public interface CoreControl {

    List<ConfigProblem> reload();

    SelfTest selfTest();

    boolean debug();

    void debug(boolean on);

    Metrics metrics();

    Database database();

    /** A snapshot of internal counters for /sift metrics. */
    record Metrics(long uptimeMillis, int pendingWrites, long committedWrites, long failedWrites, long avgGroupMicros,
                   long transactions, long storeFailures, int accounts, int dialogSessions, long dialogClicks,
                   long dialogRejected, int features, int commandLabels) {
    }
}
