package net.siftvanilla.siftcore.feature.spawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps the spawn point in {@code data/spawn.yml}. It is per server (not in the shared database) because the
 * spawn belongs to this server's worlds. Writes go to a temporary file that replaces the real one, so a crash
 * mid-write never leaves a broken file.
 */
final class SpawnStore {

    private final Path file;
    private final Executor io;
    private final Logger logger;
    private final Object lock = new Object();
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    SpawnStore(Path file, Executor io, Logger logger) {
        this.file = file;
        this.io = io;
        this.logger = logger;
    }

    /** Reads the saved spawn point; empty when none was saved. Throws when the file exists but is broken. */
    Optional<SpawnPoint> load() throws IOException {
        if (!Files.isRegularFile(this.file)) {
            return Optional.empty();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(this.file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(this.file + " is not valid YAML: " + e.getMessage(), e);
        }
        String world = yaml.getString("world");
        if (world == null || !yaml.isDouble("x") && !yaml.isInt("x")) {
            throw new IOException(this.file + " has no world or coordinates");
        }
        try {
            return Optional.of(new SpawnPoint(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch")));
        } catch (IllegalArgumentException e) {
            throw new IOException(this.file + " is invalid: " + e.getMessage(), e);
        }
    }

    /** Saves off the calling thread; writes happen in order. */
    CompletableFuture<Void> save(SpawnPoint point) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("The server spawn, set in game with /setspawn. Change it with /setspawn rather than here."));
        yaml.set("world", point.world());
        yaml.set("x", point.x());
        yaml.set("y", point.y());
        yaml.set("z", point.z());
        yaml.set("yaw", (double) point.yaw());
        yaml.set("pitch", (double) point.pitch());
        String text = yaml.saveToString();
        synchronized (this.lock) {
            this.lastWrite = this.lastWrite.handle((ignored, error) -> null)
                .thenRunAsync(() -> write(text), this.io)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.logger.log(Level.SEVERE, "Could not save the spawn point to " + this.file, error);
                    }
                });
            return this.lastWrite;
        }
    }

    private void write(String text) {
        try {
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Waits for pending writes (shutdown). */
    void flush(long timeoutSeconds) {
        CompletableFuture<Void> pending;
        synchronized (this.lock) {
            pending = this.lastWrite;
        }
        try {
            pending.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // Already logged when the write failed.
        } catch (java.util.concurrent.TimeoutException e) {
            this.logger.severe("Saving the spawn point to " + this.file + " did not finish within " + timeoutSeconds + "s");
        }
    }
}
