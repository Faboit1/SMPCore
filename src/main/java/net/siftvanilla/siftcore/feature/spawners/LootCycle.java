package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.economy.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * The loot cycles. A light global timer (once a second, touching no world state) picks the loaded chunks with
 * spawners whose cycle is due and hands each to its region thread. There, one nearby-player query covers the whole
 * chunk; every spawner with a counted player within the activation radius makes {@code stack x kills-per-cycle}
 * virtual kills worth of loot and XP ({@link LootMath}, a few random draws per drop whatever the stack), and the
 * results are added to the storages in one pass under the economy lock, as much as fits. Cycles also keep the
 * blocks set up (shown mob, never spawning) and notice spawners whose block is gone.
 */
final class LootCycle {

    private final Scheduler scheduler;
    private final Ledger ledger;
    private final Setting<SpawnersSettings> settings;
    private final SpawnerRegistry registry;
    private final SpawnerService service;
    private final AfkStatus afk;
    private final VanishStatus vanish;
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong itemsMade = new AtomicLong();
    private final AtomicLong itemsLost = new AtomicLong();

    /** Loot one spawner made in a cycle, before it is fitted into the storage. */
    private record Made(ManagedSpawner spawner, Map<String, Long> loot, long xp) {
    }

    LootCycle(Scheduler scheduler, Ledger ledger, Setting<SpawnersSettings> settings, SpawnerRegistry registry,
              SpawnerService service, AfkStatus afk, VanishStatus vanish) {
        this.scheduler = scheduler;
        this.ledger = ledger;
        this.settings = settings;
        this.registry = registry;
        this.service = service;
        this.afk = afk;
        this.vanish = vanish;
    }

    /** The global timer: dispatches the chunks whose cycle is due. */
    void tick() {
        long now = System.currentTimeMillis();
        for (SpawnerPos.ChunkKey chunk : this.registry.due(now, this.settings.get().interval().toMillis(), false)) {
            dispatch(chunk);
        }
    }

    /** Runs a cycle in every loaded chunk with spawners right away; returns how many chunks. */
    int runAllNow() {
        long now = System.currentTimeMillis();
        List<SpawnerPos.ChunkKey> chunks = this.registry.due(now, this.settings.get().interval().toMillis(), true);
        for (SpawnerPos.ChunkKey chunk : chunks) {
            dispatch(chunk);
        }
        return chunks.size();
    }

    private void dispatch(SpawnerPos.ChunkKey chunk) {
        World world = Bukkit.getWorld(chunk.world());
        if (world == null) {
            this.registry.unloaded(chunk);
            return;
        }
        this.scheduler.region(world, chunk.x(), chunk.z(), () -> run(world, chunk));
    }

