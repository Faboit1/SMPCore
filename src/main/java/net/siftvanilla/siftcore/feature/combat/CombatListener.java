package net.siftvanilla.siftcore.feature.combat;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Every combat event. Each handler runs on the thread that owns its event (the victim's or the player's region) and
 * only touches memory, the player concerned and packets.
 */
final class CombatListener implements Listener {

    /** How often the same refusal from a repeating event is told to a player at most. */
    private static final long REFUSAL_INTERVAL_MILLIS = 1_000;

    /**
     * What the death-message stage hands to the stage that runs once the death is final.
     *
     * @param logoutLine the message is a combat log announcement (not a death message)
     */
    private record PendingDeath(KillTracker.Credit credit, Component message, boolean logoutLine, boolean combatLog, long at) {
    }

    /** A refusal a player was told about, and when. */
    private record Refusal(MessageKey key, long at) {
    }

    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final CombatTagger tagger;
    private final KillTracker kills;
    private final DeathMessages deathMessages;
    private final CombatLogs logs;
    private final SpawnArea spawn;
    private final PlayerDirectory directory;
    private final Participants participants;
    private final Messenger messenger;
    private final Cosmetics cosmetics;
    private final PlayerSettings prefs;
    private final Scheduler scheduler;
    private final StaffNotices staff;
    private final Map<UUID, PendingDeath> pending = new ConcurrentHashMap<>();
    /** The last refusal each player was told about by a repeating event (a step, a throw, a glide), to tell it once a second. */
    private final Map<UUID, Refusal> refused = new ConcurrentHashMap<>();

    CombatListener(Setting<CombatSettings> settings, CombatTags tags, CombatTagger tagger, KillTracker kills,
                   DeathMessages deathMessages, CombatLogs logs, SpawnArea spawn, PlayerDirectory directory,
                   Participants participants, Messenger messenger, Cosmetics cosmetics, PlayerSettings prefs, Scheduler scheduler,
                   StaffNotices staff) {
        this.settings = settings;
        this.tags = tags;
        this.tagger = tagger;
        this.kills = kills;
        this.deathMessages = deathMessages;
        this.logs = logs;
        this.spawn = spawn;
        this.directory = directory;
        this.participants = participants;
        this.messenger = messenger;
        this.cosmetics = cosmetics;
        this.prefs = prefs;
        this.scheduler = scheduler;
        this.staff = staff;
    }

    // ------------------------------------------------------------------ tagging

