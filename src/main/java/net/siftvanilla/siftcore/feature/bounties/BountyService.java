package net.siftvanilla.siftcore.feature.bounties;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The money side of bounties, free of the server so it can be tested against a real ledger. Every change is one
 * ledger transaction that moves the money (sponsor to escrow, escrow to killer plus tax, escrow back to sponsor),
 * changes the in-memory book in its apply step and writes the {@code bounties} rows in the same database
 * transaction as the ledger rows. So the bounty escrow always holds exactly the sum of the active contributions,
 * in memory and in storage, whatever fails.
 */
final class BountyService {

    static final UUID ESCROW = SystemAccounts.BOUNTY_ESCROW;
    static final String KIND_PLACE = "bounty_place";
    static final String KIND_CLAIM = "bounty_claim";
    static final String KIND_TAX = "bounty_tax";
    static final String KIND_REFUND = "bounty_refund";

    static final String ACTIVE = "ACTIVE";
    static final String CLAIMED = "CLAIMED";
    static final String EXPIRED = "EXPIRED";
    static final String REMOVED = "REMOVED";

    /** Why a bounty can't be placed, before any money moves. */
    enum PlaceProblem {
        YOURSELF,
        BELOW_MINIMUM
    }

    /** A placement: the ledger result, the new contribution and the target's total right after. */
    record Placed(TransactionResult result, BountyBook.Contribution contribution, long totalAfter) {
    }

    /** What a killer would claim from a victim: the contributions, their total and the tax. */
    record Plan(UUID killer, UUID victim, List<BountyBook.Contribution> contributions, BountyMath.Split split) {

        Plan {
            contributions = List.copyOf(contributions);
        }

        /** Sponsors who are paid out, without repeats. */
        List<UUID> sponsors() {
            List<UUID> sponsors = new ArrayList<>();
            for (BountyBook.Contribution contribution : this.contributions) {
                if (!sponsors.contains(contribution.sponsor())) {
                    sponsors.add(contribution.sponsor());
                }
            }
            return sponsors;
        }
    }

    /** One refunded (or not refundable) contribution. */
    record Refund(BountyBook.Contribution contribution, TransactionResult result) {
    }

    private final Ledger ledger;
    private final Database database;
    private final BountyBook book;
    private volatile IdSequence ids;

    BountyService(Ledger ledger, Database database, BountyBook book) {
        this.ledger = ledger;
        this.database = database;
        this.book = book;
    }

    BountyBook book() {
        return this.book;
    }

