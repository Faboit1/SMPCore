package net.siftvanilla.siftcore.feature.crates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.KeyallEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The keyall: every interval, everyone online gets keys of one crate. A global timer ticks once a second: it
 * announces the keyall in chat at the configured moments, counts the last seconds down in the action bar, and at
 * zero gives every online player the keys. Each player's grant carries a reference unique to that player and run,
 * so no player can get one keyall twice. The next time is stored, so restarts keep the schedule; a keyall that fell
 * into a restart runs shortly after the server is back.
 */
final class Keyall {

    /** The schedule row id. */
    static final String SCHEDULE = "keyall";

    /** What a keyall did. */
    record Run(int given, boolean cancelled, List<UUID> recipients) {
    }

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final KeyService keys;
    private final CrateText text;
    private final VanishStatus vanish;
    private final AfkStatus afk;
    private final Logger logger;
    private final String upsert;
    private volatile long nextRun;
    private volatile long lastRun;
    private volatile int runs;
    private volatile long lastRemaining = Long.MAX_VALUE;
    private volatile Task timer = Task.NONE;
    private volatile long lastTick;

    Keyall(Services services, Setting<CratesSettings> settings, KeyService keys, CrateText text, VanishStatus vanish, AfkStatus afk) {
        this.services = services;
        this.settings = settings;
        this.keys = keys;
        this.text = text;
        this.vanish = vanish;
        this.afk = afk;
        this.logger = services.plugin().getLogger();
        this.upsert = services.database().dialect().replaceUpsert("crate_schedule", new String[] {"id"},
            new String[] {"next_run", "last_run", "runs"});
    }