    /** One cycle of one chunk, on its region thread. */
    void run(World world, SpawnerPos.ChunkKey chunk) {
        if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
            this.registry.unloaded(chunk);
            return;
        }
        List<ManagedSpawner> spawners = this.registry.inChunk(chunk);
        if (spawners.isEmpty()) {
            this.registry.unloaded(chunk);
            return;
        }
        this.cycles.incrementAndGet();
        SpawnersSettings s = this.settings.get();
        int radius = s.radius();
        double reach = (double) radius * radius;
        List<Location> players = nearbyPlayers(world, chunk, spawners, radius, s);
        RandomGenerator random = ThreadLocalRandom.current();
        List<Made> made = new ArrayList<>();
        List<ManagedSpawner> orphans = new ArrayList<>();
        for (ManagedSpawner spawner : spawners) {
            if (spawner.removed()) {
                continue;
            }
            SpawnerPos pos = spawner.pos;
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (block.getType() != Material.SPAWNER) {
                orphans.add(spawner);
                continue;
            }
            if (spawner.syncedRadius() != radius) {
                this.service.setupBlock(block, spawner.mob, radius);
                spawner.syncedRadius(radius);
            }
            MobDef def = s.mob(spawner.mob);
            if (def == null || !def.enabled() || !anyWithin(players, pos, reach)) {
                continue;
            }
            long kills = LootMath.kills(spawner.stack(), def.killsPerCycle(), random);
            if (kills <= 0) {
                continue;
            }
            Map<String, Long> loot = LootMath.roll(def.drops(), kills, random);
            long xp = kills * (long) def.xpPerKill();
            made.add(new Made(spawner, loot, xp));
        }
        if (!made.isEmpty()) {
            store(made, s);
        }
        for (ManagedSpawner orphan : orphans) {
            this.service.orphan(orphan, "its block is no longer a spawner");
        }
    }

    /** Locations of the counted players near the chunk's spawners (one query for the whole chunk). */
    private List<Location> nearbyPlayers(World world, SpawnerPos.ChunkKey chunk, List<ManagedSpawner> spawners, int radius,
                                         SpawnersSettings s) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (ManagedSpawner spawner : spawners) {
            minY = Math.min(minY, spawner.pos.y());
            maxY = Math.max(maxY, spawner.pos.y());
        }
        Location center = new Location(world, (chunk.x() << 4) + 8, (minY + maxY) / 2.0 + 0.5, (chunk.z() << 4) + 8);
        double horizontal = radius + 12;
        double vertical = (maxY - minY) / 2.0 + radius + 1;
        Collection<Player> near = center.getNearbyPlayers(horizontal, vertical, horizontal, player -> counts(player, s));
        List<Location> spots = new ArrayList<>(near.size());
        for (Player player : near) {
            spots.add(player.getLocation());
        }
        return spots;
    }

    /** Whether a player keeps spawners going: alive, not spectating, and (by config) not vanished or AFK. */
    boolean counts(Player player, SpawnersSettings s) {
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (!s.countVanished() && this.vanish.vanished(player.getUniqueId())) {
            return false;
        }
        return s.countAfk() || !this.afk.afk(player.getUniqueId());
    }

    private static boolean anyWithin(List<Location> players, SpawnerPos pos, double reach) {
        for (Location location : players) {
            if (pos.distanceSquared(location.getX(), location.getY(), location.getZ()) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a chunk's loot to the storages in one pass under the economy lock, as much as fits. Owners of storages that
     * are full and still owed a Full storage alert (filled this cycle, or earlier while the alert was held back) are
     * asked about afterwards ({@link FullAlerts}).
     */
    private void store(List<Made> made, SpawnersSettings s) {
        long now = System.currentTimeMillis();
        Set<UUID> owed = new HashSet<>();
        this.ledger.locked(() -> {
            for (Made m : made) {
                ManagedSpawner spawner = m.spawner();
                if (spawner.removed()) {
                    continue;
                }
                int stack = spawner.stack();
                long capacity = StorageMath.capacity(stack, s.slots(spawner.mob));
                long free = Math.max(0, capacity - spawner.storage.used());
                Map<String, Long> fitted = StorageMath.fit(m.loot(), free);
                long total = 0;
                for (long amount : m.loot().values()) {
                    total = StorageMath.saturatingAdd(total, amount);
                }
                long added = 0;
                for (Map.Entry<String, Long> entry : fitted.entrySet()) {
                    spawner.storage.add(entry.getKey(), entry.getValue());
                    added += entry.getValue();
                }
                long xpCap = StorageMath.xpCapacity(stack, s.xpPerSpawner());
                long xp = StorageMath.addXp(spawner.xp(), m.xp(), xpCap);
                boolean changed = added > 0 || xp != spawner.xp();
                if (xp != spawner.xp()) {
                    spawner.xp(xp);
                } else if (added > 0) {
                    spawner.touch();
                }
                if (changed) {
                    spawner.dirty(true);
                }
                spawner.active(now, added < total);
                if (spawner.alertOwed()) {
                    owed.add(spawner.owner);
                }
                this.itemsMade.addAndGet(added);
                this.itemsLost.addAndGet(total - added);
            }
            return null;
        });
        if (!owed.isEmpty()) {
            this.service.storageFull(owed);
        }
    }

    long cycles() {
        return this.cycles.get();
    }

    long itemsMade() {
        return this.itemsMade.get();
    }

    long itemsLost() {
        return this.itemsLost.get();
    }
}
