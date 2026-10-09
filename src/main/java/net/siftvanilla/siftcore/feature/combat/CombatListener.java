package net.siftvanilla.siftcore.feature.combat;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.util.Vector;

/**
 * Every combat event. Each handler runs on the thread that owns its event (the victim's or the player's region) and
 * only touches memory, the player concerned and packets.
 */
final class CombatListener implements Listener {

    /** What the death-message stage hands to the stage that runs once the death is final. */
    private record PendingDeath(KillTracker.Credit credit, Component message, boolean everyone, boolean combatLog, long at) {
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
    private final Map<UUID, PendingDeath> pending = new ConcurrentHashMap<>();

    CombatListener(Setting<CombatSettings> settings, CombatTags tags, CombatTagger tagger, KillTracker kills,
                   DeathMessages deathMessages, CombatLogs logs, SpawnArea spawn, PlayerDirectory directory,
                   Participants participants, Messenger messenger, Cosmetics cosmetics) {
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
        refuse(player, CombatMessages.BLOCKED_ENDER_PEARL, left);
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
        refuse(player, CombatMessages.BLOCKED_ELYTRA, left);
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
        refuse(player, CombatMessages.BLOCKED_SPAWN, left);
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
        refuse(player, CombatMessages.BLOCKED_SPAWN, left);
        return true;
    }

    private void refuse(Player player, MessageKey key, Duration left) {
        this.messenger.send(player, key, Arg.time("time", Duration.ofSeconds(TagTicker.secondsLeft(left.toMillis(), 0))));
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
        boolean everyone = false;
        Component victimName = this.deathMessages.name(victim.getUniqueId(), victim.getName());
        Component killerName = credit.pvp() ? this.deathMessages.name(credit.killer(), this.directory.name(credit.killer())) : null;
        if (combatLog && s.announceLogout()) {
            message = this.deathMessages.logout(victimName, killerName);
            everyone = true;
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
        this.pending.put(victim.getUniqueId(), new PendingDeath(credit, message, everyone, combatLog, now));
    }

    /**
     * The death happened: end the victim's combat, send the message, count the kill (bounties hook in here) and
     * announce kill streaks that were reached or ended.
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
            this.deathMessages.send(death.message(), victimId, killer, death.everyone());
        }
        KillTracker.Outcome outcome = this.kills.died(victim, death.credit(), death.combatLog(), death.at());
        if (killer == null) {
            return;
        }
        Player killerPlayer = Bukkit.getPlayer(killer);
        if (killerPlayer != null) {
            // The killer's kill effect where the victim fell (this is the victim's region thread, which owns the spot).
            this.cosmetics.kill(killerPlayer, victim, victim.getLocation());
        }
        CombatSettings.Streaks streaks = this.settings.get().streaks();
        Component killerName = this.deathMessages.name(killer, this.directory.name(killer));
        if (streaks.ended(outcome.endedStreak())) {
            this.deathMessages.send(this.deathMessages.streakEnded(killerName, this.deathMessages.name(victimId, victim.getName()),
                outcome.endedStreak()), victimId, killer, false);
        }
        if (outcome.counted() && streaks.reached(outcome.killerStreak())) {
            this.deathMessages.send(this.deathMessages.streak(killerName, outcome.killerStreak()), victimId, killer, false);
        }
    }

    // ------------------------------------------------------------------ leaving

    /** Before other quit handlers save and forget the player, so the kill and the drops happen first. */
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        this.logs.quit(event);
        this.pending.remove(event.getPlayer().getUniqueId());
    }
}
