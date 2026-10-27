package net.siftvanilla.siftcore.feature.afk;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Point;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps the AFK zone set in game ({@code /afkzone pos1}, {@code pos2}, {@code set}, {@code arrival}) in
 * {@code data/afk-zone.yml}. Per server, like the spawn point, because the zone belongs to this server's worlds.
 * Writes go to a temporary file that replaces the real one, so a crash mid-write never leaves a broken file;
 * {@link #clear()} deletes it.
 */
final class ZoneStore {

    private final Path file;
    private final Executor io;
    private final Logger logger;
    private final Object lock = new Object();
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    ZoneStore(Path file, Executor io, Logger logger) {
        this.file = file;
        this.io = io;
        this.logger = logger;
    }

    /** The saved zone (world coordinates), empty when none. Throws when the file exists but is broken. */
    Optional<ZoneSpec> load() throws IOException {
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
        String from = yaml.getString("from");
        String to = yaml.getString("to");
        if (world == null || world.isBlank() || from == null || to == null) {
            throw new IOException(this.file + " needs world, from and to");
        }
        try {
            String arrival = yaml.getString("arrival");
            Point point = arrival == null || arrival.isBlank() ? null : Point.parse(arrival);
            ZoneSpec spec = new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, world.trim(), Corner.parse(from), Corner.parse(to), point);
            if (point != null && !spec.resolve(0, 0, 0).contains(spec.world(), point.x(), point.y(), point.z())) {
                spec = new ZoneSpec(spec.anchor(), spec.world(), spec.from(), spec.to(), null);
            }
            return Optional.of(spec);
        } catch (IllegalArgumentException e) {
            throw new IOException(this.file + " is invalid: " + e.getMessage(), e);
        }
    }

    /** Saves off the calling thread; writes happen in order. */
    CompletableFuture<Void> save(ZoneSpec spec) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("The AFK zone, set in game with /afkzone. It wins over features/afk.yml until /afkzone reset.",
            "Change it with /afkzone rather than here."));
        yaml.set("world", spec.world());
        yaml.set("from", spec.from().format());
        yaml.set("to", spec.to().format());
        if (spec.arrival() != null) {
            yaml.set("arrival", spec.arrival().format());
        }
        String text = yaml.saveToString();
        return enqueue(() -> write(text));
    }

    /** Deletes the saved zone, off the calling thread. */
    CompletableFuture<Void> clear() {
        return enqueue(() -> {
            try {
                Files.deleteIfExists(this.file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private CompletableFuture<Void> enqueue(Runnable work) {
        synchronized (this.lock) {
            this.lastWrite = this.lastWrite.handle((ignored, error) -> null)
                .thenRunAsync(work, this.io)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.logger.log(Level.SEVERE, "Could not save the AFK zone to " + this.file, error);
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
        } catch (ExecutionException e) {
            // Already logged when the write failed.
        } catch (TimeoutException e) {
            this.logger.severe("Saving the AFK zone to " + this.file + " did not finish within " + timeoutSeconds + "s");
        }
    }
}
