package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * Bans, mutes, kicks and warnings. Bans and mutes in force are held in memory ({@link PunishmentBook}) and
 * enforced from there on the login and chat threads; every change is written through to {@code staff_punishments}
 * in the same order it was made. One ban and one mute per player: a new one replaces (lifts) the old one.
 */
final class Punishments implements MuteStatus {

    /** A stored punishment and the one it replaced, if any. */
    record Issued(Punishment punishment, Punishment replaced) {
    }

    private final StaffStore store;
    private final AuditLog audit;
    private final Messenger messenger;
    private final StaffText text;
    private final Supplier<StaffSettings> settings;
    private final Logger logger;
    private final PunishmentBook book = new PunishmentBook();
    private final Object lock = new Object();

    Punishments(StaffStore store, AuditLog audit, Messenger messenger, StaffText text, Supplier<StaffSettings> settings,
                Logger logger) {
        this.store = store;
        this.audit = audit;
        this.messenger = messenger;
        this.text = text;
        this.settings = settings;
        this.logger = logger;
    }

    /** Loads the bans and mutes in force; call once from enable (blocks on storage). */
    void load() throws Exception {
        long now = System.currentTimeMillis();
        List<Punishment> active = this.store.activePunishments(now).get();
        synchronized (this.lock) {
            this.book.load(active, now);
        }
    }

    // ------------------------------------------------------------------ reads (any thread)

    @Override
    public Optional<Mute> mute(UUID player) {
        return activeMute(player).map(p -> new Mute(p.reason(), p.expires(), this.text.staff(p.staff(), p.staffName())));
    }

    Optional<Punishment> activeBan(UUID target) {
        return this.book.active(PunishmentType.BAN, target, System.currentTimeMillis());
    }

    Optional<Punishment> activeMute(UUID target) {
        return this.book.active(PunishmentType.MUTE, target, System.currentTimeMillis());
    }

    int count(PunishmentType type, long now) {
        return this.book.count(type, now);
    }

    // ------------------------------------------------------------------ changes

    /**
     * Records a punishment. A ban or mute replaces the player's current one of that type, which is lifted by the
     * same staff member. Nothing is sent to the player here; callers tell them and kick them as needed.
     *
     * @param length   how long a ban or mute lasts, null for permanent (ignored for kicks and warnings)
     * @param notified for warnings: whether the player has already been told
     */
    Issued issue(PunishmentType type, UUID target, String targetName, Actor actor, String reason, Duration length,
                 boolean notified) {
        long now = System.currentTimeMillis();
        Punishment punishment;
        Punishment replaced = null;
        synchronized (this.lock) {
            if (type.lasting()) {
                Optional<Punishment> current = this.book.active(type, target, now);
                if (current.isPresent()) {
                    replaced = current.get().lift(now, actor.name());
                    watch(this.store.lift(replaced.id(), now, actor.name()), "lift punishment " + replaced.id());
                }
            }
            long expires = type.lasting() ? Punishment.endOf(now, length) : Punishment.NONE;
            punishment = new Punishment(this.store.nextPunishmentId(), type, target, targetName, actor.id(), actor.name(),
                reason, now, expires, Punishment.NONE, "");
            if (type.lasting()) {
                this.book.put(punishment);
            }
            watch(this.store.insert(punishment, notified), "store punishment " + punishment.id());
        }
        String lengthText = punishment.permanent() ? "permanent" : Durations.format(Duration.ofMillis(punishment.expires() - now));
        String details = "#" + punishment.id() + " " + (type.lasting() ? lengthText + ": " : "") + this.text.reason(reason)
            + (replaced == null ? "" : " (replaces #" + replaced.id() + ")");
        this.audit.record(actor.id(), "staff." + type.id().toLowerCase(Locale.ROOT), target.toString(), details);
        return new Issued(punishment, replaced);
    }

