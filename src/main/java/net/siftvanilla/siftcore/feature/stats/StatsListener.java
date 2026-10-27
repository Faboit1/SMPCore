package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Loads players' stats before they enter the world, saves them when they leave, and counts mobs killed and blocks
 * mined. Player kills and deaths are reported by the combat feature through {@link StatsStore#kill} and
 * {@link StatsStore#death}. Each handler runs on the thread that owns its event and only touches memory.
 */
final class StatsListener implements Listener {

    private static final long LOGIN_LOAD_SECONDS = 5;
    private static final StatsDelta ONE_MOB = StatsDelta.add(Counter.MOBS, 1);
    private static final StatsDelta ONE_BLOCK = StatsDelta.add(Counter.BLOCKS, 1);

    private final StatsStore store;
    private final PlacedBlocks placed;
    private final Setting<StatsSettings> settings;
    private final Logger logger;

    StatsListener(StatsStore store, PlacedBlocks placed, Setting<StatsSettings> settings, Logger logger) {
        this.store = store;
        this.placed = placed;
        this.settings = settings;
        this.logger = logger;
    }

    /** Stats are loaded off the world threads before the player joins (this event may block). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            this.store.load(event.getUniqueId()).get(LOGIN_LOAD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            this.logger.warning("The stats of " + event.getName() + " took longer than " + LOGIN_LOAD_SECONDS
                + "s to load; they finish loading in the background.");
        } catch (ExecutionException e) {
            // Already logged by the store; the join retries the load.
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        this.store.join(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.store.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (this.settings.get().ignorePlacedFor().isZero()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID world = event.getBlockPlaced().getWorld().getUID();
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState state : multi.getReplacedBlockStates()) {
                this.placed.placed(world, state.getX(), state.getY(), state.getZ(), now);
            }
            return;
        }
        Block block = event.getBlockPlaced();
        this.placed.placed(world, block.getX(), block.getY(), block.getZ(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        StatsSettings settings = this.settings.get();
        Block block = event.getBlock();
        long window = settings.ignorePlacedFor().toMillis();
        boolean placedRecently = window > 0 && this.placed.takeIfRecent(block.getWorld().getUID(), block.getX(), block.getY(),
            block.getZ(), System.currentTimeMillis(), window);
        Player player = event.getPlayer();
        if (placedRecently || !counts(player)) {
            return;
        }
        Material type = block.getType();
        if (settings.ignored(type.getKey().getKey()) || (!settings.countInstantBlocks() && type.getHardness() <= 0)) {
            return;
        }
        this.store.record(player.getUniqueId(), ONE_BLOCK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }

    /** Placed blocks pushed by a piston stay "placed" at their new position; blocks the piston breaks are gone. */
    private void moved(List<Block> blocks, BlockFace direction) {
        if (blocks.isEmpty() || this.settings.get().ignorePlacedFor().isZero()) {
            return;
        }
        UUID world = blocks.getFirst().getWorld().getUID();
        List<int[]> positions = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                this.placed.forget(world, block.getX(), block.getY(), block.getZ());
            } else {
                positions.add(new int[] {block.getX(), block.getY(), block.getZ()});
            }
        }
        this.placed.moved(world, positions, direction.getModX(), direction.getModY(), direction.getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Mob)) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer == null || !counts(killer)) {
            return;
        }
        this.store.record(killer.getUniqueId(), ONE_MOB);
    }

    /** Creative and spectator players never count (instant breaking and kills). */
    private static boolean counts(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }
}
