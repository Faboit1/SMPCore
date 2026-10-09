package net.siftvanilla.siftcore.feature.integrations;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * Delivers store purchases (money, shards, crate keys, LuckPerms ranks, server sell boosters) exactly once per
 * reference, and takes them back again on a refund or chargeback.
 * <p>
 * Every delivered reference is held in memory (loaded at startup) and in {@code store_deliveries}; the memory copy
 * changes only inside economy transactions, under the economy lock, so two deliveries with the same reference can
 * never both pass. How each kind stays exactly-once:
 * <ul>
 *   <li>Money and shards: one ledger transaction pays and stores the reference together.</li>
 *   <li>Keys: the crates feature applies the grant with the reference {@code store:<ref>} atomically (and refuses it
 *       a second time); the reference is recorded here once the grant committed. A crash in between is caught by the
 *       crate grant reference on the next attempt, which is then recorded as already delivered.</li>
 *   <li>Ranks: LuckPerms is outside the database, so the reference is first stored as pending with the rank's end
 *       worked out, then LuckPerms is told to make the player hold the group at least that long (idempotent), then
 *       the reference is marked done. A pending reference (crash or LuckPerms failure) is finished by the next
 *       attempt with the same reference or the next start. Rank work of one player runs one step at a time, so two
 *       purchases of the same rank add up.</li>
 *   <li>Boosters: one transaction (no money moves) stores the reference and puts the booster in line, with its row,
 *       through {@link ServerBoosters#deliver}; it starts at once when no booster runs, otherwise it waits its turn.</li>
 * </ul>
 * A revoked reference stays recorded (so it is never delivered again). Money and shards are taken back in one ledger
 * transaction with the state change (as much as the player still has); ranks are revoked through a recorded plan,
 * like a delivery; crate keys can't be taken back through the crates contract, so only the record changes; a booster is
 * ended when it runs, taken out of line when it waits, and left alone once it is over, in one transaction with the
 * record.
 * Thread-safe; nothing blocks. Results complete after the change is durably stored.
 */
final class StoreService {

    /** Ledger kind of store money and shards. */
    static final String KIND = "store";
    /** Ledger kind of money and shards taken back by a revoke. */
    static final String REVOKE_KIND = "store_revoke";
    /** Prefix of the crate grant references store keys use. */
    static final String KEY_REF_PREFIX = "store:";
    /** The account a booster from the server itself (the console, community goals) is recorded under. */
    static final UUID SERVER = new UUID(0L, 0L);

    /** How a request ended. */
    enum Status {
        /** Delivered now. */
        DELIVERED,
        /** Revoked now. */
        REVOKED,
        /** The reference was delivered (or revoked) before; nothing changed. */
        ALREADY,
        /** Nothing happened; nothing recorded unless {@link Outcome#delivery()} is a rank waiting for LuckPerms. */
        FAILED
    }

    /**
     * @param delivery the recorded delivery (null when nothing was recorded)
     * @param reason   a machine reason for failures, e.g. {@code too_much} or {@code luckperms_failed}; for a booster revoke what
     *                 happened to the booster ({@code ended}, {@code removed} or {@code over})
     * @param detail   extra text for failures (an exception message), may be null
     * @param taken    for a revoke of money or shards: how much was taken back
     */
    record Outcome(Status status, Delivery delivery, String reason, String detail, long taken) {

        static Outcome delivered(Delivery delivery) {
            return new Outcome(Status.DELIVERED, delivery, null, null, 0);
        }

        static Outcome revoked(Delivery delivery, long taken) {
            return new Outcome(Status.REVOKED, delivery, null, null, taken);
        }

        static Outcome already(Delivery delivery) {
            return new Outcome(Status.ALREADY, delivery, null, null, 0);
        }

        static Outcome failed(String reason) {
            return new Outcome(Status.FAILED, null, reason, null, 0);
        }

        static Outcome failed(String reason, Delivery delivery, String detail) {
            return new Outcome(Status.FAILED, delivery, reason, detail, 0);
        }
    }

    /** Lets plugins cancel a delivery ({@link StoreDeliveryEvent}); returns whether it may go ahead. */
    @FunctionalInterface
    interface Gate {
        boolean allow(UUID player, StoreDeliveryEvent.Kind kind, String item, long amount, Duration duration, String ref, String actor);
    }

    private static final String INSERT = "INSERT INTO store_deliveries (ref, kind, uuid, item, amount, duration, state, actor, ts, "
        + "until_ts, revoke_until, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE store_deliveries SET state = ?, revoke_until = ?, note = ? WHERE ref = ?";
    /** How often a revoke retries when the balance changed between reading it and taking the money. */
    private static final int REVOKE_ATTEMPTS = 5;

    private final Ledger ledger;
    private final Database database;
    private final CrateKeys keys;
    private final ServerBoosters boosters;
    private final Supplier<RankAccess> ranks;
    private final Supplier<IntegrationsSettings.Store> settings;
    private final Gate gate;
    private final LongSupplier clock;
    /** Every recorded reference; read and changed only under the economy lock. */
    private final Map<String, Delivery> book = new HashMap<>();
    private final Map<UUID, CompletableFuture<?>> lanes = new ConcurrentHashMap<>();

    StoreService(Ledger ledger, Database database, CrateKeys keys, ServerBoosters boosters, Supplier<RankAccess> ranks,
                 Supplier<IntegrationsSettings.Store> settings, Gate gate, LongSupplier clock) {
        this.ledger = ledger;
        this.database = database;
        this.keys = keys;
        this.boosters = boosters;
        this.ranks = ranks;
        this.settings = settings;
        this.gate = gate;
        this.clock = clock;
    }

    /** Loads every recorded reference. Blocking; startup only. Returns the rank work waiting for LuckPerms. */
    List<Delivery> load() throws Exception {
        List<Delivery> rows = this.database.read(c -> {
            List<Delivery> list = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT ref, kind, uuid, item, amount, duration, "
                + "state, actor, ts, until_ts, revoke_until, note FROM store_deliveries")) {
                while (rs.next()) {
                    StoreDeliveryEvent.Kind kind;
                    UUID uuid;
                    try {
                        kind = StoreDeliveryEvent.Kind.valueOf(rs.getString(2).toUpperCase(Locale.ROOT));
                        uuid = UUID.fromString(rs.getString(3));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    list.add(new Delivery(rs.getString(1), kind, uuid, rs.getString(4), rs.getLong(5), rs.getLong(6),
                        Delivery.State.byId(rs.getString(7)), rs.getString(8), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                        rs.getString(12)));
                }
            }
            return list;
        }).get();
        return this.ledger.locked(() -> {
            this.book.clear();
            List<Delivery> unfinished = new ArrayList<>();
            for (Delivery delivery : rows) {
                this.book.put(delivery.ref(), delivery);
                if (delivery.unfinished()) {
                    unfinished.add(delivery);
                }
            }
            return unfinished;
        });
    }

    /** The delivery recorded under a reference, or null. */
    Delivery find(String ref) {
        return this.ledger.locked(() -> this.book.get(ref));
    }

    /** A player's deliveries, newest first. */
    List<Delivery> history(UUID player, int limit) {
        List<Delivery> list = this.ledger.locked(() -> {
            List<Delivery> mine = new ArrayList<>();
            for (Delivery delivery : this.book.values()) {
                if (delivery.player().equals(player)) {
                    mine.add(delivery);
                }
            }
            return mine;
        });
        list.sort(Comparator.comparingLong(Delivery::time).reversed());
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : List.copyOf(list);
    }

    int size() {
        return this.ledger.locked(this.book::size);
    }

    /** Rank deliveries and revokes waiting for LuckPerms. */
    int pendingCount() {
        return this.ledger.locked(() -> (int) this.book.values().stream().filter(Delivery::unfinished).count());
    }

    // ------------------------------------------------------------------ money and shards

    CompletableFuture<Outcome> currency(UUID player, Currency currency, long amount, String ref, String actor) {
        String problem = StoreRules.refProblem(ref);
        if (problem != null) {
            return done(Outcome.failed("bad_ref"));
        }
        IntegrationsSettings.Store limits = this.settings.get();
        long max = currency == Currency.MONEY ? limits.maxMoney() : limits.maxShards();
        if (amount <= 0) {
            return done(Outcome.failed("bad_amount"));
        }
        if (amount > max) {
            return done(Outcome.failed("too_much"));
        }
        Delivery existing = find(ref);
        if (existing != null) {
            return done(Outcome.already(existing));
        }
        StoreDeliveryEvent.Kind kind = currency == Currency.MONEY ? StoreDeliveryEvent.Kind.MONEY : StoreDeliveryEvent.Kind.SHARDS;
        if (!this.gate.allow(player, kind, currency.id(), amount, null, ref, actor)) {
            return done(Outcome.failed("cancelled"));
        }
        Delivery delivery = Delivery.of(ref, kind, player, currency.id(), amount, 0, Delivery.State.DONE, actor, this.clock.getAsLong(), 0);
        LedgerTx tx = LedgerTx.builder()
            .actor(actorOf(actor))
            .note("store " + ref)
            .source(player, currency, amount, KIND, ref)
            .check(() -> this.book.containsKey(ref) ? "duplicate" : null)
            .apply(() -> this.book.put(ref, delivery), () -> this.book.remove(ref, delivery))
            .write(insert(delivery))
            .build();
        TransactionResult result = this.ledger.execute(tx);
        if (!result.success()) {
            return done(refused(result, ref));
        }
        return stored(result, Outcome.delivered(delivery));
    }

    // ------------------------------------------------------------------ keys

    CompletableFuture<Outcome> keys(UUID player, String crate, int amount, String ref, String actor) {
        if (StoreRules.refProblem(ref) != null) {
            return done(Outcome.failed("bad_ref"));
        }
        if (amount <= 0) {
            return done(Outcome.failed("bad_amount"));
        }
        if (amount > this.settings.get().maxKeys()) {
            return done(Outcome.failed("too_much"));
        }
        if (!this.keys.crates().contains(crate)) {
            return done(Outcome.failed("unknown_crate"));
        }
        Delivery existing = find(ref);
        if (existing != null) {
            return done(Outcome.already(existing));
        }
        if (!this.gate.allow(player, StoreDeliveryEvent.Kind.KEYS, crate, amount, null, ref, actor)) {
            return done(Outcome.failed("cancelled"));
        }
        Delivery delivery = Delivery.of(ref, StoreDeliveryEvent.Kind.KEYS, player, crate, amount, 0, Delivery.State.DONE, actor,
            this.clock.getAsLong(), 0);
        TransactionResult granted = this.keys.give(player, crate, amount, actor, KEY_REF_PREFIX + ref);
        if (granted.success()) {
            // Once the grant committed the keys are delivered. Should recording the reference fail, the crate grant
            // reference still stops a second grant, and the next attempt records it.
            return granted.committed()
                .thenCompose(ignored -> record(delivery).handle((recorded, error) -> Outcome.delivered(delivery)))
                .exceptionally(error -> Outcome.failed("storage", null, message(error)));
        }
        if ("duplicate".equals(granted.reason())) {
            // The crates feature applied this reference before (a crash between its grant and our record, or the same
            // reference twice at once): it was delivered, so record it and report it as such.
            return record(delivery).thenApply(recorded -> Outcome.already(recorded))
                .exceptionally(error -> Outcome.already(delivery));
        }
        return done(Outcome.failed(granted.reason() == null ? granted.status().name().toLowerCase(Locale.ROOT) : granted.reason()));
    }

    /** Records a delivery unless its reference is already recorded; completes with the recorded one. */
    private CompletableFuture<Delivery> record(Delivery delivery) {
        LedgerTx tx = LedgerTx.builder()
            .actor(actorOf(delivery.actor()))
            .silent()
            .check(() -> this.book.containsKey(delivery.ref()) ? "duplicate" : null)
            .apply(() -> this.book.put(delivery.ref(), delivery), () -> this.book.remove(delivery.ref(), delivery))
            .write(insert(delivery))
            .build();
        TransactionResult result = this.ledger.execute(tx);
        if (!result.success()) {
            Delivery existing = find(delivery.ref());
            return CompletableFuture.completedFuture(existing == null ? delivery : existing);
        }
        return result.committed().thenApply(ignored -> delivery);
    }

    // ------------------------------------------------------------------ boosters

    /**
     * Starts (or queues) a server-wide sell booster: one transaction stores the reference and puts the booster in line.
     *
     * @param player the buyer, or {@link #SERVER} for a booster from the server itself (a community goal)
     */
    CompletableFuture<Outcome> booster(UUID player, String kind, int percent, Duration duration, String ref, String actor) {
        if (StoreRules.refProblem(ref) != null) {
            return done(Outcome.failed("bad_ref"));
        }
        String problem = this.boosters.problem(kind, percent, duration);
        if (problem != null) {
            return done(Outcome.failed(switch (problem) {
                case "unknown_kind" -> "unknown_booster";
                case "bad_percent" -> "bad_booster_percent";
                case "bad_duration" -> "bad_booster_duration";
                default -> "no_boosters";
            }));
        }
        Delivery existing = find(ref);
        if (existing != null) {
            return done(Outcome.already(existing));
        }
        if (!this.gate.allow(player, StoreDeliveryEvent.Kind.BOOSTER, kind, percent, duration, ref, actor)) {
            return done(Outcome.failed("cancelled"));
        }
        Delivery delivery = Delivery.of(ref, StoreDeliveryEvent.Kind.BOOSTER, player, kind, percent, duration.toSeconds(),
            Delivery.State.DONE, actor, this.clock.getAsLong(), 0);
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actorOf(actor))
            .note("store " + ref)
            .silent()
            .check(() -> this.book.containsKey(ref) ? "duplicate" : null)
            .apply(() -> this.book.put(ref, delivery), () -> this.book.remove(ref, delivery))
            .write(insert(delivery));
        this.boosters.deliver(tx, new ServerBoosters.Grant(kind, percent, duration, SERVER.equals(player) ? null : player, ref, actor));
        TransactionResult result = this.ledger.execute(tx.build());
        if (!result.success()) {
            return done(refused(result, ref));
        }
        return stored(result, Outcome.delivered(delivery));
    }

    /** Where the booster delivered under a reference stands: running or waiting, empty once it is over. */
    Optional<ServerBoosters.Status> boosterStatus(String ref) {
        return this.boosters.status(ref);
    }

    /** What a booster bought for {@code percent} pays right now (never more than {@code sell.max-percent}). */
    int boosterPaid(int percent) {
        return this.boosters.paid(percent);
    }

    // ------------------------------------------------------------------ ranks

    /**
     * Grants a LuckPerms group, permanently ({@code duration} null) or for a time added to what the player already
     * has. A pending delivery with this reference is finished instead.
     */
    CompletableFuture<Outcome> rank(UUID player, String groupInput, Duration duration, String ref, String actor) {
        if (StoreRules.refProblem(ref) != null) {
            return done(Outcome.failed("bad_ref"));
        }
        RankAccess access = this.ranks.get();
        if (!access.available()) {
            return done(Outcome.failed("no_luckperms"));
        }
        Delivery existing = find(ref);
        if (existing != null) {
            if (existing.kind() == StoreDeliveryEvent.Kind.RANK && existing.pending()) {
                return lane(existing.player(), () -> finishRank(existing, access));
            }
            return done(Outcome.already(existing));
        }
        String group = StoreRules.group(groupInput);
        IntegrationsSettings.Store limits = this.settings.get();
        if (group == null || !limits.allowsGroup(group)) {
            return done(Outcome.failed("group_not_allowed"));
        }
        if (!access.groupExists(group)) {
            return done(Outcome.failed("unknown_group"));
        }
        if (duration != null && (duration.compareTo(limits.minRankDuration()) < 0 || duration.compareTo(limits.maxRankDuration()) > 0)) {
            return done(Outcome.failed("bad_duration"));
        }
        if (!this.gate.allow(player, StoreDeliveryEvent.Kind.RANK, group, 0, duration, ref, actor)) {
            return done(Outcome.failed("cancelled"));
        }
        long seconds = duration == null ? 0 : duration.toSeconds();
        return lane(player, () -> access.held(player, group).thenCompose(held -> {
            Instant now = Instant.ofEpochMilli(this.clock.getAsLong());
            Instant end = StoreRules.rankEnd(held.permanent(), held.until(), now, duration);
            Delivery pending = Delivery.of(ref, StoreDeliveryEvent.Kind.RANK, player, group, 0, seconds, Delivery.State.PENDING,
                actor, now.toEpochMilli(), end == null ? 0 : end.getEpochSecond());
            LedgerTx tx = LedgerTx.builder()
                .actor(actorOf(actor))
                .silent()
                .check(() -> this.book.containsKey(ref) ? "duplicate" : null)
                .apply(() -> this.book.put(ref, pending), () -> this.book.remove(ref, pending))
                .write(insert(pending))
                .build();
            TransactionResult reserved = this.ledger.execute(tx);
            if (!reserved.success()) {
                return CompletableFuture.completedFuture(refused(reserved, ref));
            }
            return afterCommit(reserved, () -> finishRank(pending, access));
        })).exceptionally(error -> Outcome.failed("luckperms_failed", find(ref), message(error)));
    }

    /** Finishes every rank delivery and revoke that waits for LuckPerms (at startup). */
    List<CompletableFuture<Outcome>> resumePending() {
        RankAccess access = this.ranks.get();
        List<CompletableFuture<Outcome>> results = new ArrayList<>();
        if (!access.available()) {
            return results;
        }
        List<Delivery> unfinished = this.ledger.locked(() -> this.book.values().stream().filter(Delivery::unfinished).toList());
        for (Delivery delivery : unfinished) {
            results.add(lane(delivery.player(), () -> delivery.pending() ? finishRank(delivery, access) : finishRevoke(delivery, access)));
        }
        return results;
    }

    private CompletableFuture<Outcome> finishRank(Delivery pending, RankAccess access) {
        Instant until = pending.until() == 0 ? null : Instant.ofEpochSecond(pending.until());
        return access.ensure(pending.player(), pending.item(), until)
            .thenCompose(changed -> markDone(pending))
            .exceptionally(error -> Outcome.failed("luckperms_failed", pending, message(error)));
    }

    private CompletableFuture<Outcome> markDone(Delivery pending) {
        Delivery done = pending.done();
        String ref = pending.ref();
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .actor(actorOf(pending.actor()))
            .silent()
            .check(() -> {
                Delivery current = this.book.get(ref);
                return current != null && current.pending() ? null : "not_pending";
            })
            .apply(() -> this.book.put(ref, done), () -> this.book.put(ref, pending))
            .write(update(done))
            .build());
        if (!result.success()) {
            // Finished by a concurrent attempt with the same reference (the lane makes this rare).
            Delivery current = find(ref);
            return CompletableFuture.completedFuture(Outcome.already(current == null ? done : current));
        }
        return stored(result, Outcome.delivered(done));
    }

    // ------------------------------------------------------------------ revoke

    /**
     * Takes a delivery back (a refund or chargeback) and keeps the reference recorded as revoked, so the store can
     * never deliver it again. Money and shards: as much of the amount as the player still has. Ranks: a timed purchase
     * takes its time off the player's current grant (removing it when nothing is left), a permanent purchase removes
     * the permanent grant. Keys: only the record changes (the crates contract can't take keys).
     *
     * @param reason a short word for the record and the audit ({@code refund}, {@code chargeback})
     */
    CompletableFuture<Outcome> revoke(String ref, String reason, String actor) {
        Delivery delivery = find(ref);
        if (delivery == null) {
            return done(Outcome.failed("unknown_ref"));
        }
        if (delivery.state() == Delivery.State.REVOKED) {
            return done(Outcome.already(delivery));
        }
        String why = StoreRules.reason(reason);
        return switch (delivery.kind()) {
            case MONEY, SHARDS -> revokeCurrency(delivery, why, actor);
            case KEYS -> markRevoked(delivery, delivery.revoked(why + ", keys not taken back"), 0);
            case BOOSTER -> revokeBooster(delivery, why, actor);
            case RANK -> {
                RankAccess access = this.ranks.get();
                if (!access.available()) {
                    yield done(Outcome.failed("no_luckperms"));
                }
                yield lane(delivery.player(), () -> revokeRank(ref, why, actor, access))
                    .exceptionally(error -> Outcome.failed("luckperms_failed", find(ref), message(error)));
            }
        };
    }

    /** Takes back as much as the player still has, in one transaction with the state change; retries if the balance moved. */
    private CompletableFuture<Outcome> revokeCurrency(Delivery delivery, String why, String actor) {
        Currency currency = delivery.kind() == StoreDeliveryEvent.Kind.MONEY ? Currency.MONEY : Currency.SHARDS;
        String ref = delivery.ref();
        for (int attempt = 0; attempt < REVOKE_ATTEMPTS; attempt++) {
            long take = Math.min(delivery.amount(), Math.max(0, this.ledger.balance(delivery.player(), currency)));
            Delivery revoked = delivery.revoked(take == delivery.amount() ? why : why + ", took back " + take + " of " + delivery.amount());
            LedgerTx.Builder tx = LedgerTx.builder().actor(actorOf(actor)).note("store revoke " + ref);
            if (take > 0) {
                tx.sink(delivery.player(), currency, take, REVOKE_KIND, ref);
            } else {
                tx.silent();
            }
            TransactionResult result = this.ledger.execute(tx
                .check(() -> {
                    Delivery current = this.book.get(ref);
                    return current != null && current.state() == Delivery.State.DONE ? null : "not_done";
                })
                .apply(() -> this.book.put(ref, revoked), () -> this.book.put(ref, delivery))
                .write(update(revoked))
                .build());
            if (result.success()) {
                return stored(result, Outcome.revoked(revoked, take));
            }
            if (result.status() == TransactionStatus.INSUFFICIENT_FUNDS) {
                continue;
            }
            if ("not_done".equals(result.reason())) {
                Delivery current = find(ref);
                return done(current != null && current.state() == Delivery.State.REVOKED ? Outcome.already(current) : Outcome.failed("busy"));
            }
            return done(refused(result, ref));
        }
        return done(Outcome.failed("busy"));
    }

    /**
     * Ends the booster when it runs, takes it out of line when it waits, or leaves it when it is over, in one
     * transaction with the revoked record. The note says which.
     */
    private CompletableFuture<Outcome> revokeBooster(Delivery delivery, String why, String actor) {
        String ref = delivery.ref();
        Delivery[] revoked = new Delivery[1];
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actorOf(actor))
            .note("store revoke " + ref)
            .silent()
            .check(() -> {
                Delivery current = this.book.get(ref);
                return current != null && current.state() == Delivery.State.DONE ? null : "not_done";
            });
        AtomicReference<ServerBoosters.Revoked> what = this.boosters.revoke(tx, ref);
        tx.apply(() -> {
            revoked[0] = delivery.revoked(why + switch (what.get()) {
                case ENDED -> ", booster ended early";
                case REMOVED -> ", booster taken out of line";
                case OVER -> ", booster had already ended";
            });
            this.book.put(ref, revoked[0]);
        }, () -> this.book.put(ref, delivery));
        tx.write(c -> update(revoked[0]).run(c));
        TransactionResult result = this.ledger.execute(tx.build());
        if (!result.success()) {
            if ("not_done".equals(result.reason())) {
                Delivery current = find(ref);
                return done(current != null && current.state() == Delivery.State.REVOKED ? Outcome.already(current) : Outcome.failed("busy"));
            }
            return done(refused(result, ref));
        }
        return result.committed()
            .thenApply(ignored -> new Outcome(Status.REVOKED, revoked[0], what.get().name().toLowerCase(Locale.ROOT), null, 0))
            .exceptionally(error -> Outcome.failed("storage", null, message(error)));
    }

    private CompletableFuture<Outcome> revokeRank(String ref, String why, String actor, RankAccess access) {
        Delivery current = find(ref);
        if (current == null) {
            return done(Outcome.failed("unknown_ref"));
        }
        return switch (current.state()) {
            case REVOKED -> done(Outcome.already(current));
            case REVOKING -> finishRevoke(current, access);
            // A delivery still waiting for LuckPerms is finished first, so the time taken off is time it really added.
            case PENDING -> finishRank(current, access).thenCompose(finished -> finished.status() == Status.FAILED
                ? done(finished)
                : revokeRank(ref, why, actor, access));
            case DONE -> access.held(current.player(), current.item()).thenCompose(held -> {
                long plan = revokePlan(current, held, Instant.ofEpochMilli(this.clock.getAsLong()));
                Delivery revoking = current.revoking(plan, why);
                TransactionResult reserved = this.ledger.execute(LedgerTx.builder()
                    .actor(actorOf(actor))
                    .silent()
                    .check(() -> {
                        Delivery now = this.book.get(ref);
                        return now != null && now.state() == Delivery.State.DONE ? null : "not_done";
                    })
                    .apply(() -> this.book.put(ref, revoking), () -> this.book.put(ref, current))
                    .write(update(revoking))
                    .build());
                if (!reserved.success()) {
                    Delivery now = find(ref);
                    return done(now != null && now.revoked() ? Outcome.already(now) : refused(reserved, ref));
                }
                return afterCommit(reserved, () -> finishRevoke(revoking, access));
            });
        };
    }

    /**
     * What revoking a rank delivery does to the player's timed grants: -1 leaves them alone (a permanent purchase, or no
     * timed grant left), 0 removes them (the purchase was all the time left), more cuts them to that epoch second.
     */
    static long revokePlan(Delivery delivery, RankAccess.Held held, Instant now) {
        if (delivery.duration() == 0 || held.until() == null) {
            return -1;
        }
        Instant end = held.until().minusSeconds(delivery.duration());
        return end.isAfter(now) ? end.getEpochSecond() : 0;
    }

    private CompletableFuture<Outcome> finishRevoke(Delivery revoking, RankAccess access) {
        boolean removePermanent = revoking.duration() == 0;
        boolean cutTimed = revoking.revokeUntil() >= 0;
        Instant cutTo = revoking.revokeUntil() > 0 ? Instant.ofEpochSecond(revoking.revokeUntil()) : null;
        return access.limit(revoking.player(), revoking.item(), removePermanent, cutTimed, cutTo)
            .thenCompose(changed -> markRevoked(revoking, revoking.revoked(revoking.note()), 0))
            .exceptionally(error -> Outcome.failed("luckperms_failed", revoking, message(error)));
    }

    /** Records a revoke that needs no money moved (keys, or the last step of a rank). */
    private CompletableFuture<Outcome> markRevoked(Delivery before, Delivery revoked, long taken) {
        String ref = before.ref();
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .actor("system")
            .silent()
            .check(() -> {
                Delivery current = this.book.get(ref);
                return current != null && current.state() == before.state() ? null : "changed";
            })
            .apply(() -> this.book.put(ref, revoked), () -> this.book.put(ref, before))
            .write(update(revoked))
            .build());
        if (!result.success()) {
            Delivery current = find(ref);
            return done(current != null && current.state() == Delivery.State.REVOKED ? Outcome.already(current) : Outcome.failed("busy"));
        }
        return stored(result, Outcome.revoked(revoked, taken));
    }

    // ------------------------------------------------------------------ lanes

    /** Runs rank work for one player after their earlier rank work, one at a time. */
    private CompletableFuture<Outcome> lane(UUID player, Supplier<CompletableFuture<Outcome>> work) {
        // The work starts only when this call releases it, outside the map's lock, after the player's earlier work.
        CompletableFuture<Void> release = new CompletableFuture<>();
        List<CompletableFuture<Outcome>> mine = new ArrayList<>(1);
        this.lanes.compute(player, (key, previous) -> {
            CompletableFuture<?> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<Outcome> next = before.handle((ignored, error) -> null)
                .thenCompose(ignored -> release)
                .thenCompose(ignored -> start(work));
            mine.add(next);
            return next;
        });
        CompletableFuture<Outcome> next = mine.getFirst();
        next.whenComplete((outcome, error) -> this.lanes.remove(player, next));
        release.complete(null);
        return next;
    }

    private static CompletableFuture<Outcome> start(Supplier<CompletableFuture<Outcome>> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the next step once a reservation is stored; a storage failure ends it (the reservation was undone). */
    private static CompletableFuture<Outcome> afterCommit(TransactionResult reserved, Supplier<CompletableFuture<Outcome>> next) {
        return reserved.committed()
            .handle((ignored, error) -> error)
            .thenCompose(error -> error != null ? done(Outcome.failed("storage", null, message(error))) : next.get());
    }

    private Outcome refused(TransactionResult result, String ref) {
        if (result.status() == TransactionStatus.REJECTED && "duplicate".equals(result.reason())) {
            return Outcome.already(find(ref));
        }
        return Outcome.failed(switch (result.status()) {
            case BALANCE_LIMIT -> "balance_limit";
            case CANCELLED -> "cancelled";
            case UNAVAILABLE -> "unavailable";
            case INSUFFICIENT_FUNDS -> "insufficient_funds";
            default -> result.reason() == null ? "rejected" : result.reason();
        });
    }

    private static CompletableFuture<Outcome> stored(TransactionResult result, Outcome outcome) {
        return result.committed().thenApply(ignored -> outcome)
            .exceptionally(error -> Outcome.failed("storage", null, message(error)));
    }

    private static String actorOf(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor;
    }

    private static CompletableFuture<Outcome> done(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String actor36(String actor) {
        return actor == null ? null : actor.length() > 36 ? actor.substring(0, 36) : actor;
    }

    private static String note128(String note) {
        return note == null ? null : note.length() > 128 ? note.substring(0, 128) : note;
    }

    private static SqlWork<Integer> insert(Delivery delivery) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                ps.setString(1, delivery.ref());
                ps.setString(2, delivery.kind().name().toLowerCase(Locale.ROOT));
                ps.setString(3, delivery.player().toString());
                ps.setString(4, delivery.item());
                ps.setLong(5, delivery.amount());
                ps.setLong(6, delivery.duration());
                ps.setString(7, delivery.state().id());
                ps.setString(8, actor36(delivery.actor()));
                ps.setLong(9, delivery.time());
                ps.setLong(10, delivery.until());
                ps.setLong(11, delivery.revokeUntil());
                if (delivery.note() == null) {
                    ps.setNull(12, Types.VARCHAR);
                } else {
                    ps.setString(12, note128(delivery.note()));
                }
                return ps.executeUpdate();
            }
        };
    }

    private static SqlWork<Integer> update(Delivery delivery) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(UPDATE)) {
                ps.setString(1, delivery.state().id());
                ps.setLong(2, delivery.revokeUntil());
                if (delivery.note() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, note128(delivery.note()));
                }
                ps.setString(4, delivery.ref());
                return ps.executeUpdate();
            }
        };
    }
}