    /** Lifts the player's ban or mute. Empty when there was none in force. */
    Optional<Punishment> lift(PunishmentType type, UUID target, Actor actor) {
        long now = System.currentTimeMillis();
        Punishment lifted;
        synchronized (this.lock) {
            Optional<Punishment> current = this.book.active(type, target, now);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            this.book.remove(type, target);
            lifted = current.get().lift(now, actor.name());
            watch(this.store.lift(lifted.id(), now, actor.name()), "lift punishment " + lifted.id());
        }
        String action = type == PunishmentType.BAN ? "staff.unban" : "staff.unmute";
        this.audit.record(actor.id(), action, target.toString(), "#" + lifted.id());
        return Optional.of(lifted);
    }

    /** Drops bans and mutes whose time is up, telling online players their mute ended. Runs on a timer. */
    void sweep() {
        long now = System.currentTimeMillis();
        List<Punishment> ended;
        synchronized (this.lock) {
            ended = this.book.expire(now);
        }
        for (Punishment punishment : ended) {
            if (punishment.type() == PunishmentType.MUTE && punishment.state(now) == PunishmentState.EXPIRED) {
                Player online = Bukkit.getPlayer(punishment.target());
                if (online != null) {
                    this.messenger.send(online, StaffMessages.MUTE_EXPIRED);
                }
            }
        }
    }

    /**
     * Bans and mutes in force in memory and in storage, counted at the same moment: no change can happen between
     * reading memory and queueing the counts, and the counts run after every write queued before them (self-test).
     */
    CompletableFuture<String> checkStorage() {
        long now = System.currentTimeMillis();
        int bans;
        int mutes;
        CompletableFuture<Integer> storedBans;
        CompletableFuture<Integer> storedMutes;
        synchronized (this.lock) {
            bans = this.book.count(PunishmentType.BAN, now);
            mutes = this.book.count(PunishmentType.MUTE, now);
            storedBans = this.store.countActive(PunishmentType.BAN, now);
            storedMutes = this.store.countActive(PunishmentType.MUTE, now);
        }
        return storedBans.thenCombine(storedMutes, (b, m) -> b == bans && m == mutes ? null
            : bans + " bans and " + mutes + " mutes in memory, " + b + " and " + m + " stored");
    }

    // ------------------------------------------------------------------ screens

    /** The disconnect screen of a ban. */
    Component banScreen(Punishment ban) {
        Arg reason = Arg.text("reason", this.text.reason(ban.reason()));
        Arg appeal = Arg.text("appeal", this.settings.get().appeal());
        if (ban.permanent()) {
            return this.text.lang().get(StaffMessages.BAN_SCREEN_PERMANENT, reason, appeal);
        }
        Duration left = ban.remaining(System.currentTimeMillis());
        return this.text.lang().get(StaffMessages.BAN_SCREEN, reason, Arg.time("time", left.isZero() ? Duration.ofSeconds(1) : left), appeal);
    }

    Component kickScreen(String reason) {
        return this.text.lang().get(StaffMessages.KICK_SCREEN, Arg.text("reason", this.text.reason(reason)));
    }

    /**
     * Removes a banned player who is online. Call it on the player's thread: from another thread the server only
     * drops the connection, without the kick event and with "disconnected" as the quit reason.
     */
    void removeBanned(Player player, Punishment ban) {
        player.kick(banScreen(ban), PlayerKickEvent.Cause.BANNED);
    }

    /** Tells a muted player why they can't talk. */
    void tellMuted(Player player, Punishment mute) {
        Arg reason = Arg.text("reason", this.text.reason(mute.reason()));
        if (mute.permanent()) {
            this.messenger.send(player, StaffMessages.MUTED_PERMANENT, reason);
        } else {
            Duration left = mute.remaining(System.currentTimeMillis());
            this.messenger.send(player, StaffMessages.MUTED, reason, Arg.time("time", left.isZero() ? Duration.ofSeconds(1) : left));
        }
    }

    private void watch(CompletableFuture<?> write, String what) {
        write.whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Could not " + what + "; memory and database may differ until a restart", error);
            }
        });
    }
}
