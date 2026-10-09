package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.api.event.CombatLogEvent;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Leaving in combat. With the kill punishment the player dies where they stand as they leave: their items drop
 * there, the death is processed like any other (the last player who hit them gets the kill, and any bounty on them
 * is claimed) and the announcement replaces the death message. With no punishment only the announcement is made.
 * Every combat log is written to the audit log.
 */
final class CombatLogs {

    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final CombatTagger tagger;
    private final DeathMessages messages;
    private final AuditLog audit;
    private final PlayerDirectory directory;
    private final Participants participants;
    private final Set<UUID> leaving = ConcurrentHashMap.newKeySet();

    CombatLogs(Setting<CombatSettings> settings, CombatTags tags, CombatTagger tagger, DeathMessages messages, AuditLog audit,
               PlayerDirectory directory, Participants participants) {
        this.settings = settings;
        this.tags = tags;
        this.tagger = tagger;
        this.messages = messages;
        this.audit = audit;
        this.directory = directory;
        this.participants = participants;
    }

    /** True while a combat logger is being killed (their death message becomes the announcement). */
    boolean leaving(UUID player) {
        return this.leaving.contains(player);
    }

    /** Handles a player leaving. Runs on the player's thread, before the server saves and removes them. */
    void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        CombatTags.Tag tag = this.tags.get(id);
        try {
            if (tag == null || player.isDead() || this.participants.outOfPlay(player)) {
                return;
            }
            CombatSettings s = this.settings.get();
            if (event.getReason() == PlayerQuitEvent.QuitReason.KICKED && !s.punishKicks()) {
                return;
            }
            Duration remaining = Duration.ofMillis(Math.max(0, tag.until() - System.currentTimeMillis()));
            CombatLogEvent log = new CombatLogEvent(player, tag.lastAttacker(), remaining, s.logoutPunishment());
            if (!log.callEvent()) {
                return;
            }
            boolean killed = false;
            if (log.punishment() == CombatLogEvent.Punishment.KILL) {
                this.leaving.add(id);
                try {
                    player.setHealth(0);
                } finally {
                    this.leaving.remove(id);
                }
                killed = player.isDead();
            }
            if (!killed && s.announceLogout()) {
                this.messages.send(this.messages.logout(this.messages.name(id, player.getName()), null), id, null, true);
            }
            this.audit.record(id.toString(), "combat.log", id.toString(), "punishment=" + log.punishment().name().toLowerCase(java.util.Locale.ROOT)
                + ", killed=" + killed + ", reason=" + event.getReason().name().toLowerCase(java.util.Locale.ROOT)
                + ", left=" + remaining.toMillis() + "ms, last attacker="
                + (tag.lastAttacker() == null ? "none" : this.directory.name(tag.lastAttacker()) + " " + tag.lastAttacker())
                + ", at=" + location(player));
        } finally {
            this.tagger.clear(id);
        }
    }

    private static String location(Player player) {
        var location = player.getLocation();
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }
}