    /** Runs after protection plugins and friendly fire had their say, so only real hits tag. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = Attackers.of(event, this.settings.get().tagPets());
        if (attacker == null || attacker.equals(victim) || this.participants.outOfPlay(attacker) || this.participants.outOfPlay(victim)) {
            return;
        }
        this.tagger.hit(victim, attacker, Attackers.weapon(event, attacker));
    }

    // ------------------------------------------------------------------ while tagged

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero() || !this.settings.get().blockedCommands().blocks(event.getMessage(), CombatListener::names)) {
            return;
        }
        event.setCancelled(true);
        refuse(player, CombatMessages.BLOCKED_COMMAND, left);
    }

    /** Every name of the command behind a label: the label, the command's name and its aliases, without namespaces. */
    private static Set<String> names(String label) {
        Set<String> names = new HashSet<>();
        names.add(label);
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command != null) {
            names.add(CommandFilter.label(command.getName()));
            names.add(CommandFilter.label(command.getLabel()));
            for (String alias : command.getAliases()) {
                names.add(CommandFilter.label(alias));
            }
        }
        return names;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof EnderPearl) || !this.settings.get().blockEnderPearls()) {
            return;
        }
        Player player = event.getPlayer();
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero()) {
            return;
        }
        event.setShouldConsume(false);
        event.setCancelled(true);
        refuseRepeating(player, CombatMessages.BLOCKED_ENDER_PEARL, left);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player) || !this.settings.get().disableElytra()) {
            return;
        }
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero()) {
            return;
        }
        event.setCancelled(true);
        refuseRepeating(player, CombatMessages.BLOCKED_ELYTRA, left);
    }

    /** Walking into the protected spawn area while tagged is undone and the player is pushed back out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || this.tags.size() == 0 || !this.settings.get().blockSpawnEntry()) {
            return;
        }
        Player player = event.getPlayer();
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!this.spawn.contains(to) || this.spawn.contains(from)) {
            return;
        }
        event.setCancelled(true);
        Vector back = from.toVector().subtract(to.toVector()).setY(0);
        if (back.lengthSquared() > 1.0E-4) {
            player.setVelocity(back.normalize().multiply(0.6).setY(0.25));
        }
        refuseRepeating(player, CombatMessages.BLOCKED_SPAWN, left);
    }

    /** Pearls and chorus fruit into spawn (registered through {@link AsyncTeleportGuard}). */
    boolean refuseTeleportIntoSpawn(Player player, Location to) {
        if (!this.settings.get().blockSpawnEntry()) {
            return false;
        }
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero() || !this.spawn.contains(to) || this.spawn.contains(player.getLocation())) {
            return false;
        }
        refuseRepeating(player, CombatMessages.BLOCKED_SPAWN, left);
        return true;
    }

    private void refuse(Player player, MessageKey key, Duration left) {
        this.messenger.send(player, key, Arg.text("time", Durations.format(Duration.ofSeconds(TagTicker.secondsLeft(left.toMillis(), 0)))));
    }

    /**
     * A refusal of something the game repeats while the player keeps at it (each step into spawn, each throw while the
     * use key is held): told at most once a second per refusal, like spawn protection does, so neither the line nor the
     * error note floods. The action itself is refused every time.
     */
    private void refuseRepeating(Player player, MessageKey key, Duration left) {
        long now = System.currentTimeMillis();
        Refusal last = this.refused.get(player.getUniqueId());
        if (last != null && last.key().equals(key) && now - last.at() < REFUSAL_INTERVAL_MILLIS) {
            return;
        }
        this.refused.put(player.getUniqueId(), new Refusal(key, now));
        refuse(player, key, left);
    }

    // ------------------------------------------------------------------ deaths

    /**
     * Works out the kill credit and the death message while the event can still change: the message replaces the
     * game's broadcast (sent to each player according to their setting) and is also shown on the death screen.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeathMessage(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        long now = System.currentTimeMillis();
        KillTracker.Credit credit = this.kills.resolve(victim, now);
        boolean combatLog = this.logs.leaving(victim.getUniqueId());
        CombatSettings s = this.settings.get();
        Component original = event.deathMessage();
        Component message = null;
        boolean logoutLine = false;
        Component victimName = this.deathMessages.name(victim.getUniqueId(), victim.getName());
        Component killerName = credit.pvp() ? this.deathMessages.name(credit.killer(), this.directory.name(credit.killer())) : null;
        if (combatLog && s.announceLogout()) {
            message = this.deathMessages.logout(victimName, killerName);
            logoutLine = true;
            event.deathMessage(null);
        } else if (s.deathMessages() && original != null && event.getShowDeathMessages()) {
            message = credit.pvp()
                ? this.deathMessages.kill(victimName, killerName, s.showWeapon() ? credit.weapon() : null)
                : this.deathMessages.restyle(original);
            event.deathMessage(null);
            if (event.deathScreenMessageOverride() == null) {
                event.deathScreenMessageOverride(message);
            }
        }
        this.pending.put(victim.getUniqueId(), new PendingDeath(credit, message, logoutLine, combatLog, now));
    }

    /**
     * The death happened: end the victim's combat, send the message, count the kill (bounties hook in here), tell the
     * victim where they died and how their killer was doing, tell the killer whether the kill counted (and staff who
     * watch for farming when it didn't), and announce kill streaks that were reached or ended.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        UUID victimId = victim.getUniqueId();
        PendingDeath death = this.pending.remove(victimId);
        if (death == null || event.isCancelled()) {
            return;
        }
        this.tagger.clear(victimId);
        UUID killer = death.credit().killer();
        if (death.message() != null) {
            if (death.logoutLine()) {
                this.deathMessages.logoutLine(death.message(), victimId, killer);
            } else {
                this.deathMessages.death(death.message(), victimId, killer);
            }
        }
        KillTracker.Outcome outcome = this.kills.died(victim, death.credit(), death.combatLog(), death.at());
        if (!death.combatLog()) {
            location(victim);
        }
        if (killer == null) {
            return;
        }
        Component killerName = this.deathMessages.name(killer, this.directory.name(killer));
        Player killerPlayer = Bukkit.getPlayer(killer);
        if (killerPlayer != null) {
            // The killer's kill effect where the victim fell (this is the victim's region thread, which owns the spot).
            this.cosmetics.kill(killerPlayer, victim, victim.getLocation());
            killFeedback(killerPlayer, victim, outcome.decision(), outcome.killerStreak());
            if (!death.combatLog()) {
                recap(victim, killerPlayer, killerName, death.credit().weapon());
            }
        }
        if (!outcome.counted() && outcome.decision() != null) {
            this.staff.notCounted(killer, victimId, outcome.decision().reason());
        }
        CombatSettings.Streaks streaks = this.settings.get().streaks();
        if (streaks.ended(outcome.endedStreak())) {
            this.deathMessages.streakLine(this.deathMessages.streakEnded(killerName, this.deathMessages.name(victimId, victim.getName()),
                outcome.endedStreak()), victimId, killer);
        }
        if (outcome.counted() && streaks.reached(outcome.killerStreak())) {
            this.deathMessages.streakLine(this.deathMessages.streak(killerName, outcome.killerStreak()), victimId, killer);
        }
    }

    /**
     * A private chat line telling the victim where they died ({@code death-coordinates}); only the world while they
     * hide coordinates (streamer mode). Runs on the victim's thread, which owns the spot.
     */
    private void location(Player victim) {
        UUID id = victim.getUniqueId();
        MessageKey line = locationLine(this.prefs.get(id, CombatFeature.DEATH_COORDINATES), this.prefs.get(id, SharedSettings.HIDE_COORDINATES));
        if (line == null) {
            return;
        }
        Location at = victim.getLocation();
        List<World> worlds = Bukkit.getWorlds();
        Arg world = Arg.text("world", worldName(this.messenger.lang(), at.getWorld().getName(),
            worlds.isEmpty() ? null : worlds.getFirst().getName()));
        if (line == CombatMessages.DEATH_LOCATION_HIDDEN) {
            this.messenger.chat(victim, line, world);
        } else {
            this.messenger.chat(victim, line, world, Arg.text("x", Lang.number(at.getBlockX())), Arg.text("y", Lang.number(at.getBlockY())),
                Arg.text("z", Lang.number(at.getBlockZ())));
        }
    }

    /**
     * How a world is named in the death location: the server's main world is "Overworld", its nether "Nether" and its
     * end "The End" (lang {@code combat.worlds}); any other world shows its own name.
     *
     * @param mainWorld the name of the server's main world (the first one loaded), or null when unknown
     */
    static String worldName(Lang lang, String world, String mainWorld) {
        MessageKey key = null;
        if (mainWorld != null) {
            if (world.equals(mainWorld)) {
                key = CombatMessages.WORLD_OVERWORLD;
            } else if (world.equals(mainWorld + "_nether")) {
                key = CombatMessages.WORLD_NETHER;
            } else if (world.equals(mainWorld + "_the_end")) {
                key = CombatMessages.WORLD_END;
            }
        }
        return key == null ? world : lang.plain(key);
    }

    /**
     * The victim's death location line: none while their {@code death-coordinates} is off, only the world while they
     * hide coordinates (streamer mode), otherwise the block coordinates and the world.
     */
    static MessageKey locationLine(boolean deathCoordinates, boolean hideCoordinates) {
        if (!deathCoordinates) {
            return null;
        }
        return hideCoordinates ? CombatMessages.DEATH_LOCATION_HIDDEN : CombatMessages.DEATH_LOCATION;
    }

    /**
     * Tells the killer whether their kill counted ({@code kill-feedback}), with their new streak, or why it didn't
     * count (a shared IP address is never named). Packets only, so the killer's region doesn't matter.
     */
    private void killFeedback(Player killer, Player victim, AntiFarm.Decision decision, int streak) {
        if (decision == null) {
            return;
        }
        AlertStyle style = this.prefs.get(killer.getUniqueId(), CombatFeature.KILL_FEEDBACK);
        KillNotice notice = KillNotice.of(decision);
        MessageKey key = notice.key(style);
        if (key == null) {
            return;
        }
        Arg name = Arg.component("name", this.deathMessages.name(victim.getUniqueId(), victim.getName()));
        switch (notice) {
            case COUNTED -> this.messenger.alert(killer, style, false, key, name, Arg.text("streak", Lang.number(streak)));
            case NOT_COUNTED -> this.messenger.alert(killer, style, false, key, name,
                Arg.text("reason", this.messenger.lang().plain(CombatMessages.reason(decision.reason()))));
            case NOT_COUNTED_PLAIN -> this.messenger.alert(killer, style, false, key, name);
        }
    }

    /**
     * Tells the victim the health their killer had left and the weapon of the last hit ({@code death-recap}). The
     * killer's health is read on the killer's thread, which then sends the line.
     */
    private void recap(Player victim, Player killer, Component killerName, ItemStack weapon) {
        if (killer.equals(victim) || !this.prefs.get(victim.getUniqueId(), CombatFeature.DEATH_RECAP)) {
            return;
        }
        this.scheduler.entity(killer, () -> {
            if (killer.isDead() || !killer.isValid()) {
                return;
            }
            Arg hearts = Arg.text("hearts", heartsText(hearts(killer.getHealth(), killer.getAbsorptionAmount())));
            if (weapon == null) {
                this.messenger.chat(victim, CombatMessages.DEATH_RECAP, Arg.component("killer", killerName), hearts);
            } else {
                this.messenger.chat(victim, CombatMessages.DEATH_RECAP_USING, Arg.component("killer", killerName), hearts,
                    Arg.component("item", DeathMessages.itemName(weapon)));
            }
        }, null);
    }

    /** Health plus absorption in hearts (two health points each), to one decimal. */
    static double hearts(double health, double absorption) {
        return Math.round((Math.max(0, health) + Math.max(0, absorption)) * 5) / 10.0;
    }

    /** Hearts as the recap writes them: "6.5", or "10" without a needless ".0". */
    static String heartsText(double hearts) {
        long whole = Math.round(hearts * 10);
        return whole % 10 == 0 ? Long.toString(whole / 10) : (whole / 10) + "." + Math.abs(whole % 10);
    }

    // ------------------------------------------------------------------ leaving

    /** Before other quit handlers save and forget the player, so the kill and the drops happen first. */
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        this.logs.quit(event);
        this.pending.remove(event.getPlayer().getUniqueId());
        this.refused.remove(event.getPlayer().getUniqueId());
    }
}