    /** Loads the stored schedule, works out the next run and starts the timer. Blocking; call at startup. */
    void start() throws Exception {
        long[] stored = this.services.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT next_run, last_run, runs FROM crate_schedule WHERE id = ?")) {
                ps.setString(1, SCHEDULE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)} : new long[] {0, 0, 0};
                }
            }
        }).get();
        this.lastRun = stored[1];
        this.runs = (int) stored[2];
        CratesSettings.Keyall config = this.settings.get().keyall();
        long now = System.currentTimeMillis();
        this.nextRun = KeyallClock.nextAfterStart(stored[0], now, config.interval(), config.missedDelay());
        this.lastRemaining = this.nextRun - now;
        if (this.nextRun != stored[0]) {
            save();
        }
        this.lastTick = now;
        this.timer = this.services.scheduler().globalTimer(this::tick, 20L, 20L);
    }

    void stop() {
        this.timer.cancel();
        this.timer = Task.NONE;
    }

    /** After a reload: the next run never lies further away than one (new) interval. */
    void reloaded(CratesSettings settings) {
        long now = System.currentTimeMillis();
        long next = KeyallClock.afterReload(this.nextRun, now, settings.keyall().interval());
        if (next != this.nextRun) {
            this.nextRun = next;
            this.lastRemaining = next - now;
            save();
        }
    }

    boolean enabled() {
        return this.settings.get().keyall().enabled();
    }

    long nextRun() {
        return this.nextRun;
    }

    long lastTick() {
        return this.lastTick;
    }

    int runs() {
        return this.runs;
    }

    /** Time left until the next keyall, rounded up to whole seconds. */
    Duration remaining() {
        return KeyallClock.remaining(this.nextRun, System.currentTimeMillis());
    }

    /** {@code 1 Uncommon key}: what the scheduled keyall gives, or null when its crate is unknown. */
    Component reward() {
        CratesSettings settings = this.settings.get();
        Crate crate = settings.crate(settings.keyall().crate());
        return crate == null ? null : this.text.keys(settings.keyall().amount(), crate);
    }

    /** Moves the next scheduled keyall to {@code delay} from now (staff). */
    void schedule(Duration delay) {
        long now = System.currentTimeMillis();
        this.nextRun = now + delay.toMillis();
        this.lastRemaining = this.nextRun - now;
        save();
    }

    /** Runs once a second on the global thread. */
    private void tick() {
        long now = System.currentTimeMillis();
        this.lastTick = now;
        CratesSettings settings = this.settings.get();
        CratesSettings.Keyall config = settings.keyall();
        if (!config.enabled()) {
            this.lastRemaining = this.nextRun - now;
            return;
        }
        long remaining = this.nextRun - now;
        long before = this.lastRemaining;
        this.lastRemaining = remaining;
        Component reward = reward();
        if (reward == null) {
            return;
        }
        if (remaining <= 0) {
            long scheduledAt = this.nextRun;
            this.nextRun = now + config.interval().toMillis();
            this.lastRemaining = this.nextRun - now;
            this.lastRun = now;
            this.runs++;
            save();
            try {
                run(config.crate(), config.amount(), true, "system", "s" + Long.toString(scheduledAt, 36));
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "The keyall failed", e);
            }
            return;
        }
        Duration announce = KeyallClock.crossed(before, remaining, config.chatAt());
        int seconds = KeyallClock.actionBarSeconds(remaining, config.actionBarFrom());
        if (announce == null && seconds <= 0) {
            return;
        }
        // Each player's Keyall countdown setting picks the chat announcements, the action bar count, both or neither;
        // the console always gets the announcements. The "everyone got keys" line is not part of it.
        Arg time = announce == null ? null : Arg.time("time", announce);
        Arg keys = Arg.component("keys", reward);
        Arg count = seconds <= 0 ? null : Arg.time("time", Duration.ofSeconds(seconds));
        for (Player player : Bukkit.getOnlinePlayers()) {
            AlertStyle style = this.services.settings().get(player.getUniqueId(), CratePlayerSettings.KEYALL_COUNTDOWN);
            if (time != null && CratePlayerSettings.countdownInChat(style)) {
                this.services.messenger().send(player, CratesMessages.KEYALL_COUNTDOWN, time, keys);
            }
            if (count != null && CratePlayerSettings.countdownInActionBar(style)) {
                this.services.messenger().send(player, CratesMessages.KEYALL_ACTIONBAR, count);
            }
        }
        if (time != null) {
            this.services.messenger().send(Bukkit.getConsoleSender(), CratesMessages.KEYALL_COUNTDOWN, time, keys);
        }
    }

    /**
     * Gives every online player (except vanished staff and AFK players, when configured so) {@code amount} keys of
     * {@code crate}.
     * Fires {@link KeyallEvent} first. Safe from any thread.
     *
     * @param runId unique per keyall; with the player's UUID it makes each grant's reference
     */
    Run run(String crate, int amount, boolean scheduled, String actor, String runId) {
        CratesSettings settings = this.settings.get();
        Crate target = settings.crate(crate);
        if (target == null) {
            return new Run(0, false, List.of());
        }
        Map<UUID, Player> online = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.put(player.getUniqueId(), player);
        }
        List<UUID> recipients = recipients(online.keySet(), settings.keyall(), this.vanish, this.afk,
            this.services.directory()::connection);
        List<Player> players = new ArrayList<>(recipients.size());
        for (UUID uuid : recipients) {
            players.add(online.get(uuid));
        }
        if (!new KeyallEvent(crate, amount, recipients, scheduled, actor).callEvent()) {
            this.logger.info("A plugin cancelled the keyall of " + amount + " " + crate + " key(s)");
            return new Run(0, true, recipients);
        }
        int given = 0;
        for (Player player : players) {
            TransactionResult result = this.keys.give(player.getUniqueId(), crate, amount, actor, "keyall:" + runId + ":" + player.getUniqueId());
            if (result.success()) {
                given++;
            } else {
                this.logger.warning("Keyall keys for " + player.getName() + " were not given: " + result.status() + " " + result.reason());
            }
        }
        Component keys = this.text.keys(amount, target);
        if (given > 0) {
            this.services.messenger().broadcast(CratesMessages.KEYALL_DONE, Arg.component("keys", keys));
            // Players left out for being AFK or for sharing a connection are told why, so the broadcast doesn't read
            // like a mistake.
            CratesSettings.Keyall config = settings.keyall();
            Set<UUID> chosen = new HashSet<>(recipients);
            for (Map.Entry<UUID, Player> entry : online.entrySet()) {
                UUID uuid = entry.getKey();
                if (chosen.contains(uuid) || (!config.includeVanished() && this.vanish.vanished(uuid))) {
                    continue;
                }
                if (!config.includeAfk() && this.afk.afk(uuid)) {
                    this.services.messenger().send(entry.getValue(), CratesMessages.KEYALL_MISSED_AFK, Arg.component("keys", keys));
                } else if (config.onePerConnection()) {
                    this.services.messenger().send(entry.getValue(), CratesMessages.KEYALL_MISSED_CONNECTION, Arg.component("keys", keys));
                }
            }
        }
        this.services.audit().record(actor, "crates.keyall", crate, "amount=" + amount + " players=" + given
            + " scheduled=" + scheduled + " run=" + runId);
        return new Run(given, false, recipients);
    }

    /**
     * Who gets a keyall's keys, in the order given: everyone online except vanished staff (unless
     * {@code include-vanished}) and AFK players (unless {@code include-afk}). Pure.
     */
    static List<UUID> recipients(Collection<UUID> online, CratesSettings.Keyall config, VanishStatus vanish, AfkStatus afk) {
        return recipients(online, config, vanish, afk, uuid -> "player:" + uuid);
    }

    /**
     * The same, and with {@code one-per-connection} only the first account of each connection, in the order given
     * (Bukkit lists players in the order they joined, so the one online longest). Pure.
     *
     * @param connection a player's connection key ({@code PlayerDirectory#connection})
     */
    static List<UUID> recipients(Collection<UUID> online, CratesSettings.Keyall config, VanishStatus vanish, AfkStatus afk,
                                 java.util.function.Function<UUID, String> connection) {
        List<UUID> result = new ArrayList<>(online.size());
        Set<String> connections = new HashSet<>();
        for (UUID uuid : online) {
            if ((config.includeVanished() || !vanish.vanished(uuid)) && (config.includeAfk() || !afk.afk(uuid))
                && (!config.onePerConnection() || connections.add(connection.apply(uuid)))) {
                result.add(uuid);
            }
        }
        return result;
    }

    /** A run id for a keyall started by a command. */
    static String manualRunId() {
        return "m" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36), 36);
    }

    private void save() {
        long next = this.nextRun;
        long last = this.lastRun;
        int count = this.runs;
        this.services.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.upsert)) {
                ps.setString(1, SCHEDULE);
                ps.setLong(2, next);
                ps.setLong(3, last);
                ps.setInt(4, count);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "The keyall schedule could not be saved", error);
            }
        });
    }
}
