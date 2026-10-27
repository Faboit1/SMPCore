package net.siftvanilla.siftcore.feature.crates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Command rewards that must run exactly when their opening is stored, even if the server stops or crashes right after.
 * The resolved commands are stored in the opening's transaction (with the spent key and the log row); once that is
 * stored they run from the console on the global thread and their rows are deleted. Commands whose rows are still
 * there at the next start (the scheduler stopped before they ran, or refused them while the plugin stopped) run then,
 * each one logged. A crash after a command ran but before its row was deleted makes it run again at the next start:
 * the log line says so, so staff can take a doubled reward back.
 */
final class RewardCommands {

    /** The commands of one opening still waiting to run. */
    record Waiting(String ref, UUID player, List<String> commands) {
        Waiting {
            commands = List.copyOf(commands);
        }
    }

    private final Database database;
    private final Consumer<Runnable> global;
    private final Predicate<String> dispatch;
    private final Logger logger;

    /**
     * @param global   runs a task on the global thread (may throw when the plugin is stopping)
     * @param dispatch runs one console command; false when it was not found, throws when it failed
     */
    RewardCommands(Database database, Consumer<Runnable> global, Predicate<String> dispatch, Logger logger) {
        this.database = database;
        this.global = global;
        this.dispatch = dispatch;
        this.logger = logger;
    }

    /** Stores an opening's resolved commands with its transaction. */
    void add(LedgerTx.Builder tx, String ref, UUID player, List<String> commands, long now) {
        if (commands.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(commands);
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO crate_commands (ref, seq, uuid, command, created) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 0; i < copy.size(); i++) {
                    ps.setString(1, ref);
                    ps.setInt(2, i);
                    ps.setString(3, player.toString());
                    ps.setString(4, copy.get(i));
                    ps.setLong(5, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * Runs an opening's commands once it is stored: on the global thread, then deletes their rows. When the scheduler
     * refuses (the plugin is stopping), they stay stored and run at the next start.
     */
    void run(String ref, List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }
        try {
            this.global.accept(() -> dispatchAll(new Waiting(ref, null, commands), false));
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "The crate reward commands of " + ref + " could not run now (the server is stopping); "
                + "they run at the next start", e);
        }
    }

    /** Commands left from before the last stop, oldest opening first. Blocking; call at startup only. */
    List<Waiting> leftovers() throws Exception {
        return this.database.read(c -> {
            Map<String, UUID> players = new LinkedHashMap<>();
            Map<String, List<String>> commands = new LinkedHashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT ref, uuid, command FROM crate_commands ORDER BY created, ref, seq")) {
                while (rs.next()) {
                    String ref = rs.getString(1);
                    UUID player;
                    try {
                        player = UUID.fromString(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        player = null;
                    }
                    players.putIfAbsent(ref, player);
                    commands.computeIfAbsent(ref, k -> new ArrayList<>()).add(rs.getString(3));
                }
            }
            List<Waiting> list = new ArrayList<>(commands.size());
            commands.forEach((ref, lines) -> list.add(new Waiting(ref, players.get(ref), lines)));
            return list;
        }).get();
    }

    /** Runs commands left from before the last stop (on the global thread), each one logged. */
    void resume(List<Waiting> leftovers) {
        if (leftovers.isEmpty()) {
            return;
        }
        this.logger.warning(leftovers.size() + " crate openings had reward commands that had not run when the server stopped; "
            + "running them now");
        for (Waiting waiting : leftovers) {
            try {
                this.global.accept(() -> dispatchAll(waiting, true));
            } catch (RuntimeException e) {
                this.logger.log(Level.WARNING, "The crate reward commands of " + waiting.ref() + " could not be scheduled; "
                    + "they run at the next start", e);
            }
        }
    }

    /** Runs the commands (global thread), then deletes their rows. A failing command is logged with what to fix. */
    private void dispatchAll(Waiting waiting, boolean replay) {
        for (String command : waiting.commands()) {
            if (replay) {
                this.logger.warning("Running crate reward command '" + command + "' (" + waiting.ref() + ", player " + waiting.player()
                    + ") left over from before the server stopped. If the server crashed right after it ran last time, it ran twice.");
            }
            try {
                if (!this.dispatch.test(command)) {
                    this.logger.warning("Crate reward command '" + command + "' (" + waiting.ref() + ") was not found; give the reward by hand");
                }
            } catch (Throwable t) {
                this.logger.log(Level.WARNING, "Crate reward command '" + command + "' (" + waiting.ref() + ") failed; give the reward by hand", t);
            }
        }
        delete(waiting.ref()).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "The crate reward commands of " + waiting.ref() + " ran but could not be marked as done; "
                    + "they run again at the next start unless the row is deleted from crate_commands", error);
            }
        });
    }

    /** Deletes the stored commands of an opening. */
    CompletableFuture<Integer> delete(String ref) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM crate_commands WHERE ref = ?")) {
                ps.setString(1, ref);
                return ps.executeUpdate();
            }
        });
    }
}
