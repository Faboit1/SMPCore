package net.siftvanilla.siftcore.feature.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Reads who paid a player while they were offline, for the join summary ({@code pay-join-summary}): the incoming
 * {@code /pay} rows of the ledger after a moment, summed per payer. One indexed read (ledger by account and time).
 */
final class PaymentsAway {

    /** The ledger kind of {@code /pay} transfers. */
    static final String KIND = "pay";

    private final Database database;

    PaymentsAway(Database database) {
        this.database = database;
    }

    /** What each player paid {@code player} after {@code since} (epoch millis). Completes off-thread. */
    CompletableFuture<List<PayRules.Payer>> since(UUID player, long since) {
        return this.database.read(c -> {
            List<PayRules.Payer> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT counterparty, SUM(delta), COUNT(*) FROM ledger WHERE account = ? "
                + "AND ts > ? AND kind = ? AND currency = ? AND delta > 0 AND counterparty IS NOT NULL GROUP BY counterparty")) {
                ps.setString(1, player.toString());
                ps.setLong(2, since);
                ps.setString(3, KIND);
                ps.setString(4, Currency.MONEY.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID payer = uuid(rs.getString(1));
                        if (payer != null) {
                            rows.add(new PayRules.Payer(payer, rs.getLong(2), rs.getInt(3)));
                        }
                    }
                }
            }
            return rows;
        });
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
