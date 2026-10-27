package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
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
 * Command rewards are stored with the opening and deleted only after they ran, so a stop between the opening's commit
 * and the console task (which used to drop them silently) runs them at the next start instead.
 */
class RewardCommandsTest {

    private static final UUID WINNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private final Logger logger = Logger.getLogger("crate-commands-test");
    private final List<String> dispatched = new CopyOnWriteArrayList<>();
    private final List<Runnable> globalQueue = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("crates.db"), 2), this.logger);
        ClassLoader loader = RewardCommandsTest.class.getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, Long.MAX_VALUE / 4);
        this.ledger.load();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    /** Commands whose global thread is a queue the test runs by hand. */
    private RewardCommands commands() {
        return commands(this.globalQueue::add);
    }

    private RewardCommands commands(Consumer<Runnable> global) {
        return new RewardCommands(this.database, global, command -> {
            if (command.startsWith("missing")) {
                return false;
            }
            if (command.startsWith("broken")) {
                throw new IllegalStateException("parse error");
            }
            this.dispatched.add(command);
            return true;
        }, this.logger);
    }

    private TransactionResult open(RewardCommands commands, String ref, List<String> lines) throws Exception {
        LedgerTx.Builder tx = LedgerTx.builder().actor(WINNER).silent().note("open");
        commands.add(tx, ref, WINNER, lines, System.currentTimeMillis());
        TransactionResult result = this.ledger.executeDomain(tx.build());
        result.committed().get(10, TimeUnit.SECONDS);
        return result;
    }

    private void runGlobal() {
        List<Runnable> tasks = new ArrayList<>(this.globalQueue);
        this.globalQueue.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void commandsRunOnceAfterTheOpeningIsStoredAndAreThenForgotten() throws Exception {
        RewardCommands commands = commands();
        List<String> lines = CrateOpener.resolve(List.of("lp user %player% parent add baron", "say %uuid% won"), "Steve", WINNER);
        open(commands, "crate:a", lines);
        assertEquals(1, commands.leftovers().size(), "stored before they run");
        commands.run("crate:a", lines);
        assertTrue(this.dispatched.isEmpty(), "they run on the global thread");
        runGlobal();
        assertEquals(List.of("lp user Steve parent add baron", "say " + WINNER + " won"), this.dispatched);
        this.database.flush();
        assertTrue(commands.leftovers().isEmpty(), "deleted after they ran");
    }

    @Test
    void aStopBeforeTheConsoleTaskRanKeepsThemForTheNextStart() throws Exception {
        RewardCommands commands = commands();
        open(commands, "crate:b", List.of("give Steve diamond 1"));
        // The region scheduler halted: the global task was accepted but never runs.
        commands.run("crate:b", List.of("give Steve diamond 1"));
        this.globalQueue.clear();
        assertTrue(this.dispatched.isEmpty());

        RewardCommands nextStart = commands();
        List<RewardCommands.Waiting> leftovers = nextStart.leftovers();
        assertEquals(1, leftovers.size());
        assertEquals("crate:b", leftovers.getFirst().ref());
        assertEquals(WINNER, leftovers.getFirst().player());
        nextStart.resume(leftovers);
        runGlobal();
        assertEquals(List.of("give Steve diamond 1"), this.dispatched);
        this.database.flush();
        assertTrue(nextStart.leftovers().isEmpty());
    }

    @Test
    void aSchedulerThatRefusesWorkWhileStoppingKeepsThemToo() throws Exception {
        RewardCommands stopping = commands(task -> {
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        });
        open(stopping, "crate:c", List.of("eco give Steve 1000"));
        stopping.run("crate:c", List.of("eco give Steve 1000"));
        assertTrue(this.dispatched.isEmpty());
        assertEquals(List.of("eco give Steve 1000"), commands().leftovers().getFirst().commands());
    }

    @Test
    void anOpeningThatIsNotStoredLeavesNoCommands() throws Exception {
        RewardCommands commands = commands();
        LedgerTx.Builder tx = LedgerTx.builder().actor(WINNER).silent().note("open");
        commands.add(tx, "crate:d", WINNER, List.of("give Steve diamond 64"), 1L);
        tx.write(c -> {
            throw new SQLException("disk on fire");
        });
        TransactionResult result = this.ledger.executeDomain(tx.build());
        assertThrows(ExecutionException.class, () -> result.committed().get(10, TimeUnit.SECONDS));
        assertTrue(commands.leftovers().isEmpty(), "rolled back with the key and the log row");
    }

    @Test
    void failingCommandsAreLoggedAndNotRetriedForever() throws Exception {
        RewardCommands commands = commands();
        List<String> lines = List.of("missing command", "broken command", "say ok");
        open(commands, "crate:e", lines);
        commands.run("crate:e", lines);
        runGlobal();
        assertEquals(List.of("say ok"), this.dispatched, "one failing command does not stop the others");
        this.database.flush();
        assertTrue(commands.leftovers().isEmpty());
    }

    @Test
    void leftoversRunInTheOrderTheyWereWon() throws Exception {
        RewardCommands commands = commands();
        LedgerTx.Builder first = LedgerTx.builder().actor(WINNER).silent();
        commands.add(first, "crate:z", WINNER, List.of("one", "two"), 100L);
        this.ledger.executeDomain(first.build()).committed().get(10, TimeUnit.SECONDS);
        LedgerTx.Builder second = LedgerTx.builder().actor(WINNER).silent();
        commands.add(second, "crate:a", WINNER, List.of("three"), 200L);
        this.ledger.executeDomain(second.build()).committed().get(10, TimeUnit.SECONDS);
        commands.resume(commands.leftovers());
        runGlobal();
        assertEquals(List.of("one", "two", "three"), this.dispatched);
    }
}
