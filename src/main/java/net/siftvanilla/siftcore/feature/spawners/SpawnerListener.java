package net.siftvanilla.siftcore.feature.spawners;

import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import java.util.List;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * World events for spawners. Checks that may refuse an action run at HIGHEST; the change itself is recorded at
 * MONITOR, once no other plugin can cancel the event any more, so a placement or pickup that is cancelled later can
 * never leave a record behind. Explosions, pistons and withers can't destroy or move managed spawners, and a managed
 * spawner never spawns a mob. Chunk events keep the loaded-chunk index (no world scans).
 * <p>
 * Deliberately not listened to: Paper's {@code BlockDestroyEvent}. The server only builds it while a plugin listens,
 * and it fires for every block that breaks by itself (cactus and sugar cane farms, crops, vines), so one listener
 * costs every farm on the server. The only things that reach a spawner that way are admin tools such as
 * {@code /setblock ... destroy}; the loot cycle then finds the block gone and refunds the owner.
 */
final class SpawnerListener implements Listener {

    private final SpawnerService service;
    private final SpawnerRegistry registry;
    private final WriteBehind writeBehind;
    private final StorageMenus menus;
    private final Setting<SpawnersSettings> settings;

    SpawnerListener(SpawnerService service, SpawnerRegistry registry, WriteBehind writeBehind, StorageMenus menus,
                    Setting<SpawnersSettings> settings) {
        this.service = service;
        this.registry = registry;
        this.writeBehind = writeBehind;
        this.menus = menus;
        this.settings = settings;
    }

    private boolean managed(Block block) {
        return block.getType() == Material.SPAWNER && this.registry.at(SpawnerService.pos(block)) != null;
    }

    private static boolean spawnEgg(ItemStack item) {
        return item != null && !item.isEmpty() && item.getType().getKey().getKey().endsWith("_spawn_egg");
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkPlace(BlockPlaceEvent event) {
        String mob = this.service.items().mobOf(event.getItemInHand());
        if (mob != null && event.canBuild() && !this.service.checkPlace(event.getPlayer(), event.getBlockPlaced(), mob)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        // A placement another plugin refused with setBuild(false) is undone by the server even though it is not
        // cancelled, so it must not be recorded either.
        String mob = this.service.items().mobOf(event.getItemInHand());
        if (mob != null && event.canBuild() && event.getBlockPlaced().getType() == Material.SPAWNER
            && !this.service.place(event.getPlayer(), event.getBlockPlaced(), mob)) {
            // Only when the record could not be made (the economy is paused): nothing was placed after all.
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ stacking and opening

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
            || event.getClickedBlock().getType() != Material.SPAWNER) {
            return;
        }
        ManagedSpawner spawner = this.service.at(event.getClickedBlock());
        if (spawner == null || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        // Whether opening needs sneaking follows the player's Open spawner storage with (server: the config).
        boolean requiresSneak = SpawnerPlayerSettings.requiresSneak(this.service.prefs().get(player, SpawnerPlayerSettings.OPEN_CLICK),
            this.settings.get().openRequiresSneak());
        boolean opening = main.isEmpty() && (player.isSneaking() || !requiresSneak);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (opening || off.getType() == Material.SPAWNER || spawnEgg(off) || main.getType() == Material.SPAWNER) {
                deny(event);
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (this.service.items().mobOf(main) != null) {
            deny(event);
            // A plain right-click adds what the player's Right-click with spawners adds says; sneaking does the other.
            this.service.stack(player, spawner, SpawnerPlayerSettings.wholeStack(
                this.service.prefs().get(player, SpawnerPlayerSettings.STACK_CLICK), player.isSneaking()));
        } else if (main.getType() == Material.SPAWNER) {
            deny(event);
        } else if (spawnEgg(main)) {
            deny(event);
            this.service.tell(player, SpawnersMessages.SPAWN_EGG);
        } else if (opening) {
            deny(event);
            if (this.service.allowed(player, spawner) && this.service.outOfCombat(player)) {
                this.menus.open(player, spawner, null);
            }
        }
    }

    private static void deny(PlayerInteractEvent event) {
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    // ------------------------------------------------------------------ picking up

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        ManagedSpawner spawner = this.service.at(block);
        if (spawner != null && !this.service.checkBreak(event.getPlayer(), spawner)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickUp(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        ManagedSpawner spawner = this.service.at(block);
        if (spawner == null) {
            if (this.service.pickUpNatural(event.getPlayer(), block)) {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
            return;
        }
        if (this.service.pickUp(event.getPlayer(), spawner)) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        } else {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ protection

    /** Pays out XP that waited for the player (they left before a pickup or collection reached them). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        this.service.payOutXp(event.getPlayer(), SpawnersMessages.XP_WAITING);
    }

    /** Forgets a half-confirmed give of spawners. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        this.service.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::managed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::managed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) {
        if (anyManaged(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) {
        if (anyManaged(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean anyManaged(List<Block> blocks) {
        for (Block block : blocks) {
            if (managed(block)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) {
        if (managed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ never spawn

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preSpawn(PreSpawnerSpawnEvent event) {
        if (this.registry.at(SpawnerService.pos(event.getSpawnerLocation())) != null) {
            event.setCancelled(true);
            event.setShouldAbortSpawn(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void spawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        if (spawner != null && this.registry.at(SpawnerService.pos(spawner.getLocation())) != null) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ chunks

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        SpawnerPos.ChunkKey key = new SpawnerPos.ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (this.registry.hasChunk(key)) {
            this.registry.loaded(key, System.currentTimeMillis() + this.settings.get().interval().toMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        SpawnerPos.ChunkKey key = new SpawnerPos.ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (this.registry.hasChunk(key)) {
            this.registry.unloaded(key);
            this.writeBehind.flush(this.registry.inChunk(key));
        }
    }
}
