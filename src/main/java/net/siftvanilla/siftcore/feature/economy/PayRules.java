package net.siftvanilla.siftcore.feature.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;

/** The pure rules behind the payment settings (unit tested). */
final class PayRules {

    private PayRules() {
    }

    /**
     * Whether a payment of {@code amount} asks for confirmation: the payer's {@code pay-confirm-above} choice, never
     * looser than the server's {@code pay.confirm-above} ({@code serverAbove}, 0 never asks). "Server default" follows
     * the server; "always" and a preset ask on top of what the server already asks for.
     */
    static boolean asks(ConfirmAbove choice, long amount, long serverAbove) {
        boolean server = serverAbove > 0 && amount >= serverAbove;
        return server || choice.asks(amount, server);
    }

    /**
     * Whether the receiver accepts a payment: never from a player they ignore (unless the payer can't be ignored,
     * like staff), otherwise from the audience of their {@code pay-accept-from} choice.
     *
     * @param friends     the two are friends
     * @param sameTeam    the two are in the same team
     * @param ignored     the receiver ignores the payer
     * @param unignorable the payer can't be ignored
     */
    static boolean accepts(Audience audience, boolean friends, boolean sameTeam, boolean ignored, boolean unignorable) {
        if (ignored && !unignorable) {
            return false;
        }
        return Relations.allows(audience, friends, sameTeam);
    }

    /** Whether the receiver is told about a payment: their alert style is not off and it reaches their minimum. */
    static boolean alerts(AlertStyle style, long amount, long minimum) {
        return style != AlertStyle.OFF && amount >= minimum;
    }

    /** What one player paid while the receiver was away. */
    record Payer(UUID payer, long total, int payments) {
    }

    /**
     * The summary of payments received while away.
     *
     * @param total    everything received
     * @param payers   the payers shown, the largest total first
     * @param more     how many further payers are left out
     */
    record Away(long total, List<Payer> payers, int more) {

        boolean empty() {
            return this.payers.isEmpty();
        }
    }

    /**
     * Sums up what each payer sent (rows with no amount are dropped), the largest first, at most {@code shown}
     * payers listed; a total that would overflow saturates.
     */
    static Away away(List<Payer> rows, int shown) {
        List<Payer> sorted = new ArrayList<>();
        long total = 0;
        for (Payer row : rows) {
            if (row.total() <= 0 || row.payer() == null) {
                continue;
            }
            sorted.add(row);
            total = total > Long.MAX_VALUE - row.total() ? Long.MAX_VALUE : total + row.total();
        }
        sorted.sort(Comparator.comparingLong(Payer::total).reversed().thenComparing(p -> p.payer().toString()));
        int keep = Math.min(Math.max(0, shown), sorted.size());
        return new Away(total, List.copyOf(sorted.subList(0, keep)), sorted.size() - keep);
    }
}
