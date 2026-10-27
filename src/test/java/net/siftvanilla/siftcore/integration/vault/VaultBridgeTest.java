package net.siftvanilla.siftcore.integration.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.milkbowl.vault.economy.EconomyResponse;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultBridgeTest {

    private static final UUID ALEX = new UUID(7, 1);
    private static final UUID STRANGER = new UUID(7, 9);

    /** An in-memory ledger: balances, a limit, and every move it made. */
    private static final class FakeMoves implements VaultBridge.Moves {
        final Map<UUID, Long> balances = new HashMap<>();
        final List<String> log = new ArrayList<>();
        long limit = Long.MAX_VALUE;

        @Override
        public long balance(UUID account) {
            return this.balances.getOrDefault(account, 0L);
        }

        @Override
        public TransactionResult credit(UUID account, long amount, String kind, String actor) {
            if (balance(account) > this.limit - amount) {
                return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.BALANCE_LIMIT, "limit");
            }
            this.balances.merge(account, amount, Long::sum);
            this.log.add("+" + amount + " " + kind + " by " + actor);
            return new TransactionResult(UUID.randomUUID(), TransactionStatus.SUCCESS, null, CompletableFuture.completedFuture(null));
        }

        @Override
        public TransactionResult debit(UUID account, long amount, String kind, String actor) {
            if (balance(account) < amount) {
                return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.INSUFFICIENT_FUNDS, "funds");
            }
            this.balances.merge(account, -amount, Long::sum);
            this.log.add("-" + amount + " " + kind + " by " + actor);
            return new TransactionResult(UUID.randomUUID(), TransactionStatus.SUCCESS, null, CompletableFuture.completedFuture(null));
        }
    }

    private FakeMoves moves;
    private VaultBridge bridge;

    @BeforeEach
    void setUp() {
        this.moves = new FakeMoves();
        this.bridge = new VaultBridge(this.moves, name -> "alex".equalsIgnoreCase(name) ? Optional.of(ALEX) : Optional.empty(),
            ALEX::equals);
    }

    @Test
    void amountsRoundInTheServersFavour() {
        assertEquals(14, VaultMoney.credit(new BigDecimal("14.99")), "paid out: rounded down");
        assertEquals(15, VaultMoney.debit(new BigDecimal("14.01")), "taken: rounded up");
        assertEquals(10, VaultMoney.debit(10.000000000000002), "float noise is not charged as a dollar");
        assertEquals(9, VaultMoney.credit(9.999999999999998 - 0.5), "credits still round down");
        assertEquals(0, VaultMoney.credit(new BigDecimal("0.4")));
        assertEquals(1, VaultMoney.debit(new BigDecimal("0.4")));
        assertEquals(VaultMoney.INVALID, VaultMoney.credit(-1));
        assertEquals(VaultMoney.INVALID, VaultMoney.debit(Double.NaN));
        assertEquals(VaultMoney.INVALID, VaultMoney.debit(Double.POSITIVE_INFINITY));
        assertEquals(VaultMoney.INVALID, VaultMoney.credit(new BigDecimal("1e30")));
        assertEquals(Long.MAX_VALUE, VaultMoney.credit(BigDecimal.valueOf(Long.MAX_VALUE)));
    }

    @Test
    void depositsAndWithdrawalsGoThroughTheLedgerNamedByPlugin() {
        VaultBridge.Outcome paid = this.bridge.deposit("AxAuctions", ALEX, new BigDecimal("142.5"));
        assertTrue(paid.success());
        assertEquals(142, paid.amount());
        assertEquals(142, paid.balance());
        VaultBridge.Outcome charged = this.bridge.withdraw("AxAuctions", ALEX, new BigDecimal("40.2"));
        assertTrue(charged.success());
        assertEquals(41, charged.amount());
        assertEquals(101, this.moves.balance(ALEX));
        assertEquals(List.of("+142 vault_axauctions by vault:AxAuctions", "-41 vault_axauctions by vault:AxAuctions"), this.moves.log);
    }

    @Test
    void failuresSayWhyAndMoveNothing() {
        this.moves.balances.put(ALEX, 50L);
        VaultBridge.Outcome tooMuch = this.bridge.withdraw("Shop", ALEX, new BigDecimal("50.01"));
        assertFalse(tooMuch.success());
        assertEquals(VaultBridge.INSUFFICIENT_FUNDS, tooMuch.error());
        assertEquals(50, tooMuch.balance());
        assertFalse(this.bridge.withdraw("Shop", ALEX, new BigDecimal("-5")).success());
        assertFalse(this.bridge.deposit("Shop", ALEX, new BigDecimal("-5")).success());
        assertEquals(VaultBridge.UNKNOWN_ACCOUNT, this.bridge.deposit("Shop", null, BigDecimal.TEN).error());
        this.moves.limit = 60;
        assertEquals(VaultBridge.BALANCE_LIMIT, this.bridge.deposit("Shop", ALEX, BigDecimal.valueOf(20)).error());
        assertEquals(50, this.moves.balance(ALEX));
        assertTrue(this.moves.log.isEmpty());
    }

    @Test
    void amountsThatRoundToNothingSucceedWithoutAMove() {
        VaultBridge.Outcome tiny = this.bridge.deposit("Jobs", ALEX, new BigDecimal("0.75"));
        assertTrue(tiny.success());
        assertEquals(0, tiny.amount());
        assertTrue(this.moves.log.isEmpty());
    }

    @Test
    void hasUsesTheSameRoundingAsAWithdrawal() {
        this.moves.balances.put(ALEX, 10L);
        assertTrue(this.bridge.has(ALEX, new BigDecimal("10")));
        assertFalse(this.bridge.has(ALEX, new BigDecimal("10.01")), "would be charged $11");
        assertFalse(this.bridge.has(ALEX, new BigDecimal("-1")));
    }

    @Test
    void accountsAreKnownPlayersOrHoldMoney() {
        assertTrue(this.bridge.hasAccount(ALEX));
        assertFalse(this.bridge.hasAccount(STRANGER));
        this.moves.balances.put(STRANGER, 5L);
        assertTrue(this.bridge.hasAccount(STRANGER));
        assertEquals(Optional.of(ALEX), this.bridge.account("ALEX"));
        assertEquals(Optional.empty(), this.bridge.account(""));
    }

    @Test
    void kindsAreShortAndClean() {
        assertEquals("vault_axauctions", VaultBridge.kind("AxAuctions"));
        assertEquals("vault", VaultBridge.kind(null));
        assertEquals("vault", VaultBridge.kind("§§"));
        assertEquals(32, VaultBridge.kind("AVeryLongPluginNameThatGoesOnAndOn").length());
    }

    @Test
    void theClassicInterfaceAnswersLikeVaultExpects() {
        LegacyVaultEconomy legacy = new LegacyVaultEconomy(this.bridge, new Callers(java.util.Set.of()), amount -> "$" + amount);
        EconomyResponse paid = legacy.depositPlayer("Alex", 25.9);
        assertTrue(paid.transactionSuccess());
        assertEquals(25, paid.amount);
        assertEquals(25, legacy.getBalance("alex"));
        assertTrue(legacy.has("alex", 25));
        assertFalse(legacy.has("alex", 25.5));
        EconomyResponse refused = legacy.withdrawPlayer("Alex", 30);
        assertEquals(EconomyResponse.ResponseType.FAILURE, refused.type);
        assertEquals(EconomyResponse.ResponseType.FAILURE, legacy.depositPlayer("Nobody", 5).type);
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, legacy.bankBalance("x").type);
        assertEquals("$1500", legacy.format(1500.7));
        assertEquals("-$3", legacy.format(-3));
        assertEquals(0, legacy.fractionalDigits());
        assertEquals(List.of("+25 vault by vault"), this.moves.log, "no plugin on the stack in a unit test");
    }

    @Test
    void theModernInterfaceHasOneCurrency() {
        ModernVaultEconomy modern = new ModernVaultEconomy(this.bridge, amount -> "$" + amount, () -> Map.of(ALEX, "Alex"));
        assertTrue(modern.deposit("Quests", ALEX, BigDecimal.valueOf(12)).transactionSuccess());
        assertEquals(BigDecimal.valueOf(12), modern.getBalance("Quests", ALEX));
        assertFalse(modern.deposit("Quests", ALEX, "world", "gems", BigDecimal.ONE).transactionSuccess());
        assertTrue(modern.hasCurrency("Money"));
        assertEquals(Optional.of("Alex"), modern.getAccountName(ALEX));
        assertFalse(modern.createSharedAccount("Quests", STRANGER, "bank", ALEX));
        assertEquals(List.of("+12 vault_quests by vault:Quests"), this.moves.log);
    }
}
