package net.siftvanilla.siftcore.api.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * One balance change inside a transaction.
 *
 * @param account      the account whose balance changes
 * @param currency     the currency
 * @param delta        positive to credit, negative to debit; never zero
 * @param kind         short machine id of the reason, e.g. {@code pay}, {@code sell}, {@code ah_tax}
 * @param flow         how this posting affects total supply
 * @param counterparty the other account of a transfer, or null
 * @param ref          an optional external reference such as a listing id
 */
public record Posting(UUID account, Currency currency, long delta, String kind, Flow flow, UUID counterparty, String ref) {

    public Posting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(flow, "flow");
        if (delta == 0) {
            throw new IllegalArgumentException("A posting cannot be zero");
        }
        if (kind.isEmpty() || kind.length() > 32) {
            throw new IllegalArgumentException("kind must be 1-32 characters");
        }
        if (ref != null && ref.length() > 64) {
            throw new IllegalArgumentException("ref must be at most 64 characters");
        }
    }
}
