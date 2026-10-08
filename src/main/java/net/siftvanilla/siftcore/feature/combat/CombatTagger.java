package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.UUID;
import net.siftvanilla.siftcore.api.event.CombatTagEvent;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Puts players in combat. A hit between two players tags both of them for the configured time (every hit starts
 * the timer again), remembers the hit for kill credit, and ends elytra flight when that is turned off in combat.
 * Tags live in the shared {@link CombatTags}, which teleports and other features read.
 */
final class CombatTagger {

    /** Players with this node are never put in combat. */
    static final String BYPASS = "siftcore.combat.bypass";

    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final HitLog<ItemStack> hits;
    private final TagTicker ticker;
    private final Scheduler scheduler;
    private final Messenger messenger;

    CombatTagger(Setting<CombatSettings> settings, CombatTags tags, HitLog<ItemStack> hits, TagTicker ticker,
                 Scheduler scheduler, Messenger messenger) {
        this.settings = settings;
        this.tags = tags;
        this.hits = hits;
        this.ticker = ticker;
        this.scheduler = scheduler;
        this.messenger = messenger;
    }

    /**
     * {@code attacker} hit {@code victim}. Runs on the victim's thread; the attacker may belong to another region
     * (a long shot), so only packets and the scheduler touch it.
     */
    void hit(Player victim, Player attacker, ItemStack weapon) {
        CombatSettings s = this.settings.get();
        long now = System.currentTimeMillis();
        long window = s.tagDuration().toMillis();
        this.hits.record(victim.getUniqueId(), attacker.getUniqueId(), now, weapon);
        tag(victim, attacker, false, attacker.getUniqueId(), s);
        tag(attacker, victim, true, this.hits.attackerWithin(attacker.getUniqueId(), now, window), s);
    }

    private void tag(Player player, Player opponent, boolean isAttacker, UUID lastAttacker, CombatSettings s) {
        if (player.hasPermission(BYPASS)) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean refresh = this.tags.tagged(id);
        if (CombatTagEvent.getHandlerList().getRegisteredListeners().length > 0
            && !new CombatTagEvent(player, opponent, isAttacker, refresh).callEvent()) {
            return;
        }
        if (this.tags.tag(id, lastAttacker, s.tagDuration())) {
            this.ticker.shown(id);
            if (s.actionBar()) {
                this.messenger.send(player, CombatMessages.TAG_ACTION_BAR, Arg.time("time", s.tagDuration()));
            }
        }
        if (s.disableElytra()) {
            stopGliding(player);
        }
    }

    /** The configured combat time. */
    Duration defaultDuration() {
        return this.settings.get().tagDuration();
    }

    /** Tags a player directly (staff command); no event, no hit. Runs on the player's thread. */
    void tagByStaff(Player player, Duration duration) {
        UUID id = player.getUniqueId();
        CombatTags.Tag previous = this.tags.get(id);
        this.tags.tag(id, previous == null ? null : previous.lastAttacker(), duration);
        this.ticker.shown(id);
        if (this.settings.get().actionBar()) {
            this.messenger.send(player, CombatMessages.TAG_ACTION_BAR, Arg.time("time", duration));
        }
        if (this.settings.get().disableElytra()) {
            stopGliding(player);
        }
    }

    /** Ends a player's combat without telling them (death, quit). */
    void clear(UUID player) {
        this.tags.untag(player);
        this.ticker.forget(player);
        this.hits.forget(player);
    }

    private void stopGliding(Player player) {
        if (this.scheduler.owns(player)) {
            if (player.isGliding()) {
                player.setGliding(false);
            }
            return;
        }
        if (player.isGliding()) {
            this.scheduler.entity(player, () -> {
                if (player.isGliding()) {
                    player.setGliding(false);
                }
            }, null);
        }
    }
}