    /** Loads every active contribution. Call once at startup, before any transaction. */
    void load() throws Exception {
        this.ids = IdSequence.forTable(this.database, "bounties");
        List<BountyBook.Contribution> rows = this.database.read(c -> {
            List<BountyBook.Contribution> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, target, sponsor, amount, created FROM bounties WHERE state = ? ORDER BY id")) {
                ps.setString(1, ACTIVE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new BountyBook.Contribution(rs.getLong(1), UUID.fromString(rs.getString(2)),
                            UUID.fromString(rs.getString(3)), rs.getLong(4), rs.getLong(5)));
                    }
                }
            }
            return list;
        }).get();
        this.ledger.locked(() -> {
            this.book.clear();
            rows.forEach(this.book::add);
            return null;
        });
    }

    /** Checks the inputs of a placement; null when it may go ahead. */
    static PlaceProblem check(UUID sponsor, UUID target, long amount, long minimum) {
        if (sponsor.equals(target)) {
            return PlaceProblem.YOURSELF;
        }
        if (amount < Math.max(1, minimum)) {
            return PlaceProblem.BELOW_MINIMUM;
        }
        return null;
    }

    /**
     * Moves {@code amount} from the sponsor to the escrow and adds it to the target's bounty, atomically. The
     * caller checked the inputs with {@link #check} and fired the placement event.
     */
    Placed place(UUID sponsor, UUID target, long amount, long now) {
        if (amount <= 0 || sponsor.equals(target)) {
            throw new IllegalArgumentException("Invalid bounty of " + amount + " by " + sponsor + " on " + target);
        }
        BountyBook.Contribution contribution = new BountyBook.Contribution(this.ids.next(), target, sponsor, amount, now);
        LedgerTx tx = LedgerTx.builder()
            .actor(sponsor)
            .note("bounty on " + target)
            .transfer(sponsor, ESCROW, Currency.MONEY, amount, KIND_PLACE, Long.toString(contribution.id()))
            .apply(() -> this.book.add(contribution), () -> this.book.remove(contribution))
            .write(c -> {
                insert(c, contribution);
                return null;
            })
            .build();
        TransactionResult result = this.ledger.execute(tx);
        return new Placed(result, contribution, this.book.total(target));
    }

    /** What the killer would get for this victim right now, or empty when nothing is claimable by them. */
    Optional<Plan> plan(UUID killer, UUID victim, int taxPercent) {
        if (killer.equals(victim)) {
            return Optional.empty();
        }
        BountyBook.Bounty bounty = this.book.get(victim);
        if (bounty == null) {
            return Optional.empty();
        }
        List<BountyBook.Contribution> claimable = bounty.claimableBy(killer);
        if (claimable.isEmpty()) {
            return Optional.empty();
        }
        long total = 0;
        for (BountyBook.Contribution contribution : claimable) {
            total = BountyMath.add(total, contribution.amount());
        }
        return Optional.of(new Plan(killer, victim, claimable, BountyMath.split(total, taxPercent)));
    }

    /**
     * Pays a plan out: escrow to killer, the tax destroyed, the contributions closed as claimed. Rejected with
     * reason {@code changed} when any of them was claimed, refunded or removed since the plan was made.
     */
    TransactionResult claim(Plan plan, long now) {
        List<BountyBook.Contribution> contributions = plan.contributions();
        String ref = plan.victim().toString();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(plan.killer())
            .note("bounty on " + plan.victim() + " claimed by " + plan.killer());
        if (plan.split().payout() > 0) {
            tx.transfer(ESCROW, plan.killer(), Currency.MONEY, plan.split().payout(), KIND_CLAIM, ref);
        }
        if (plan.split().tax() > 0) {
            tx.sink(ESCROW, Currency.MONEY, plan.split().tax(), KIND_TAX, ref);
        }
        tx.check(() -> this.book.allActive(contributions) ? null : "changed")
            .apply(() -> contributions.forEach(this.book::remove), () -> contributions.forEach(this.book::add))
            .write(c -> {
                close(c, contributions, CLAIMED, plan.killer(), now);
                return null;
            });
        return this.ledger.execute(tx.build());
    }

    /** Refunds every contribution placed {@code expireAfter} or longer ago to its sponsor. */
    List<Refund> expire(long now, Duration expireAfter) {
        List<Refund> refunds = new ArrayList<>();
        for (BountyBook.Contribution contribution : this.book.all()) {
            if (BountyMath.expired(contribution.created(), now, expireAfter)) {
                refunds.add(new Refund(contribution, refund(contribution, EXPIRED, "system", now)));
            }
        }
        return refunds;
    }

    /** Refunds every contribution on a target (staff removal). */
    List<Refund> removeAll(UUID target, String actor, long now) {
        BountyBook.Bounty bounty = this.book.get(target);
        if (bounty == null) {
            return List.of();
        }
        List<Refund> refunds = new ArrayList<>();
        for (BountyBook.Contribution contribution : bounty.contributions()) {
            refunds.add(new Refund(contribution, refund(contribution, REMOVED, actor, now)));
        }
        return refunds;
    }

    /** Escrow back to the sponsor; internal bookkeeping, so no cancellable economy event. */
    private TransactionResult refund(BountyBook.Contribution contribution, String state, String actor, long now) {
        LedgerTx tx = LedgerTx.builder()
            .actor(actor)
            .silent()
            .note("bounty on " + contribution.target() + " " + state.toLowerCase(java.util.Locale.ROOT))
            .transfer(ESCROW, contribution.sponsor(), Currency.MONEY, contribution.amount(), KIND_REFUND, Long.toString(contribution.id()))
            .check(() -> this.book.allActive(List.of(contribution)) ? null : "gone")
            .apply(() -> this.book.remove(contribution), () -> this.book.add(contribution))
            .write(c -> {
                close(c, List.of(contribution), state, null, now);
                return null;
            })
            .build();
        return this.ledger.execute(tx);
    }

    private static void insert(Connection c, BountyBook.Contribution contribution) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bounties (id, target, sponsor, amount, created, state, "
            + "claimed_by, closed_at) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            ps.setLong(1, contribution.id());
            ps.setString(2, contribution.target().toString());
            ps.setString(3, contribution.sponsor().toString());
            ps.setLong(4, contribution.amount());
            ps.setLong(5, contribution.created());
            ps.setString(6, ACTIVE);
            ps.executeUpdate();
        }
    }

    /** Closes active rows; a row that is no longer active fails the whole transaction (it is then reverted). */
    private static void close(Connection c, List<BountyBook.Contribution> contributions, String state, UUID claimedBy, long now)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE bounties SET state = ?, claimed_by = ?, closed_at = ? WHERE id = ? AND state = ?")) {
            for (BountyBook.Contribution contribution : contributions) {
                ps.setString(1, state);
                if (claimedBy == null) {
                    ps.setNull(2, Types.VARCHAR);
                } else {
                    ps.setString(2, claimedBy.toString());
                }
                ps.setLong(3, now);
                ps.setLong(4, contribution.id());
                ps.setString(5, ACTIVE);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Bounty contribution " + contribution.id() + " is not active in storage");
                }
            }
        }
    }

    /** The invariant in memory: the escrow balance equals the active contributions. Null when it holds. */
    String checkEscrow() {
        return this.ledger.locked(() -> {
            long escrow = this.ledger.balance(ESCROW, Currency.MONEY);
            long active = this.book.totalActive();
            return escrow == active ? null : "the bounty escrow holds " + escrow + " but the active bounties add up to " + active;
        });
    }

    /** The same invariant in storage, after every queued write is committed. Null when it holds. */
    CompletableFuture<String> checkStoredEscrow() {
        return this.database.write(c -> null).thenCompose(ignored -> this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT "
                + "(SELECT COALESCE(SUM(amount), 0) FROM bounties WHERE state = ?), "
                + "(SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE uuid = ? AND currency = ?)")) {
                ps.setString(1, ACTIVE);
                ps.setString(2, ESCROW.toString());
                ps.setString(3, Currency.MONEY.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "the stored escrow could not be read";
                    }
                    long active = rs.getLong(1);
                    long escrow = rs.getLong(2);
                    return active == escrow ? null
                        : "stored active bounties add up to " + active + " but the stored escrow balance is " + escrow;
                }
            }
        }));
    }

    /** Number of rows per state (for the self-test and staff). */
    CompletableFuture<String> storedSummary() {
        return this.database.read(c -> {
            StringBuilder summary = new StringBuilder();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT state, COUNT(*) FROM bounties GROUP BY state ORDER BY state")) {
                while (rs.next()) {
                    if (!summary.isEmpty()) {
                        summary.append(", ");
                    }
                    summary.append(rs.getString(1).toLowerCase(java.util.Locale.ROOT)).append(' ').append(rs.getLong(2));
                }
            }
            return summary.isEmpty() ? "none" : summary.toString();
        });
    }
}
