package net.siftvanilla.siftcore.feature.boosters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The line of boosters: at most one runs, the rest wait in the order they were added, and percentages never stack.
 * Time only passes through {@link #advance}, which the service calls with the time the server really ran, so a
 * booster's remaining time counts only while the server is up. When a booster runs out, the next one starts in the
 * same step and gets the leftover milliseconds taken off, so boosters run back to back without gaps or overlaps.
 * <p>
 * Every change returns the boosters whose stored state changed (ended, started, stopped), for the caller to store.
 * Pure logic; not thread-safe (the service changes it only under the economy lock).
 */
final class BoosterQueue {

    /** How many finished boosters are remembered (for announcements and lookups right after they end). */
    private static final int FINISHED = 32;

    private Booster active;
    private final List<Booster> waiting = new ArrayList<>();
    private final Map<Long, Booster> finished = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Booster> eldest) {
            return size() > FINISHED;
        }
    };

    /** The running booster, or null. */
    Booster active() {
        return this.active;
    }

    /** The waiting boosters, next first (a copy). */
    List<Booster> waiting() {
        return List.copyOf(this.waiting);
    }

    /** How much the running booster raises prices, 0 when none runs. */
    int percent() {
        return this.active == null ? 0 : this.active.percent();
    }

    /**
     * Takes the stored boosters that were running or waiting at the last shutdown. The one that started first runs
     * again; anything else marked running (only after a crash between two writes) waits again, in id order like the
     * rest. Returns the boosters whose state changed.
     */
    List<Booster> load(List<Booster> stored, long now) {
        this.active = null;
        this.waiting.clear();
        this.finished.clear();
        List<Booster> changed = new ArrayList<>();
        List<Booster> running = new ArrayList<>();
        List<Booster> rest = new ArrayList<>();
        for (Booster booster : stored) {
            if (booster.state() == Booster.State.ACTIVE) {
                running.add(booster);
            } else if (booster.state() == Booster.State.QUEUED) {
                rest.add(booster);
            }
        }
        running.sort(Comparator.<Booster>comparingLong(Booster::started).thenComparingLong(Booster::id));
        if (!running.isEmpty()) {
            this.active = running.removeFirst();
            for (Booster extra : running) {
                Booster requeued = extra.requeued();
                rest.add(requeued);
                changed.add(requeued);
            }
        }
        rest.sort(Comparator.comparingLong(Booster::id));
        this.waiting.addAll(rest);
        if (this.active == null && !this.waiting.isEmpty()) {
            // Waiting boosters with nothing running: the next one starts (the server stopped right as one ended).
            this.active = this.waiting.removeFirst().started(now);
            changed.add(this.active);
        }
        return changed;
    }

    /** Adds a booster: it starts at once when none runs, otherwise it waits at the end of the line. */
    List<Booster> add(Booster booster, long now) {
        if (booster.state() != Booster.State.QUEUED) {
            throw new IllegalArgumentException("Only new boosters can be added");
        }
        if (contains(booster.id())) {
            throw new IllegalStateException("Booster " + booster.id() + " is already in line");
        }
        if (this.active == null) {
            this.active = booster.started(now);
            return List.of(this.active);
        }
        this.waiting.add(booster);
        return List.of(booster);
    }

    /**
     * Lets {@code elapsed} milliseconds of server time pass: the running booster loses them, and when it runs out it
     * ends and the next one starts with the rest taken off. Returns the boosters that ended or started.
     */
    List<Booster> advance(long elapsed, long now) {
        if (this.active == null || elapsed <= 0) {
            return List.of();
        }
        List<Booster> changed = new ArrayList<>();
        long left = this.active.remaining() - elapsed;
        boolean startedHere = false;
        while (this.active != null && left <= 0) {
            Booster done = this.active.over(Booster.State.ENDED, now);
            remember(done);
            changed.add(done);
            long overflow = -left;
            if (this.waiting.isEmpty()) {
                this.active = null;
                break;
            }
            this.active = this.waiting.removeFirst().started(now);
            startedHere = true;
            left = this.active.remaining() - overflow;
        }
        if (this.active != null) {
            this.active = this.active.withRemaining(left);
            if (startedHere) {
                changed.add(this.active);
            }
        }
        return changed;
    }

    /**
     * Ends a booster early ({@code state} {@link Booster.State#STOPPED} or {@link Booster.State#REVOKED}): a running one
     * ends and the next starts, a waiting one leaves the line. Returns the boosters that changed (empty when it is not
     * in line).
     */
    List<Booster> end(long id, Booster.State state, long now) {
        if (state != Booster.State.STOPPED && state != Booster.State.REVOKED) {
            throw new IllegalArgumentException("Boosters end early as stopped or revoked, not " + state);
        }
        if (this.active != null && this.active.id() == id) {
            Booster done = this.active.over(state, now);
            remember(done);
            this.active = this.waiting.isEmpty() ? null : this.waiting.removeFirst().started(now);
            return this.active == null ? List.of(done) : List.of(done, this.active);
        }
        for (int i = 0; i < this.waiting.size(); i++) {
            if (this.waiting.get(i).id() == id) {
                Booster done = this.waiting.remove(i).over(state, now);
                remember(done);
                return List.of(done);
            }
        }
        return List.of();
    }

    /**
     * Takes a booster out of line without a trace, undoing an {@link #add} whose row could not be stored. When it was
     * running, the next one starts. Returns the boosters whose stored state changed (not the removed one: it was
     * never stored).
     */
    List<Booster> remove(long id, long now) {
        if (this.active != null && this.active.id() == id) {
            this.active = this.waiting.isEmpty() ? null : this.waiting.removeFirst().started(now);
            return this.active == null ? List.of() : List.of(this.active);
        }
        this.waiting.removeIf(booster -> booster.id() == id);
        return List.of();
    }

    /**
     * Puts a booster back where it was before {@link #end}, undoing a revoke that could not be stored: a running one
     * runs again (the one that started in its place waits first in line again), a waiting one takes its old place.
     * Returns the boosters whose state changed.
     */
    List<Booster> reinstate(Booster before, int index) {
        this.finished.remove(before.id());
        if (before.state() == Booster.State.ACTIVE) {
            List<Booster> changed = new ArrayList<>();
            if (this.active != null) {
                Booster displaced = this.active.requeued();
                this.waiting.addFirst(displaced);
                changed.add(displaced);
            }
            this.active = before.resumed();
            changed.add(this.active);
            return changed;
        }
        Booster again = before.requeued();
        this.waiting.add(Math.clamp(index, 0, this.waiting.size()), again);
        return List.of(again);
    }

    /** Whether a booster is running or waiting. */
    boolean contains(long id) {
        return find(id).isPresent();
    }

    /** The running or waiting booster with this id. */
    Optional<Booster> find(long id) {
        if (this.active != null && this.active.id() == id) {
            return Optional.of(this.active);
        }
        return this.waiting.stream().filter(booster -> booster.id() == id).findFirst();
    }

    /** The running or waiting booster delivered under a store reference. */
    Optional<Booster> byRef(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        if (this.active != null && ref.equals(this.active.ref())) {
            return Optional.of(this.active);
        }
        return this.waiting.stream().filter(booster -> ref.equals(booster.ref())).findFirst();
    }

    /** A waiting booster's place in line (1 is next), 0 when it runs or is not in line. */
    int position(long id) {
        for (int i = 0; i < this.waiting.size(); i++) {
            if (this.waiting.get(i).id() == id) {
                return i + 1;
            }
        }
        return 0;
    }

    /** A booster that ended recently, as it ended. */
    Optional<Booster> finished(long id) {
        return Optional.ofNullable(this.finished.get(id));
    }

    private void remember(Booster done) {
        this.finished.put(done.id(), done);
    }
}
