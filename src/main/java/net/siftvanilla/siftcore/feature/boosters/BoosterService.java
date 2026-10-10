package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The boosters on this server: the line ({@link BoosterQueue}), starting and stopping, store deliveries and refunds,
 * and the clock. The line is held in memory and changed only under the economy lock (store deliveries change it
 * inside their own ledger transaction, so a reference starts at most one booster); every change stores its rows on
 * the database writer in the same order. Readers (sale prices, placeholders, the boss bar) read an immutable
 * {@link View} published after each change, so they never lock.
 * <p>
 * Time passes only through {@link #tick()}, which the feature calls every second on the global thread with the time
 * the server really ran; the running booster's remaining time is stored every minute and at shutdown, so a restart
 * resumes it where it was.
 * <p>
 * A change made inside a ledger transaction (a store delivery, a staff start, a refund) is visible at once but only
 * {@link #settled()} once that transaction is stored: until then it could still be taken back, so nothing about it is
 * announced. Everything shown to players uses {@link #paid(Booster)}, the percent sales really pay right now.
 */
final class BoosterService implements ServerBoosters {

    /**
     * What the line looks like right now.
     *
     * @param active   the running booster as of {@code measured}, or null
     * @param waiting  the waiting boosters, next first
     * @param measured {@link System#nanoTime()} when {@code active}'s remaining time was last counted
     */
    record View(Booster active, List<Booster> waiting, long measured) {

        static final View EMPTY = new View(null, List.of(), 0);

        View {
            waiting = List.copyOf(waiting);
        }

        /** The running booster's time left now (counting since it was measured). */
        Duration left(long nanos) {
            if (this.active == null) {
                return Duration.ZERO;
            }
            long passed = Math.max(0, (nanos - this.measured) / 1_000_000L);
            return Duration.ofMillis(Math.max(0, this.active.remaining() - passed));
        }
    }

    /** Why staff can't start a booster now, or the booster that was added. */
    record Started(Booster booster, String problem) {
    }

    private final Ledger ledger;
    private final Database database;
    private final BoosterStore store;
    private final Supplier<BoostersSettings> settings;
    private final AtomicReference<BoostersSettings> latest;
    private final Logger logger;
    private final LongSupplier clock;
    private final LongSupplier nanos;
    /** Changed only under the economy lock. */
    private final BoosterQueue queue = new BoosterQueue();
    private volatile View view = View.EMPTY;
    private volatile IdSequence ids;
    /** One token per transaction that changed the line and is not stored yet (it could still be taken back). */
    private final Set<Object> unsettled = ConcurrentHashMap.newKeySet();
    /** {@link #nanos} at the last tick; under the economy lock. */
    private long lastTick;

    BoosterService(Ledger ledger, Database database, Supplier<BoostersSettings> settings, AtomicReference<BoostersSettings> latest,
                   Logger logger, LongSupplier clock, LongSupplier nanos) {
        this.ledger = ledger;
        this.database = database;
        this.store = new BoosterStore(database);
        this.settings = settings;
        this.latest = latest;
        this.logger = logger;
        this.clock = clock;
        this.nanos = nanos;
    }

    /** Loads the boosters still in line and starts the clock. Blocking; startup only. */
    void load() throws Exception {
        List<Booster> stored = this.store.loadOpen();
        this.ids = IdSequence.forTable(this.database, "boosters");
        this.ledger.locked(() -> {
            persist(this.queue.load(stored, this.clock.getAsLong()));
            this.lastTick = this.nanos.getAsLong();
            publish();
            return null;
        });
    }

    View view() {
        return this.view;
    }

    /** The running booster's time left now, rounded up to a whole second (zero when none runs). */
    Duration left() {
        Duration left = this.view.left(this.nanos.getAsLong());
        return Duration.ofSeconds((left.toMillis() + 999) / 1000);
    }

    /** A waiting booster's place in line (1 is next), 0 when it is not waiting. */
    int position(long id) {
        List<Booster> waiting = this.view.waiting();
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).id() == id) {
                return i + 1;
            }
        }
        return 0;
    }

    /** A booster that ended recently, as it ended. */
    Optional<Booster> finished(long id) {
        return this.ledger.locked(() -> this.queue.finished(id));
    }

    /**
     * Whether every change to the line is stored for good. False between a delivery, staff start or refund and the
     * database commit of its transaction (a few milliseconds), when it could still be taken back. Read the view first,
     * then this: a change that is in the view and not yet stored is always seen here.
     */
    boolean settled() {
        return this.unsettled.isEmpty();
    }

    /**
     * The percent a booster pays right now: its own, but never more than the current {@code sell.max-percent} (a store
     * booster bought for more, or one started before the limit was lowered, pays the limit). What every message,
     * placeholder and event shows, so they always match the sale prices.
     */
    int paid(Booster booster) {
        return paid(booster.percent());
    }

    // ------------------------------------------------------------------ the clock

    /** Lets the time since the last tick pass. Global thread, every second. */
    void tick() {
        this.ledger.locked(() -> {
            catchUp();
            publish();
            return null;
        });
    }

    /**
     * Lets the time since the last tick pass (under the economy lock). Every change of the line catches up first, so a
     * booster that starts now gets all of its time and one that ends now has used exactly what it ran.
     */
    private void catchUp() {
        long now = this.nanos.getAsLong();
        long elapsed = Math.max(0, (now - this.lastTick) / 1_000_000L);
        this.lastTick += elapsed * 1_000_000L;
        persist(this.queue.advance(elapsed, this.clock.getAsLong()));
    }

    /** Stores the running booster's remaining time (every minute, and at shutdown after a last tick). */
    void storeRemaining() {
        this.ledger.locked(() -> {
            catchUp();
            publish();
            Booster active = this.queue.active();
            if (active != null) {
                this.database.write(BoosterStore.remaining(active)).whenComplete((rows, error) -> {
                    if (error != null) {
                        this.logger.log(Level.WARNING, "Could not store the booster's remaining time", error);
                    }
                });
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ staff

    /**
     * Starts (or queues) a booster for staff or the console. Problems: {@code bad_percent}, {@code bad_duration},
     * {@code queue_full}, {@code unavailable}, or a ledger refusal such as {@code read_only}.
     *
     * @param owner the staff member, or null for the console
     */
    Started start(int percent, Duration duration, UUID owner, String reason, String actor) {
        BoostersSettings s = this.settings.get();
        String problem = s.problem(percent, duration);
        if (problem != null) {
            return new Started(null, problem);
        }
        IdSequence sequence = this.ids;
        if (sequence == null) {
            return new Started(null, "unavailable");
        }
        Booster booster = Booster.queued(sequence.next(), SELL, percent, duration, owner, Booster.Source.STAFF, null, reason, actor,
            this.clock.getAsLong());
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor == null ? "system" : actor).silent()
            .check(() -> this.queue.active() != null && this.queue.waiting().size() >= this.settings.get().queueLimit() ? "queue_full" : null);
        Booster[] added = add(tx, booster);
        TransactionResult result = this.ledger.executeDomain(tx.build());
        if (!result.success()) {
            return new Started(null, result.reason() == null ? "rejected" : result.reason());
        }
        return new Started(added[0], null);
    }

    /**
     * Ends the running booster ({@code id} null) or the booster with this id, running or waiting, early. Returns the
     * booster as it ended, or empty when there was none.
     */
    Optional<Booster> stop(Long id) {
        return this.ledger.locked(() -> {
            catchUp();
            Booster target = id == null ? this.queue.active() : this.queue.find(id).orElse(null);
            if (target == null) {
                return Optional.<Booster>empty();
            }
            List<Booster> changed = this.queue.end(target.id(), Booster.State.STOPPED, this.clock.getAsLong());
            persist(changed);
            publish();
            return changed.stream().filter(booster -> booster.id() == target.id()).findFirst();
        });
    }

    // ------------------------------------------------------------------ ServerBoosters (selling, store)

    @Override
    public boolean available() {
        return this.ids != null;
    }

    @Override
    public int percent() {
        Booster active = this.view.active();
        return active == null ? 0 : paid(active);
    }

    @Override
    public int paid(int percent) {
        return Math.clamp(percent, 0, this.settings.get().maxPercent());
    }

    @Override
    public int maxPercent() {
        return this.settings.get().maxPercent();
    }

    @Override
    public int latestMaxPercent() {
        BoostersSettings parsed = this.latest.get();
        return parsed == null ? maxPercent() : parsed.maxPercent();
    }

    @Override
    public String problem(String kind, int percent, Duration duration) {
        if (!available()) {
            return "unavailable";
        }
        if (!SELL.equals(kind)) {
            return "unknown_kind";
        }
        return storeProblem(percent, duration);
    }

    /**
     * Why a store booster can't be delivered: only the hard limits apply ({@link ServerBoosters#PERCENT_CAP},
     * {@link ServerBoosters#MIN_LENGTH} to {@link ServerBoosters#MAX_LENGTH}). The configured limits are for staff; a
     * paid booster above {@code sell.max-percent} is still delivered and pays the limit, so a purchase is never lost to
     * a config change.
     */
    static String storeProblem(int percent, Duration duration) {
        if (percent < 1 || percent > PERCENT_CAP) {
            return "bad_percent";
        }
        if (duration == null || duration.compareTo(MIN_LENGTH) < 0 || duration.compareTo(MAX_LENGTH) > 0) {
            return "bad_duration";
        }
        return null;
    }

    @Override
    public void deliver(LedgerTx.Builder tx, Grant grant) {
        IdSequence sequence = this.ids;
        if (sequence == null) {
            throw new IllegalStateException("Boosters are not loaded yet");
        }
        add(tx, Booster.queued(sequence.next(), grant.kind(), grant.percent(), grant.duration(), grant.owner(), Booster.Source.STORE,
            grant.ref(), null, grant.actor(), this.clock.getAsLong()));
    }

    /** Adds a booster to a transaction: in line when it applies, its row written with it. Returns it as it was added. */
    private Booster[] add(LedgerTx.Builder tx, Booster booster) {
        Booster[] added = new Booster[1];
        Object token = new Object();
        tx.apply(() -> {
            this.unsettled.add(token);
            catchUp();
            added[0] = this.queue.add(booster, this.clock.getAsLong()).getFirst();
            publish();
        }, () -> {
            // The row could not be stored: the booster never existed. When it was running, the next one starts.
            persist(this.queue.remove(booster.id(), this.clock.getAsLong()));
            publish();
            this.unsettled.remove(token);
        });
        tx.write(c -> BoosterStore.insert(c, added[0]));
        tx.afterCommit(() -> this.unsettled.remove(token));
        return added;
    }

    @Override
    public AtomicReference<Revoked> revoke(LedgerTx.Builder tx, String ref) {
        AtomicReference<Revoked> outcome = new AtomicReference<>(Revoked.OVER);
        Booster[] before = new Booster[1];
        int[] index = new int[1];
        AtomicReference<List<Booster>> changed = new AtomicReference<>(List.of());
        Object token = new Object();
        tx.apply(() -> {
            this.unsettled.add(token);
            catchUp();
            Optional<Booster> found = this.queue.byRef(ref);
            if (found.isEmpty()) {
                outcome.set(Revoked.OVER);
                return;
            }
            before[0] = found.get();
            index[0] = Math.max(0, this.queue.position(before[0].id()) - 1);
            changed.set(this.queue.end(before[0].id(), Booster.State.REVOKED, this.clock.getAsLong()));
            outcome.set(before[0].state() == Booster.State.ACTIVE ? Revoked.ENDED : Revoked.REMOVED);
            publish();
        }, () -> {
            if (before[0] != null) {
                persist(this.queue.reinstate(before[0], index[0]));
                before[0] = null;
                publish();
            }
            this.unsettled.remove(token);
        });
        tx.write(c -> {
            BoosterStore.update(c, changed.get());
            return null;
        });
        tx.afterCommit(() -> this.unsettled.remove(token));
        return outcome;
    }

    @Override
    public Optional<Status> status(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        View current = this.view;
        if (current.active() != null && ref.equals(current.active().ref())) {
            return Optional.of(new Status(true, paid(current.active()), current.left(this.nanos.getAsLong()), 0));
        }
        List<Booster> waiting = current.waiting();
        for (int i = 0; i < waiting.size(); i++) {
            Booster booster = waiting.get(i);
            if (ref.equals(booster.ref())) {
                return Optional.of(new Status(false, paid(booster), booster.left(), i + 1));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ helpers

    /** Stores the boosters whose state changed, in order with every other write. Under the economy lock. */
    private void persist(List<Booster> changed) {
        if (changed.isEmpty()) {
            return;
        }
        List<Booster> rows = List.copyOf(changed);
        this.database.write(c -> {
            BoosterStore.update(c, rows);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not store a booster change", error);
            }
        });
    }

    /** Publishes the line for readers. Under the economy lock. */
    private void publish() {
        // The running booster's remaining time was counted at the last tick.
        this.view = new View(this.queue.active(), this.queue.waiting(), this.lastTick);
    }
}
