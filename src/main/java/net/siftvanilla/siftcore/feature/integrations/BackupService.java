package net.siftvanilla.siftcore.feature.integrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Copies the SQLite database to {@code plugins/SiftCore/backups/siftcore-<time>.db} with {@code VACUUM INTO}, on
 * demand ({@code /sift backup}) and on a timer.
 * <p>
 * A backup first queues an empty write and waits for it: the database applies writes strictly in order, so every
 * change made before the command (every committed trade) is in the file. The copy itself runs on its own connection
 * inside one read transaction, so it is a consistent snapshot even while new writes keep committing, and nothing
 * waits for it. Each new file is checked with {@code PRAGMA quick_check} before older backups are deleted.
 * MySQL and MariaDB databases are backed up on the database server (mysqldump), not here.
 */
final class BackupService {

    /** A finished backup. */
    record Result(Path file, long bytes, Duration took, List<String> deleted) {
    }

    /** A backup file on disk. */
    record BackupFile(String name, long bytes, Instant modified) {
    }

    private final Database database;
    private final Path sqlite;
    private final Path folder;
    private final Supplier<IntegrationsSettings.Backups> settings;
    private final Scheduler scheduler;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Task timer = Task.NONE;
    private volatile Instant nextAutomatic;

    /**
     * @param sqlite the SQLite database file, or null when the database is MySQL/MariaDB
     * @param folder where backups go
     */
    BackupService(Database database, Path sqlite, Path folder, Supplier<IntegrationsSettings.Backups> settings, Scheduler scheduler,
                  Logger logger) {
        this.database = database;
        this.sqlite = sqlite;
        this.folder = folder;
        this.settings = settings;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    boolean sqlite() {
        return this.sqlite != null;
    }

    Path folder() {
        return this.folder;
    }

    boolean running() {
        return this.running.get();
    }

    /** When the next automatic backup runs, or null when they are off. */
    Instant nextAutomatic() {
        return this.nextAutomatic;
    }

    /** Makes a backup. Fails with {@link IllegalStateException} when one is already running or the database is not SQLite. */
    CompletableFuture<Result> backup() {
        if (this.sqlite == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("the database is not SQLite"));
        }
        if (!this.running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("a backup is already running"));
        }
        long start = System.nanoTime();
        return this.database.write(connection -> null)
            .thenApplyAsync(ignored -> copy(start), this.scheduler.asyncExecutor())
            .whenComplete((result, error) -> this.running.set(false));
    }

    private Result copy(long start) {
        try {
            Files.createDirectories(this.folder);
            Set<String> taken = new HashSet<>();
            for (BackupFile file : list()) {
                taken.add(file.name());
            }
            Path target = this.folder.resolve(FileNames.backup(Instant.now(), taken)).toAbsolutePath();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + this.sqlite.toAbsolutePath());
                 Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout=10000");
                st.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
            }
            verify(target);
            long bytes = Files.size(target);
            List<String> deleted = prune();
            return new Result(target, bytes, Duration.ofNanos(System.nanoTime() - start), deleted);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    private static void verify(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            String answer = rs.next() ? rs.getString(1) : "no answer";
            if (!"ok".equalsIgnoreCase(answer)) {
                throw new SQLException("the new backup failed its integrity check: " + answer);
            }
        }
    }

    /** Deletes the oldest backups beyond {@code keep}; returns their names. */
    private List<String> prune() throws IOException {
        List<String> names = new ArrayList<>();
        for (BackupFile file : list()) {
            names.add(file.name());
        }
        List<String> doomed = FileNames.prune(names, this.settings.get().keep());
        for (String name : doomed) {
            Files.deleteIfExists(this.folder.resolve(name));
        }
        return doomed;
    }

    /** The backups on disk, newest first. */
    List<BackupFile> list() throws IOException {
        if (!Files.isDirectory(this.folder)) {
            return List.of();
        }
        List<BackupFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(this.folder)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (FileNames.isBackup(name) && Files.isRegularFile(path)) {
                    files.add(new BackupFile(name, Files.size(path), Files.getLastModifiedTime(path).toInstant()));
                }
            }
        }
        files.sort(Comparator.comparing(BackupFile::name).reversed());
        return files;
    }

    /** (Re)starts the automatic backups from the current settings; the first one follows the newest file on disk. */
    void schedule() {
        this.timer.cancel();
        this.timer = Task.NONE;
        this.nextAutomatic = null;
        IntegrationsSettings.Backups backups = this.settings.get();
        if (this.sqlite == null || !backups.automatic()) {
            return;
        }
        Instant newest = null;
        try {
            List<BackupFile> files = list();
            newest = files.isEmpty() ? null : files.stream().map(BackupFile::modified).max(Comparator.naturalOrder()).orElse(null);
        } catch (IOException e) {
            this.logger.log(Level.WARNING, "Could not read the backups folder " + this.folder, e);
        }
        Instant now = Instant.now();
        Duration minimum = Duration.ofMinutes(5);
        Duration delay = newest == null ? minimum : Duration.between(now, newest.plus(backups.interval()));
        if (delay.compareTo(minimum) < 0) {
            delay = minimum;
        }
        this.nextAutomatic = now.plus(delay);
        Duration interval = backups.interval();
        this.timer = this.scheduler.asyncTimer(() -> {
            this.nextAutomatic = Instant.now().plus(interval);
            automatic();
        }, delay, interval);
    }

    void stop() {
        this.timer.cancel();
        this.timer = Task.NONE;
        this.nextAutomatic = null;
    }

    private void automatic() {
        backup().whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                this.logger.log(Level.SEVERE, "The automatic database backup failed: " + cause.getMessage(), cause);
                return;
            }
            this.logger.info("Automatic database backup saved as " + result.file().getFileName() + " (" + FileNames.size(result.bytes())
                + ", " + result.took().toMillis() + " ms" + (result.deleted().isEmpty() ? "" : ", deleted " + result.deleted().size()
                + " older") + ").");
        });
    }
}
