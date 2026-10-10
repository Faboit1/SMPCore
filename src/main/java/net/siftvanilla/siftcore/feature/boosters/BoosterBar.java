package net.siftvanilla.siftcore.feature.boosters;

import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.StatusBars;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The boss bar everyone online sees while a booster runs: its percent, who it is from and the time left, with the
 * bar emptying as it runs out. Players who turned the "Booster bar" switch off don't see it.
 * <p>
 * It is a line on the shared per-player status bar ({@link StatusBars}, owner {@value #OWNER}) at the lowest priority,
 * so a personal status (the combat timer, the AFK zone countdown) takes the bar while it lasts and the booster comes
 * back after; a player never sees two bars. The status bar changes each player's bar only on that player's thread
 * (the server keeps a player's shown bars in a plain set); the global thread only works out what the line says, once
 * a second.
 */
final class BoosterBar {

    /** The owner name of the booster's line on the status bar. */
    static final String OWNER = "boosters";

    /**
     * What the bar shows now.
     *
     * @param title    the title (percent, who it is from, time left)
     * @param progress how much of the booster is left, 0 to 1
     */
    record Frame(Component title, float progress, BossBar.Color color, BossBar.Overlay overlay) {
        Frame {
            progress = Math.clamp(progress, 0f, 1f);
        }
    }

    private final StatusBars bars;
    private final PlayerSettings settings;
    private final Toggle toggle;
    /** What every bar should show, or null when none should be shown. Written by the global thread. */
    private volatile Frame frame;

    BoosterBar(StatusBars bars, PlayerSettings settings, Toggle toggle) {
        this.bars = bars;
        this.settings = settings;
        this.toggle = toggle;
    }

    /**
     * Publishes what the bar shows ({@code null}: no booster runs, or the bar is turned off) and brings every online
     * player's line in line with it and their switch. Global thread, every second.
     */
    void refresh(Frame next) {
        this.frame = next;
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync(player, next);
        }
    }

    /** Brings one player's line in line with the frame and their switch at once (after they flipped it). Any thread. */
    void update(Player player) {
        sync(player, this.frame);
    }

    private void sync(Player player, Frame current) {
        UUID id = player.getUniqueId();
        if (current != null && this.settings.enabled(id, this.toggle)) {
            this.bars.show(player, OWNER, new StatusBars.Bar(current.title(), current.progress(), current.color(), current.overlay(),
                StatusBars.PRIORITY_SERVER));
        } else if (this.bars.has(id, OWNER)) {
            this.bars.hide(player, OWNER);
        }
    }
}
