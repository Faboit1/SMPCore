package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.CombatTagEvent;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Puts players in combat. A hit between two players tags both of them for the configured time (every hit starts
 * the timer again), remembers the hit for kill credit, and ends elytra flight when that is turned off in combat.
 * A new tag (not a refresh) is told once in the style the player chose ({@code combat-tag-alert}) and starts the
 * timer where they want it ({@link TimerDisplay}). Tags live in the shared {@link CombatTags}, which teleports and
 * other features read.
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
    private final PlayerSettings prefs;
    private final TimerDisplay timer;
    private final Function<Player, Component> names;

    /**
     * @param names a player's name as they show it (their nickname); safe from any thread
     */
    CombatTagger(Setting<CombatSettings> settings, CombatTags tags, HitLog<ItemStack> hits, TagTicker ticker,
                 Scheduler scheduler, Messenger messenger, PlayerSettings prefs, TimerDisplay timer,
                 Function<Player, Component> names) {
        this.settings = settings;
        this.tags = tags;
        this.hits = hits;
        this.ticker = ticker;
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.prefs = prefs;
        this.timer = timer;
        this.names = names;
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
            started(player, s.tagDuration(), s, Arg.component("name", this.names.apply(opponent)));
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
        CombatSettings s = this.settings.get();
        if (this.tags.tag(id, previous == null ? null : previous.lastAttacker(), duration)) {
            this.ticker.shown(id);
            started(player, duration, s, null);
        } else if (s.actionBar()) {
            this.timer.show(player, TagTicker.secondsLeft(duration.toMillis(), 0), s.tagDuration());
        }
        if (s.disableElytra()) {
            stopGliding(player);
        }
    }

    /**
     * A new tag: the alert in the player's style (never turned into a chat line by quiet in combat: it is combat's
     * own) and the first timer line. {@code opponent} is null for a staff tag.
     */
    private void started(Player player, Duration duration, CombatSettings s, Arg opponent) {
        AlertStyle style = this.prefs.get(player.getUniqueId(), CombatFeature.TAG_ALERT);
        Arg time = Arg.time("time", duration);
        boolean title = style == AlertStyle.TITLE;
        if (opponent == null) {
            this.messenger.alert(player, style, false, title ? CombatMessages.TAG_STARTED_STAFF_TITLE : CombatMessages.TAG_STARTED_STAFF,
                time);
        } else {
            this.messenger.alert(player, style, false, title ? CombatMessages.TAG_STARTED_TITLE : CombatMessages.TAG_STARTED,
                opponent, time);
        }
        if (s.actionBar()) {
            this.timer.show(player, TagTicker.secondsLeft(duration.toMillis(), 0), s.tagDuration());
        }
    }

    /** Ends a player's combat without telling them (death, quit); the timer leaves their boss bar. */
    void clear(UUID player) {
        this.tags.untag(player);
        this.ticker.forget(player);
        this.hits.forget(player);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.timer.hide(online);
        }
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
