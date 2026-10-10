package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.StatusBars;
import org.bukkit.entity.Player;

/**
 * Shows the "In combat 12s" timer where each player wants it ({@code combat-timer-display}): on the action bar, on
 * the shared boss bar ({@link StatusBars}, where the combat timer outranks the AFK countdown), both, or nowhere.
 * Safe from any thread: the action bar is a packet and the boss bar changes on the player's thread, and only while the
 * player is still in combat there. Death and quit end combat on that same thread ({@link CombatTagger#clear}), so a
 * timer line the async timer queued just before can't put the bar back after it was taken off.
 */
final class TimerDisplay {

    /** The combat timer's owner name on the shared boss bar. */
    static final String OWNER = "combat";

    private final PlayerSettings settings;
    private final Messenger messenger;
    private final Lang lang;
    private final StatusBars bars;
    private final CombatTags tags;
    private final Scheduler scheduler;

    TimerDisplay(PlayerSettings settings, Messenger messenger, Lang lang, StatusBars bars, CombatTags tags, Scheduler scheduler) {
        this.settings = settings;
        this.messenger = messenger;
        this.lang = lang;
        this.bars = bars;
        this.tags = tags;
        this.scheduler = scheduler;
    }

    /** Whether a timer style shows on the action bar. */
    static boolean actionBar(AlertStyle style) {
        return style == AlertStyle.ACTIONBAR || style == AlertStyle.BOTH;
    }

    /** Whether a timer style shows on the boss bar. */
    static boolean bossBar(AlertStyle style) {
        return style == AlertStyle.BOSSBAR || style == AlertStyle.BOTH;
    }

    /**
     * How full the boss bar is: the time left out of the configured combat time. A longer staff tag shows a full bar
     * until it is down to the configured time.
     */
    static float progress(long secondsLeft, Duration configured) {
        long total = Math.max(Math.max(1, configured.toSeconds()), secondsLeft);
        return Math.clamp((float) secondsLeft / total, 0f, 1f);
    }

    /** Shows the timer with {@code secondsLeft} to a player, in the style they chose. */
    void show(Player player, long secondsLeft, Duration configured) {
        AlertStyle style = this.settings.get(player.getUniqueId(), CombatFeature.TIMER_DISPLAY);
        Arg time = Arg.text("time", Durations.format(Duration.ofSeconds(secondsLeft)));
        if (actionBar(style)) {
            this.messenger.send(player, CombatMessages.TAG_ACTION_BAR, time);
        }
        if (bossBar(style)) {
            StatusBars.Bar bar = new StatusBars.Bar(this.lang.get(CombatMessages.TAG_BOSS_BAR, time),
                progress(secondsLeft, configured), BossBar.Color.RED, BossBar.Overlay.PROGRESS, StatusBars.PRIORITY_COMBAT);
            onPlayer(player, () -> {
                // On the player's thread, where death and quit end combat: a line queued before they did is dropped.
                if (this.tags.tagged(player.getUniqueId())) {
                    this.bars.show(player, OWNER, bar);
                }
            });
        } else {
            hide(player);
        }
    }

    /** Takes the timer off the player's boss bar, if it is there. */
    void hide(Player player) {
        if (this.bars.has(player.getUniqueId(), OWNER)) {
            this.bars.hide(player, OWNER);
        }
    }

    /** Whether the player's boss bar shows the combat timer. */
    boolean showing(UUID player) {
        return this.bars.has(player, OWNER);
    }

    private void onPlayer(Player player, Runnable task) {
        if (this.scheduler.owns(player)) {
            task.run();
        } else {
            this.scheduler.entity(player, task, null);
        }
    }
}
