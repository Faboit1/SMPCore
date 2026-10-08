package net.siftvanilla.siftcore.feature.rtp;

import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;
import net.siftvanilla.siftcore.feature.spawn.WorldBorders;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

/**
 * Finds a safe random spot in a region's ring without ever generating terrain and without blocking any world
 * thread: each attempt picks a random column, loads its chunk with {@code getChunkAtAsync(x, z, false)} (an
 * ungenerated chunk comes back as null and simply counts as a failed attempt), then checks a few columns of that
 * chunk on the thread that owns it. Attempts run one after another, so one search never loads more than one chunk
 * at a time.
 */
final class RtpSearch {

    /** A search that has not finished after this long gives up (a stuck chunk load must not lock a player out). */
    private static final long TIMEOUT_SECONDS = 30;
    /** Nether caverns: below the bedrock roof and above the lava sea. */
    private static final int NETHER_TOP = 120;
    private static final int NETHER_BOTTOM = 32;
    /** Random columns tried to find a candidate outside spawn and inside the border before giving up on an attempt. */
    private static final int CANDIDATE_TRIES = 16;

    private static final Set<Material> HAZARDS = EnumSet.of(Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS,
        Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.COBWEB, Material.POINTED_DRIPSTONE,
        Material.END_PORTAL, Material.NETHER_PORTAL, Material.END_GATEWAY, Material.CHORUS_PLANT, Material.CHORUS_FLOWER,
        Material.TNT);
    private static final Set<Material> WATERY = EnumSet.of(Material.WATER, Material.BUBBLE_COLUMN, Material.SEAGRASS,
        Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT);

    private final Scheduler scheduler;
    private final SpawnArea spawn;
    private final AtomicLong chunksLoaded = new AtomicLong();
    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong found = new AtomicLong();

    RtpSearch(Scheduler scheduler, SpawnArea spawn) {
        this.scheduler = scheduler;
        this.spawn = spawn;
    }

    long chunksLoaded() {
        return this.chunksLoaded.get();
    }

    long searches() {
        return this.searches.get();
    }

    long found() {
        return this.found.get();
    }

    /** What one search needs, captured once on the calling thread. */
    private record Job(World world, RtpSettings.Region region, double maxRadius, BorderSpec border, int margin, int attempts,
                       int spotsPerChunk, float yaw, CompletableFuture<Location> result) {
    }

    /**
     * Starts a search. The future completes with a safe location, or null when every attempt failed (or exceptionally
     * on timeout). Callable from any thread.
     */
    CompletableFuture<Location> find(World world, RtpSettings.Region region, RtpSettings settings, float yaw) {
        this.searches.incrementAndGet();
        CompletableFuture<Location> result = new CompletableFuture<>();
        Optional<BorderSpec> border = WorldBorders.live(world.getName());
        double maxRadius = RtpGeometry.usableMax(region.maxRadius(), border.orElse(null), region.centerX(), region.centerZ(),
            settings.borderMargin());
        if (maxRadius <= region.minRadius()) {
            result.complete(null);
            return result;
        }
        Job job = new Job(world, region, maxRadius, border.orElse(null), settings.borderMargin(), settings.maxAttempts(),
            settings.spotsPerChunk(), yaw, result);
        attempt(job, 1);
        return result.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void attempt(Job job, int number) {
        if (job.result().isDone()) {
            return;
        }
        if (number > job.attempts()) {
            job.result().complete(null);
            return;
        }
        RingSampler.Point candidate = candidate(job);
        if (candidate == null) {
            attempt(job, number + 1);
            return;
        }
        int chunkX = candidate.x() >> 4;
        int chunkZ = candidate.z() >> 4;
        job.world().getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                attempt(job, number + 1);
                return;
            }
            this.chunksLoaded.incrementAndGet();
            if (Bukkit.isOwnedByCurrentRegion(job.world(), chunkX, chunkZ)) {
                checkChunk(job, chunk, candidate, number);
            } else {
                this.scheduler.region(job.world(), chunkX, chunkZ, () -> checkChunk(job, chunk, candidate, number));
            }
        });
    }

    /** A random column inside the ring, inside the border margin and outside spawn; null if none was found quickly. */
    private RingSampler.Point candidate(Job job) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        RtpSettings.Region region = job.region();
        for (int i = 0; i < CANDIDATE_TRIES; i++) {
            RingSampler.Point point = RingSampler.sample(random, region.centerX(), region.centerZ(), region.minRadius(), job.maxRadius());
            if (allowedColumn(job, point.x(), point.z())) {
                return point;
            }
        }
        return null;
    }

    private boolean allowedColumn(Job job, int x, int z) {
        RtpSettings.Region region = job.region();
        if (!RingSampler.inRing(x, z, region.centerX(), region.centerZ(), region.minRadius(), job.maxRadius())) {
            return false;
        }
        if (job.border() != null && !job.border().inside(x + 0.5, z + 0.5, job.margin())) {
            return false;
        }
        return !this.spawn.contains(new Location(job.world(), x + 0.5, 64, z + 0.5));
    }

    /** Runs on the thread that owns the chunk: tries the candidate column, then random columns of the same chunk. */
    private void checkChunk(Job job, Chunk chunk, RingSampler.Point candidate, int number) {
        if (job.result().isDone()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (int i = 0; i < job.spotsPerChunk(); i++) {
            int x = i == 0 ? candidate.x() : baseX + random.nextInt(16);
            int z = i == 0 ? candidate.z() : baseZ + random.nextInt(16);
            if (i > 0 && !allowedColumn(job, x, z)) {
                continue;
            }
            Location spot = check(job, x, z);
            if (spot != null) {
                this.found.incrementAndGet();
                job.result().complete(spot);
                return;
            }
        }
        this.scheduler.async(() -> attempt(job, number + 1));
    }

    /** The landing location in one column, or null when it is not safe. */
    private Location check(Job job, int x, int z) {
        World world = job.world();
        SafeSpot.Column column = y -> classify(world.getBlockAt(x, y, z));
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        OptionalInt feet;
        if (world.getEnvironment() == World.Environment.NETHER) {
            int top = Math.min(NETHER_TOP, minY + world.getLogicalHeight() - 4);
            feet = SafeSpot.cavern(column, top, Math.max(NETHER_BOTTOM, minY + 1));
        } else {
            feet = SafeSpot.surface(column, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), minY, maxY);
        }
        if (feet.isEmpty()) {
            return null;
        }
        Location location = new Location(world, x + 0.5, feet.getAsInt(), z + 0.5, job.yaw(), 0f);
        if (!world.getWorldBorder().isInside(location) || this.spawn.contains(location)) {
            return null;
        }
        return location;
    }

    /** What a block means for landing. Must run on the thread that owns the block. */
    static Surface classify(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return Surface.CLEAR;
        }
        if (HAZARDS.contains(type) || Tag.FIRE.isTagged(type) || Tag.CAMPFIRES.isTagged(type)) {
            return Surface.HAZARD;
        }
        if (WATERY.contains(type) || block.isLiquid() || block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return Surface.LIQUID;
        }
        if (block.isPassable()) {
            return Surface.CLEAR;
        }
        if (Tag.LEAVES.isTagged(type) || Tag.FENCES.isTagged(type) || Tag.WALLS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) {
            return Surface.OTHER;
        }
        return block.isSolid() ? Surface.SOLID : Surface.OTHER;
    }
}
