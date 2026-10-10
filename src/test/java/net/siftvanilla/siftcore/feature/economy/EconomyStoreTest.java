package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Against a real SQLite ledger: the summary of payments received while away reads only incoming {@code /pay} money
 * after the given moment, summed per payer; the money leaderboard leaves hidden accounts out of the list and the places.
 */
class EconomyStoreTest {

    @TempDir
    Path folder;

    private JdbcDatabase database;
    private Ledger ledger;

    @BeforeEach
    void open() throws Exception {
        Logger logger = Logger.getLogger("economy-test");
        logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.folder.resolve("economy.db"), 2), logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, logger, 1_000_000_000_000L);
        this.ledger.load();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private void run(LedgerTx tx) throws Exception {
        var result = this.ledger.execute(tx);
        assertTrue(result.success(), result.status().name());
        result.committed().get(10, TimeUnit.SECONDS);
    }

    private void mint(UUID account, long amount) throws Exception {
        run(LedgerTx.builder().source(account, Currency.MONEY, amount, "test_mint", null).build());
    }

    @Test
    void paymentsWhileAwayAreSummedPerPayer() throws Exception {
        UUID receiver = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        mint(alex, 10_000);
        mint(sam, 10_000);
        run(LedgerTx.builder().transfer(alex, receiver, Currency.MONEY, 100, PaymentsAway.KIND, null).build());
        long away = System.currentTimeMillis();
        Thread.sleep(5);
        run(LedgerTx.builder().transfer(alex, receiver, Currency.MONEY, 250, PaymentsAway.KIND, null).build());
        run(LedgerTx.builder().transfer(alex, receiver, Currency.MONEY, 50, PaymentsAway.KIND, null).build());
        run(LedgerTx.builder().transfer(sam, receiver, Currency.MONEY, 1_000, PaymentsAway.KIND, null).build());
        // Not payments: another kind of transfer, money the receiver paid out, and a reward.
        run(LedgerTx.builder().transfer(sam, receiver, Currency.MONEY, 7, "trade", null).build());
        run(LedgerTx.builder().transfer(receiver, sam, Currency.MONEY, 5, PaymentsAway.KIND, null).build());
        mint(receiver, 99);

        PaymentsAway store = new PaymentsAway(this.database);
        List<PayRules.Payer> rows = store.since(receiver, away).get(10, TimeUnit.SECONDS);
        PayRules.Away summary = PayRules.away(rows, 5);
        assertEquals(1_300, summary.total(), "the payment before the player left is not counted again: " + rows);
        assertEquals(List.of(new PayRules.Payer(sam, 1_000, 1), new PayRules.Payer(alex, 300, 2)), summary.payers());
        assertTrue(store.since(receiver, System.currentTimeMillis() + 60_000).get(10, TimeUnit.SECONDS).isEmpty(),
            "nothing after now");
        assertEquals(List.of(new PayRules.Payer(receiver, 5, 1)), store.since(sam, away).get(10, TimeUnit.SECONDS));
    }

    @Test
    void hiddenAccountsLeaveTheMoneyLeaderboard() throws Exception {
        UUID rich = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID poor = UUID.randomUUID();
        mint(rich, 9_000);
        mint(middle, 5_000);
        mint(poor, 1_000);
        PlayerDirectory directory = new PlayerDirectory(this.database, new byte[16]);
        directory.load();
        BalanceTop top = new BalanceTop(this.ledger, directory);

        top.refresh(10);
        assertEquals(List.of(rich, middle, poor), top.top(Currency.MONEY, 10).stream().map(EconomyApi.TopEntry::account).toList());
        assertEquals(2, top.rankOf(Currency.MONEY, middle, 5_000));

        Set<UUID> hidden = Set.of(rich);
        top.refresh(10, hidden::contains);
        List<EconomyApi.TopEntry> list = top.top(Currency.MONEY, 10);
        assertEquals(List.of(middle, poor), list.stream().map(EconomyApi.TopEntry::account).toList(), "the hidden account is not listed");
        assertEquals(1, list.getFirst().rank(), "and takes no place from anyone");
        assertEquals(1, top.rankOf(Currency.MONEY, middle, 5_000));
        assertEquals(0, top.rankOf(Currency.MONEY, rich, 9_000), "and has no place itself");
        assertTrue(top.hidden(rich));
        assertFalse(top.hidden(poor));
        assertEquals(2, top.ranked(Currency.MONEY));
    }
}
