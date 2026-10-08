package dev.siftvanilla.build;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Wall;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds (or removes) the SiftVanilla spawn platform: a floating 41x41 stone platform centred on 0, 0 with a wall
 * railing, invisible light blocks, a dark 7x7 AFK pad on the north side and a chiseled centre stone. Every chunk is
 * edited on its own region thread with explicit block states and no physics, so nothing crosses a region.
 */
public final class SiftBuild extends JavaPlugin {

    static final int R = 20;
    static final int AFK_MIN_X = -3;
    static final int AFK_MAX_X = 3;
    static final int AFK_MIN_Z = -19;
    static final int AFK_MAX_Z = -16;

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
            Commands.literal("siftbuild")
                .requires(source -> source.getSender().hasPermission("siftbuild.use"))
                .then(Commands.literal("probe").executes(ctx -> {
                    probe(ctx.getSource().getSender());
                    return 1;
                }))
                .then(Commands.literal("spawn").then(Commands.argument("y", IntegerArgumentType.integer(64, 300)).executes(ctx -> {
                    apply(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "y"), false);
                    return 1;
                })))
                .then(Commands.literal("clear").then(Commands.argument("y", IntegerArgumentType.integer(64, 300)).executes(ctx -> {
                    apply(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "y"), true);
                    return 1;
                })))
                .build(), "Builds the SiftVanilla spawn platform"));
    }

    private World overworld() {
        return Bukkit.getWorlds().getFirst();
    }

    /** Reports the highest terrain block inside the platform's footprint. */
    private void probe(CommandSender sender) {
        World world = overworld();
        List<int[]> chunks = chunks();
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicInteger highest = new AtomicInteger(Integer.MIN_VALUE);
        for (int[] chunk : chunks) {
            Bukkit.getRegionScheduler().execute(this, world, chunk[0], chunk[1], () -> {
                world.getChunkAt(chunk[0], chunk[1]);
                for (int x = Math.max(-R, chunk[0] << 4); x <= Math.min(R, (chunk[0] << 4) + 15); x++) {
                    for (int z = Math.max(-R, chunk[1] << 4); z <= Math.min(R, (chunk[1] << 4) + 15); z++) {
                        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                        highest.accumulateAndGet(y, Math::max);
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    sender.sendMessage("SiftBuild: highest terrain under the platform is y=" + highest.get());
                }
            });
        }
    }

    /** Places (or removes) every block of the platform, chunk by chunk on each chunk's own region thread. */
    private void apply(CommandSender sender, int floorY, boolean clear) {
        World world = overworld();
        Map<Long, Map<Location, BlockData>> byChunk = plan(world, floorY, clear);
        AtomicInteger remaining = new AtomicInteger(byChunk.size());
        AtomicInteger placed = new AtomicInteger();
        Map<String, String> failures = new ConcurrentHashMap<>();
        for (Map.Entry<Long, Map<Location, BlockData>> entry : byChunk.entrySet()) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = (int) (long) entry.getKey();
            Bukkit.getRegionScheduler().execute(this, world, cx, cz, () -> {
                try {
                    world.getChunkAt(cx, cz);
                    for (Map.Entry<Location, BlockData> block : entry.getValue().entrySet()) {
                        block.getKey().getBlock().setBlockData(block.getValue(), false);
                        placed.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    failures.put(cx + "," + cz, e.toString());
                }
                if (remaining.decrementAndGet() == 0) {
                    sender.sendMessage("SiftBuild: " + (clear ? "cleared " : "placed ") + placed.get() + " blocks in "
                        + byChunk.size() + " chunks" + (failures.isEmpty() ? "" : ", failures: " + failures));
                    if (!clear && failures.isEmpty()) {
                        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                            world.setSpawnLocation(new Location(world, 0.5, floorY + 1, 0.5, 0f, 0f));
                            sender.sendMessage("SiftBuild: world spawn set to 0.5 " + (floorY + 1) + " 0.5");
                        });
                    }
                }
            });
        }
    }

    private static List<int[]> chunks() {
        java.util.ArrayList<int[]> list = new java.util.ArrayList<>();
        for (int cx = Math.floorDiv(-R, 16); cx <= Math.floorDiv(R, 16); cx++) {
            for (int cz = Math.floorDiv(-R, 16); cz <= Math.floorDiv(R, 16); cz++) {
                list.add(new int[] {cx, cz});
            }
        }
        return list;
    }

    static boolean ring(int x, int z) {
        return Math.max(Math.abs(x), Math.abs(z)) == R;
    }

    static boolean afkPad(int x, int z) {
        return x >= AFK_MIN_X && x <= AFK_MAX_X && z >= AFK_MIN_Z && z <= AFK_MAX_Z;
    }

    static Material floor(int x, int z) {
        int m = Math.max(Math.abs(x), Math.abs(z));
        if (m == R) {
            return Material.STONE_BRICKS;
        }
        if (x == 0 && z == 0) {
            return Material.CHISELED_STONE_BRICKS;
        }
        if (afkPad(x, z)) {
            return Material.POLISHED_DEEPSLATE;
        }
        if (m == 14 || (m <= 2 && (Math.abs(x) == 2 || Math.abs(z) == 2))) {
            return Material.POLISHED_ANDESITE;
        }
        return Material.SMOOTH_STONE;
    }

    private static Map<Long, Map<Location, BlockData>> plan(World world, int y, boolean clear) {
        Map<Long, Map<Location, BlockData>> byChunk = new HashMap<>();
        BlockData air = Material.AIR.createBlockData();
        BlockData light = Bukkit.createBlockData("minecraft:light[level=15]");
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                Map<Location, BlockData> chunk = byChunk.computeIfAbsent(
                    ((long) Math.floorDiv(x, 16) << 32) | (Math.floorDiv(z, 16) & 0xffffffffL), k -> new HashMap<>());
                chunk.put(new Location(world, x, y, z), clear ? air : floor(x, z).createBlockData());
                for (int dy = 1; dy <= 4; dy++) {
                    BlockData data = air;
                    if (!clear && dy == 1 && ring(x, z)) {
                        data = wall(x, z);
                    } else if (!clear && dy == 1 && Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 3) {
                        data = light;
                    }
                    chunk.put(new Location(world, x, y + dy, z), data);
                }
            }
        }
        return byChunk;
    }

    /** A railing wall with its connections set explicitly (no physics runs when it is placed). */
    private static BlockData wall(int x, int z) {
        Wall wall = (Wall) Material.STONE_BRICK_WALL.createBlockData();
        boolean north = ring(x, z - 1) && Math.abs(z - 1) <= R && Math.abs(x) <= R;
        boolean south = ring(x, z + 1) && Math.abs(z + 1) <= R && Math.abs(x) <= R;
        boolean west = ring(x - 1, z) && Math.abs(x - 1) <= R && Math.abs(z) <= R;
        boolean east = ring(x + 1, z) && Math.abs(x + 1) <= R && Math.abs(z) <= R;
        wall.setHeight(BlockFace.NORTH, north ? Wall.Height.LOW : Wall.Height.NONE);
        wall.setHeight(BlockFace.SOUTH, south ? Wall.Height.LOW : Wall.Height.NONE);
        wall.setHeight(BlockFace.WEST, west ? Wall.Height.LOW : Wall.Height.NONE);
        wall.setHeight(BlockFace.EAST, east ? Wall.Height.LOW : Wall.Height.NONE);
        boolean straight = (north && south && !east && !west) || (east && west && !north && !south);
        wall.setUp(!straight || (Math.floorMod(x + z, 6) == 0));
        return wall;
    }
}
