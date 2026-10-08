package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.Flow;
import net.siftvanilla.siftcore.api.economy.Posting;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import org.junit.jupiter.api.Test;

class EarningsTest {

    private static final Set<String> EARN = Set.copyOf(StatsSettings.DEFAULT_EARN_KINDS);
    private static final Set<String> TAX = Set.copyOf(StatsSettings.DEFAULT_TAX_KINDS);
    private static final UUID SELLER = new UUID(1, 1);
    private static final UUID BUYER = new UUID(1, 2);
    private static final UUID TEAM_BANK = new UUID(9, 9);
    private static final Predicate<UUID> PLAYERS = uuid -> !uuid.equals(TEAM_BANK);

    private static Posting posting(UUID account, long delta, String kind, Flow flow, UUID counterparty) {
        return new Posting(account, Currency.MONEY, delta, kind, flow, counterparty, null);
    }

    @Test
    void sellingToTheServerCounts() {
        Map<UUID, Long> earned = Earnings.of(List.of(posting(SELLER, 1500, "sell", Flow.SOURCE, null)), EARN, TAX, PLAYERS);
        assertEquals(Map.of(SELLER, 1500L), earned);
    }

    @Test
    void auctionSaleCountsForTheSellerNetOfTax() {
        List<Posting> postings = List.of(
            posting(BUYER, -10_000, "ah_sale", Flow.TRANSFER, SELLER),
            posting(SELLER, 10_000, "ah_sale", Flow.TRANSFER, BUYER),
            posting(SELLER, -500, "ah_tax", Flow.SINK, null));
        assertEquals(Map.of(SELLER, 9_500L), Earnings.of(postings, EARN, TAX, PLAYERS));
    }

    @Test
    void taxPaidByTheBuyerDoesNotTouchTheSeller() {
        List<Posting> postings = List.of(
            posting(BUYER, -9_500, "ah_sale", Flow.TRANSFER, SELLER),
            posting(SELLER, 9_500, "ah_sale", Flow.TRANSFER, BUYER),
            posting(BUYER, -500, "ah_tax", Flow.SINK, null));
        assertEquals(Map.of(SELLER, 9_500L), Earnings.of(postings, EARN, TAX, PLAYERS));
    }

    @Test
    void orderFillFromEscrowCountsNetOfTax() {
        List<Posting> postings = List.of(
            posting(SystemAccounts.ORDERS_ESCROW, -2_000, "order_fill", Flow.TRANSFER, SELLER),
            posting(SELLER, 2_000, "order_fill", Flow.TRANSFER, SystemAccounts.ORDERS_ESCROW),
            posting(SELLER, -100, "order_tax", Flow.SINK, null));
        assertEquals(Map.of(SELLER, 1_900L), Earnings.of(postings, EARN, TAX, PLAYERS));
    }

    @Test
    void feesWithoutEarningsAndPaymentsDoNotCount() {
        assertTrue(Earnings.of(List.of(posting(SELLER, -100, "ah_tax", Flow.SINK, null)), EARN, TAX, PLAYERS).isEmpty());
        List<Posting> pay = List.of(posting(BUYER, -50, "pay", Flow.TRANSFER, SELLER), posting(SELLER, 50, "pay", Flow.TRANSFER, BUYER));
        assertTrue(Earnings.of(pay, EARN, TAX, PLAYERS).isEmpty());
        assertTrue(Earnings.of(List.of(posting(SELLER, 900, "admin_give", Flow.SOURCE, null)), EARN, TAX, PLAYERS).isEmpty());
    }

    @Test
    void taxLargerThanTheSaleNeverGoesNegative() {
        List<Posting> postings = List.of(posting(SELLER, 100, "ah_sale", Flow.SOURCE, null), posting(SELLER, -300, "ah_tax", Flow.SINK, null));
        assertTrue(Earnings.of(postings, EARN, TAX, PLAYERS).isEmpty());
    }

    @Test
    void shardsSystemAccountsAndNonPlayersAreIgnored() {
        Posting shards = new Posting(SELLER, Currency.SHARDS, 40, "crate_reward", Flow.SOURCE, null, null);
        assertTrue(Earnings.of(List.of(shards), EARN, TAX, PLAYERS).isEmpty());
        assertTrue(Earnings.of(List.of(posting(SystemAccounts.BOUNTY_ESCROW, 400, "bounty_claim", Flow.SOURCE, null)), EARN, TAX, PLAYERS).isEmpty());
        assertTrue(Earnings.of(List.of(posting(TEAM_BANK, 400, "sell", Flow.SOURCE, null)), EARN, TAX, PLAYERS).isEmpty());
    }

    @Test
    void severalEarningsInOneTransactionAddUp() {
        List<Posting> postings = List.of(
            posting(SELLER, 300, "spawner_sell", Flow.SOURCE, null),
            posting(SELLER, 200, "crate_reward", Flow.SOURCE, null),
            posting(BUYER, 50, "bounty_claim", Flow.SOURCE, null));
        assertEquals(Map.of(SELLER, 500L, BUYER, 50L), Earnings.of(postings, EARN, TAX, PLAYERS));
    }
}
