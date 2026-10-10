package net.siftvanilla.siftcore.economy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.Flow;
import net.siftvanilla.siftcore.api.economy.Posting;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * An atomic economy transaction: balance postings plus optional domain checks, in-memory domain changes and SQL
 * that must commit together with the ledger rows. Built once, executed once by {@link Ledger#execute(LedgerTx)}.
 * <p>
 * Rules for domain code: {@code checks} only read state and return a failure reason or null; {@code applies}
 * mutate in-memory state and must not fail (one that throws is logged and the transaction is undone and refused as
 * {@code REJECTED apply_failed}); each apply has a matching revert used only if storing fails;
 * {@code writes} run on the database writer inside the same transaction as the ledger rows; {@code afterCommit}
 * callbacks run once that transaction is stored (never when it was reverted).
 */
public final class LedgerTx {

    private final UUID id;
    private final String actor;
    private final String note;
    private final List<Posting> postings;
    private final List<Supplier<String>> checks;
    private final List<Runnable> applies;
    private final List<Runnable> reverts;
    private final List<SqlWork<?>> writes;
    private final List<Runnable> afterCommit;
    private final boolean fireEvent;

    private LedgerTx(Builder builder) {
        this.id = UUID.randomUUID();
        this.actor = builder.actor;
        this.note = builder.note;
        this.postings = List.copyOf(builder.postings);
        this.checks = List.copyOf(builder.checks);
        this.applies = List.copyOf(builder.applies);
        this.reverts = List.copyOf(builder.reverts);
        this.writes = List.copyOf(builder.writes);
        this.afterCommit = List.copyOf(builder.afterCommit);
        this.fireEvent = builder.fireEvent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID id() {
        return this.id;
    }

    /** Who caused it: a player UUID, {@code console}, {@code system} or a plugin name. */
    public String actor() {
        return this.actor;
    }

    public String note() {
        return this.note;
    }

    public List<Posting> postings() {
        return this.postings;
    }

    List<Supplier<String>> checks() {
        return this.checks;
    }

    List<Runnable> applies() {
        return this.applies;
    }

    List<Runnable> reverts() {
        return this.reverts;
    }

    List<SqlWork<?>> writes() {
        return this.writes;
    }

    List<Runnable> afterCommit() {
        return this.afterCommit;
    }

    boolean fireEvent() {
        return this.fireEvent;
    }

    /** The main kind of this transaction (the first posting's), for events and logs. */
    public String kind() {
        return this.postings.isEmpty() ? "none" : this.postings.getFirst().kind();
    }

    /** Builder; validates that transfers net to zero per currency. */
    public static final class Builder {

        private String actor = "system";
        private String note;
        private final List<Posting> postings = new ArrayList<>();
        private final List<Supplier<String>> checks = new ArrayList<>();
        private final List<Runnable> applies = new ArrayList<>();
        private final List<Runnable> reverts = new ArrayList<>();
        private final List<SqlWork<?>> writes = new ArrayList<>();
        private final List<Runnable> afterCommit = new ArrayList<>();
        private boolean fireEvent = true;

        private Builder() {
        }

        public Builder actor(UUID player) {
            this.actor = player.toString();
            return this;
        }

        public Builder actor(String actor) {
            this.actor = Objects.requireNonNull(actor);
            return this;
        }

        public Builder note(String note) {
            this.note = note == null ? null : note.length() > 255 ? note.substring(0, 255) : note;
            return this;
        }

        /** Moves {@code amount} from one account to another. */
        public Builder transfer(UUID from, UUID to, Currency currency, long amount, String kind, String ref) {
            requirePositive(amount);
            if (from.equals(to)) {
                throw new IllegalArgumentException("Cannot transfer to the same account");
            }
            this.postings.add(new Posting(from, currency, -amount, kind, Flow.TRANSFER, to, ref));
            this.postings.add(new Posting(to, currency, amount, kind, Flow.TRANSFER, from, ref));
            return this;
        }

        /** Creates {@code amount} in an account. */
        public Builder source(UUID account, Currency currency, long amount, String kind, String ref) {
            requirePositive(amount);
            this.postings.add(new Posting(account, currency, amount, kind, Flow.SOURCE, null, ref));
            return this;
        }

        /** Destroys {@code amount} from an account. */
        public Builder sink(UUID account, Currency currency, long amount, String kind, String ref) {
            requirePositive(amount);
            this.postings.add(new Posting(account, currency, -amount, kind, Flow.SINK, null, ref));
            return this;
        }

        /** A check evaluated under the economy lock; return a short failure reason or null to pass. */
        public Builder check(Supplier<String> check) {
            this.checks.add(Objects.requireNonNull(check));
            return this;
        }

        /** An in-memory change applied under the economy lock after all checks pass, with its undo. */
        public Builder apply(Runnable apply, Runnable revert) {
            this.applies.add(Objects.requireNonNull(apply));
            this.reverts.add(Objects.requireNonNull(revert));
            return this;
        }

        /** SQL that commits atomically with the ledger rows. */
        public Builder write(SqlWork<?> work) {
            this.writes.add(Objects.requireNonNull(work));
            return this;
        }

        /**
         * Runs once the transaction is stored, on the database callback thread (not under the economy lock), and never
         * when it failed or was reverted. For things that must only happen for a change that lasts, such as telling
         * players about it. Must be quick and must not block.
         */
        public Builder afterCommit(Runnable callback) {
            this.afterCommit.add(Objects.requireNonNull(callback));
            return this;
        }

        /** Skips the public cancellable event (for internal bookkeeping such as refunds that must not be blocked). */
        public Builder silent() {
            this.fireEvent = false;
            return this;
        }

        public LedgerTx build() {
            Map<Currency, Long> transferSums = new EnumMap<>(Currency.class);
            for (Posting posting : this.postings) {
                if (posting.flow() == Flow.TRANSFER) {
                    transferSums.merge(posting.currency(), posting.delta(), Math::addExact);
                }
            }
            transferSums.forEach((currency, sum) -> {
                if (sum != 0) {
                    throw new IllegalStateException("Transfers in " + currency + " do not net to zero: " + sum);
                }
            });
            if (this.postings.isEmpty() && this.writes.isEmpty() && this.applies.isEmpty()) {
                throw new IllegalStateException("Empty transaction");
            }
            return new LedgerTx(this);
        }

        private static void requirePositive(long amount) {
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be positive, got " + amount);
            }
        }
    }
}
