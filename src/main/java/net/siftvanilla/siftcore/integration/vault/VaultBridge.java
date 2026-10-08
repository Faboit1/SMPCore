package net.siftvanilla.siftcore.integration.vault;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;

/**
 * What both Vault interfaces do, over SiftCore's ledger: balances, checks, deposits and withdrawals in whole dollars.
 * Every change is one ledger transaction of kind {@code vault_<plugin>} with the calling plugin as the actor, so
 * {@code /eco history} and the audit show which plugin moved the money, and stats can count a plugin's payouts as
 * earnings. Thread-safe: Vault callers use any thread, and the ledger answers from memory without blocking.
 */
final class VaultBridge {

    /** The ledger operations the bridge needs. */
    interface Moves {
        long balance(UUID account);

        TransactionResult credit(UUID account, long amount, String kind, String actor);

        TransactionResult debit(UUID account, long amount, String kind, String actor);
    }

    /**
     * The outcome of a deposit or withdrawal.
     *
     * @param amount  the whole dollars actually moved (0 when the request rounded to nothing)
     * @param balance the account's balance afterwards
     * @param error   why it failed, or an empty string
     */
    record Outcome(boolean success, long amount, long balance, String error) {
    }

    static final String INVALID_AMOUNT = "The amount must be a positive number.";
    static final String INSUFFICIENT_FUNDS = "Insufficient funds.";
    static final String BALANCE_LIMIT = "That would go over the balance limit.";
    static final String REJECTED = "The transaction was refused.";
    static final String UNAVAILABLE = "The economy is not available right now.";
    static final String UNKNOWN_ACCOUNT = "That player has never joined.";

    private final Moves moves;
    private final Function<String, Optional<UUID>> names;
    private final Predicate<UUID> known;

    /**
     * @param names resolves a player name to a UUID (players who joined before)
     * @param known whether a UUID belongs to a player who joined before
     */
    VaultBridge(Moves moves, Function<String, Optional<UUID>> names, Predicate<UUID> known) {
        this.moves = moves;
        this.names = names;
        this.known = known;
    }

    /**
     * The ledger kind for money moved by a plugin: {@code vault_<plugin>} (lowercase letters, digits and _, at most
     * 32 characters, like every ledger kind).
     */
    static String kind(String plugin) {
        String clean = plugin == null ? "" : plugin.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        String kind = clean.isEmpty() ? "vault" : "vault_" + clean;
        return kind.length() > 32 ? kind.substring(0, 32) : kind;
    }

    static String actor(String plugin) {
        return plugin == null || plugin.isBlank() ? "vault" : "vault:" + plugin;
    }

    Optional<UUID> account(String name) {
        return name == null || name.isBlank() ? Optional.empty() : this.names.apply(name);
    }

    /**
     * Whether the account exists. Accounts are implicit in SiftCore: every player who joined has one, and any account
     * that holds money counts too.
     */
    boolean hasAccount(UUID account) {
        return account != null && (this.known.test(account) || this.moves.balance(account) != 0);
    }

    long balance(UUID account) {
        return account == null ? 0 : this.moves.balance(account);
    }

    /** Whether paying {@code amount} would succeed now (the same rounding a withdrawal uses). */
    boolean has(UUID account, BigDecimal amount) {
        long whole = VaultMoney.debit(amount);
        return account != null && whole != VaultMoney.INVALID && balance(account) >= whole;
    }

    Outcome deposit(String plugin, UUID account, BigDecimal amount) {
        if (account == null) {
            return new Outcome(false, 0, 0, UNKNOWN_ACCOUNT);
        }
        long whole = VaultMoney.credit(amount);
        if (whole == VaultMoney.INVALID) {
            return new Outcome(false, 0, balance(account), INVALID_AMOUNT);
        }
        if (whole == 0) {
            return new Outcome(true, 0, balance(account), "");
        }
        return outcome(this.moves.credit(account, whole, kind(plugin), actor(plugin)), account, whole);
    }

    Outcome withdraw(String plugin, UUID account, BigDecimal amount) {
        if (account == null) {
            return new Outcome(false, 0, 0, UNKNOWN_ACCOUNT);
        }
        long whole = VaultMoney.debit(amount);
        if (whole == VaultMoney.INVALID) {
            return new Outcome(false, 0, balance(account), INVALID_AMOUNT);
        }
        if (whole == 0) {
            return new Outcome(true, 0, balance(account), "");
        }
        return outcome(this.moves.debit(account, whole, kind(plugin), actor(plugin)), account, whole);
    }

    private Outcome outcome(TransactionResult result, UUID account, long whole) {
        long balance = balance(account);
        if (result.success()) {
            return new Outcome(true, whole, balance, "");
        }
        return new Outcome(false, 0, balance, error(result.status()));
    }

    static String error(TransactionStatus status) {
        return switch (status) {
            case SUCCESS -> "";
            case INSUFFICIENT_FUNDS -> INSUFFICIENT_FUNDS;
            case BALANCE_LIMIT -> BALANCE_LIMIT;
            case REJECTED, CANCELLED -> REJECTED;
            case UNAVAILABLE -> UNAVAILABLE;
        };
    }
}
