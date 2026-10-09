package net.siftvanilla.siftcore.feature.integrations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Writes CSV files of every balance and of the ledger to {@code plugins/SiftCore/exports}, for spreadsheets and
 * external accounting. Runs on the database's read pool; waits for every earlier write first, so the files include
 * everything up to the command. Files are written under a temporary name and renamed when complete.
 */
final class ExportService {

    /** A finished export. */
    record Result(Path balances, Path ledger, long balanceRows, long ledgerRows, Duration took) {
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final Database database;
    private final Path folder;
    private final Supplier<Map<UUID, String>> names;
    private final AtomicBoolean running = new AtomicBoolean();

    ExportService(Database database, Path folder, Supplier<Map<UUID, String>> names) {
        this.database = database;
        this.folder = folder;
        this.names = names;
    }

    Path folder() {
        return this.folder;
    }

    /**
     * Exports balances and the ledger rows of the last {@code days} days (0: the whole ledger). Fails with
     * {@link IllegalStateException} when an export is already running.
     */
    CompletableFuture<Result> export(int days) {
        if (!this.running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("an export is already running"));
        }
        long start = System.nanoTime();
        Instant now = Instant.now();
        long since = days <= 0 ? 0 : now.minus(Duration.ofDays(days)).toEpochMilli();
        Map<UUID, String> known = this.names.get();
        return this.database.write(connection -> null)
            .thenCompose(ignored -> this.database.read(connection -> {
                try {
                    Files.createDirectories(this.folder);
                    Path balances = this.folder.resolve(FileNames.export("balances", now));
                    Path ledger = this.folder.resolve(FileNames.export("ledger", now));
                    long balanceRows = writeBalances(connection, balances, known);
                    long ledgerRows = writeLedger(connection, ledger, known, since);
                    return new Result(balances, ledger, balanceRows, ledgerRows, Duration.ofNanos(System.nanoTime() - start));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }))
            .whenComplete((result, error) -> this.running.set(false));
    }

    private static long writeBalances(java.sql.Connection connection, Path file, Map<UUID, String> names) throws SQLException, IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".part");
        long rows = 0;
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
             PreparedStatement ps = connection.prepareStatement(
                 "SELECT uuid, currency, balance FROM accounts WHERE balance <> 0 ORDER BY currency, balance DESC")) {
            ps.setFetchSize(1000);
            Csv.line(out, "account", "name", "type", "currency", "balance");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String account = rs.getString(1);
                    Csv.line(out, account, name(account, names), type(account), rs.getString(2), rs.getLong(3));
                    rows++;
                }
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return rows;
    }

    private static long writeLedger(java.sql.Connection connection, Path file, Map<UUID, String> names, long since)
        throws SQLException, IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".part");
        long rows = 0;
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
             PreparedStatement ps = connection.prepareStatement("SELECT id, tx_id, ts, currency, account, delta, balance_after, kind, "
                 + "flow, counterparty, ref, actor, note FROM ledger WHERE ts >= ? ORDER BY id")) {
            ps.setLong(1, since);
            ps.setFetchSize(1000);
            Csv.line(out, "id", "transaction", "time_utc", "time_ms", "currency", "account", "name", "delta", "balance_after",
                "kind", "flow", "counterparty", "counterparty_name", "ref", "actor", "actor_name", "note");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong(3);
                    String account = rs.getString(5);
                    String counterparty = rs.getString(10);
                    String actor = rs.getString(12);
                    Csv.line(out, rs.getLong(1), rs.getString(2), ISO.format(Instant.ofEpochMilli(ts)), ts, rs.getString(4), account,
                        name(account, names), rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9), counterparty,
                        counterparty == null ? null : name(counterparty, names), rs.getString(11), actor,
                        actor == null ? null : name(actor, names), rs.getString(13));
                    rows++;
                }
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return rows;
    }

    /** The player's name, a system account's role, or empty. */
    static String name(String account, Map<UUID, String> names) {
        UUID uuid;
        try {
            uuid = UUID.fromString(account);
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (uuid.equals(SystemAccounts.ORDERS_ESCROW)) {
            return "orders escrow";
        }
        if (uuid.equals(SystemAccounts.BOUNTY_ESCROW)) {
            return "bounty escrow";
        }
        String name = names.get(uuid);
        return name == null ? "" : name;
    }

    private static String type(String account) {
        try {
            return SystemAccounts.isSystem(UUID.fromString(account)) ? "system" : "player";
        } catch (IllegalArgumentException e) {
            return "other";
        }
    }
}
