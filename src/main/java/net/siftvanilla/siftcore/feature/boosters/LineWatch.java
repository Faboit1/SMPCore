package net.siftvanilla.siftcore.feature.boosters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What changed in the line of boosters between two looks: a booster that stopped running, one that started and one
 * that joined the line. The announcer looks once a second and tells everyone.
 * <p>
 * A look while the line is not {@link BoosterService#settled() settled} (a change waits for its database commit and
 * could still be taken back) reports nothing and remembers nothing, so the next settled look reports the change if it
 * lasted, and never mentions it if it was taken back. Pure logic; one thread (the global thread) at a time.
 */
final class LineWatch {

    /** One thing that changed. */
    sealed interface Change permits Ended, Started, Queued {
    }

    /** The booster with this id was running and no longer is (it ran out, was stopped or taken back). */
    record Ended(long id) implements Change {
    }

    /** This booster started running. */
    record Started(Booster booster) implements Change {
    }

    /** This booster joined the line at {@code position} (1 is next). */
    record Queued(Booster booster, int position) implements Change {
    }

    private long lastActive;
    private Set<Long> known = Set.of();

    /** Takes this line as already reported (the line as loaded at startup). */
    void prime(Booster active, List<Booster> waiting) {
        this.lastActive = active == null ? 0 : active.id();
        this.known = ids(waiting);
    }

    /** The changes since the last settled look, in the order to tell them; empty while {@code settled} is false. */
    List<Change> look(Booster active, List<Booster> waiting, boolean settled) {
        if (!settled) {
            return List.of();
        }
        List<Change> changes = new ArrayList<>(2);
        long activeId = active == null ? 0 : active.id();
        if (this.lastActive != 0 && activeId != this.lastActive) {
            changes.add(new Ended(this.lastActive));
        }
        if (active != null && activeId != this.lastActive) {
            changes.add(new Started(active));
        }
        for (int i = 0; i < waiting.size(); i++) {
            Booster booster = waiting.get(i);
            if (!this.known.contains(booster.id()) && booster.id() != this.lastActive) {
                changes.add(new Queued(booster, i + 1));
            }
        }
        this.known = ids(waiting);
        this.lastActive = activeId;
        return changes;
    }

    private static Set<Long> ids(List<Booster> boosters) {
        Set<Long> ids = new HashSet<>();
        for (Booster booster : boosters) {
            ids.add(booster.id());
        }
        return ids;
    }
}
