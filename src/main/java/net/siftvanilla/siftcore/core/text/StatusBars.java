package net.siftvanilla.siftcore.core.text;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * One boss bar per player for lasting status lines (the combat timer, the AFK zone countdown) when the player chose
 * the boss bar style. Several features may want the bar at once: each shows its own {@link Bar} under an owner name
 * and the bar displays the one with the highest priority (the combat timer beats the AFK countdown); hiding an owner
 * brings the next one back. Changes run on the player's thread; the methods may be called from any thread.
 */
public final class StatusBars implements Listener {

    /** The combat timer's priority. */
    public static final int PRIORITY_COMBAT = 100;
    /** Countdowns that wait while the player does nothing (the AFK zone). */
    public static final int PRIORITY_IDLE = 10;

    /**
     * What one owner wants the bar to show.
     *
     * @param progress how full the bar is, 0 to 1
     */
    public record Bar(Component text, float progress, BossBar.Color color, BossBar.Overlay overlay, int priority) {
        public Bar {
            Objects.requireNonNull(text);
            Objects.requireNonNull(color);
            Objects.requireNonNull(overlay);
            progress = Float.isFinite(progress) ? Math.clamp(progress, 0f, 1f) : 0f;
        }
    }

    /**
     * A player's owners and their bar. Changed only on the player's thread; {@link #has} reads the owners from any
     * thread and {@link #clear} reads the bar at shutdown, hence the concurrent map and volatile fields.
     */
    private static final class PlayerBars {
        private final Map<String, Bar> owners = new ConcurrentHashMap<>();
        private volatile BossBar bar;
        private volatile boolean shown;
    }

    private final Scheduler scheduler;
    private final Map<UUID, PlayerBars> players = new ConcurrentHashMap<>();

    public StatusBars(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Shows (or updates) an owner's status on the player's boss bar. */
    public void show(Player player, String owner, Bar bar) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(bar);
        onPlayer(player, () -> {
            PlayerBars state = this.players.computeIfAbsent(player.getUniqueId(), k -> new PlayerBars());
            state.owners.put(owner, bar);
            refresh(player, state);
        });
    }

    /** Removes an owner's status; the bar shows the next owner's, or disappears. */
    public void hide(Player player, String owner) {
        onPlayer(player, () -> {
            PlayerBars state = this.players.get(player.getUniqueId());
            if (state != null && state.owners.remove(owner) != null) {
                refresh(player, state);
            }
        });
    }

    /** Whether an owner currently has a status for the player (any thread). */
    public boolean has(UUID player, String owner) {
        PlayerBars state = this.players.get(player);
        return state != null && state.owners.containsKey(owner);
    }

    /** How many players have a status bar. */
    public int size() {
        return this.players.size();
    }

    /**
     * Hides every bar (plugin shutdown). At server shutdown {@code onDisable} runs on the shutdown thread, which owns
     * every player once the region scheduler halted (docs/research/runtime.md, section 10), so the bars are hidden at
     * once; otherwise each hide is handed to the player's thread, as far as the scheduler still takes tasks.
     */
    public void clear() {
        for (Map.Entry<UUID, PlayerBars> entry : this.players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            BossBar bar = entry.getValue().bar;
            if (player == null || bar == null || !entry.getValue().shown) {
                continue;
            }
            if (this.scheduler.owns(player)) {
                player.hideBossBar(bar);
            } else {
                try {
                    this.scheduler.entity(player, () -> player.hideBossBar(bar), null);
                } catch (RuntimeException e) {
                    // The plugin is being disabled and takes no more tasks: the client drops the bar when it leaves.
                }
            }
        }
        this.players.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.players.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(Player player, PlayerBars state) {
        Map.Entry<String, Bar> top = pick(state.owners);
        if (top == null) {
            if (state.shown) {
                player.hideBossBar(state.bar);
                state.shown = false;
            }
            this.players.remove(player.getUniqueId(), state);
            return;
        }
        Bar wanted = top.getValue();
        if (state.bar == null) {
            state.bar = BossBar.bossBar(wanted.text(), wanted.progress(), wanted.color(), wanted.overlay());
        } else {
            state.bar.name(wanted.text());
            state.bar.progress(wanted.progress());
            state.bar.color(wanted.color());
            state.bar.overlay(wanted.overlay());
        }
        if (!state.shown) {
            player.showBossBar(state.bar);
            state.shown = true;
        }
    }

    /** The status to show: the highest priority, ties broken by owner name. Null when there is none. */
    static Map.Entry<String, Bar> pick(Map<String, Bar> owners) {
        Map.Entry<String, Bar> best = null;
        for (Map.Entry<String, Bar> entry : owners.entrySet()) {
            if (best == null || entry.getValue().priority() > best.getValue().priority()
                || (entry.getValue().priority() == best.getValue().priority() && entry.getKey().compareTo(best.getKey()) < 0)) {
                best = entry;
            }
        }
        return best;
    }

    private void onPlayer(Player player, Runnable task) {
        if (this.scheduler.owns(player)) {
            task.run();
        } else {
            this.scheduler.entity(player, task, null);
        }
    }
}
