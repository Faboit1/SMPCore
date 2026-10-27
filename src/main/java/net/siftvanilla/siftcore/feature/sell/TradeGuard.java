package net.siftvanilla.siftcore.feature.sell;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Crafter;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Closes the villager money printer: villagers buy cheap items (sticks, rotten flesh) for emeralds and sell goods for
 * emeralds, so with trading halls the server would pay for things that cost almost nothing. Everything a villager or
 * wandering trader hands out carries a marker in its custom data, which makes it a changed item that can't be sold
 * (only plain items sell). Marked items still work everywhere else, including paying villagers.
 * <p>
 * The marker follows the item: what is crafted, smelted, cooked, cut, smithed, repaired or ground from a marked item
 * is marked too, and a marked block that is placed (also as its block form: redstone dust as wire, a banner on a
 * wall) is remembered in the chunk's data so that whatever it drops later, mined, sheared, blown up, washed away,
 * pushed off by a piston or fallen, is marked again. Marked blocks moved by pistons, falling or stripped keep their
 * mark; endermen don't pick them up. Buckets follow too: emptying a marked bucket of fish leaves a marked bucket and
 * a marked fish (whose drops are marked), filling or milking with a marked bucket gives a marked filled bucket and
 * drinking marked milk leaves a marked bucket (cauldrons included). Where the game makes a new plain container with no
 * event that could keep the marker (dispensers, recipes and fuel that leave a bucket behind, catching a fish), marked
 * buckets can't be used. Trade results are marked when a villager gains a trade, when a player opens its trades, and
 * a trade whose result somehow is not marked is refused.
 * <p>
 * What crops and plants grow into is not followed: growing is farming, not the traded item.
 */
final class TradeGuard implements Listener {

    /** The marker on items from villager trades. */
    static final NamespacedKey MARKER = Objects.requireNonNull(NamespacedKey.fromString("siftcore:traded"));
    /** Where a chunk remembers its marked blocks: "x,y,z,minecraft:block" per block. */
    static final NamespacedKey BLOCKS = Objects.requireNonNull(NamespacedKey.fromString("siftcore:traded_blocks"));

    /** How long after a marked bucket was emptied the fish it lets out may appear (it appears in the same tick). */
    private static final long BUCKET_SPAWN_WINDOW = 1_000_000_000L;

    private record ChunkKey(UUID world, long chunk) {
    }

    /** A block position where a marked bucket was just emptied. */
    private record SpawnKey(UUID world, long block) {
    }

    private final BooleanSupplier enabled;
    private final Predicate<Entity> owns;
    /** Marked blocks of loaded chunks (read from the chunk on first use; only touched on the chunk's thread). */
    private final Map<ChunkKey, Map<Long, String>> chunks = new ConcurrentHashMap<>();
    /** Where marked buckets were just emptied, so the fish they let out can be marked. */
    private final Map<SpawnKey, Long> bucketSpawns = new ConcurrentHashMap<>();

    /**
     * @param enabled whether marking is on ({@code mark-villager-trades})
     * @param owns    whether the current thread may touch an entity
     */
    TradeGuard(BooleanSupplier enabled, Predicate<Entity> owns) {
        this.enabled = enabled;
        this.owns = owns;
    }

    // ------------------------------------------------------------------ the marker

    /** True when the item came from a villager trade (or was made from one). */
    static boolean marked(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    /** A marked copy of a stack (the stack itself when it is empty or already marked). */
    static ItemStack mark(ItemStack stack) {
        if (stack == null || stack.isEmpty() || marked(stack)) {
            return stack;
        }
        ItemStack copy = stack.clone();
        copy.editPersistentDataContainer(pdc -> pdc.set(MARKER, PersistentDataType.BYTE, (byte) 1));
        return copy;
    }

    private static boolean anyMarked(ItemStack[] stacks) {
        if (stacks == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (marked(stack)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ trades

    /** A copy of a trade whose result is marked. */
    static MerchantRecipe marked(MerchantRecipe recipe) {
        MerchantRecipe copy = new MerchantRecipe(mark(recipe.getResult()), recipe.getUses(), recipe.getMaxUses(),
            recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier(), recipe.getDemand(),
            recipe.getSpecialPrice(), recipe.shouldIgnoreDiscounts());
        copy.setIngredients(recipe.getIngredients());
        return copy;
    }

    /** Marks every trade result of a villager or wandering trader that is not marked yet. Its own thread. */
    private void markTrades(AbstractVillager merchant) {
        List<MerchantRecipe> recipes = merchant.getRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            if (!marked(recipe.getResult())) {
                merchant.setRecipe(i, marked(recipe));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAcquire(VillagerAcquireTradeEvent event) {
        if (this.enabled.getAsBoolean() && !marked(event.getRecipe().getResult())) {
            event.setRecipe(marked(event.getRecipe()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (this.enabled.getAsBoolean() && event.getRightClicked() instanceof AbstractVillager merchant
            && this.owns.test(merchant)) {
            markTrades(merchant);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (this.enabled.getAsBoolean() && event.getInventory() instanceof MerchantInventory merchant
            && merchant.getMerchant() instanceof AbstractVillager villager && this.owns.test(villager)) {
            markTrades(villager);
        }
    }

    /** The last line: a trade that would hand out an unmarked result is refused (the player keeps their items). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!this.enabled.getAsBoolean() || marked(event.getTrade().getResult())) {
            return;
        }
        event.setCancelled(true);
        if (this.owns.test(event.getMerchant())) {
            markTrades(event.getMerchant());
        }
    }

    // ------------------------------------------------------------------ what is made from marked items

    /**
     * True when a marked ingredient would leave a container behind (a milk, water or lava bucket leaves a bucket).
     * The game makes that container new and plain, so such a recipe is not allowed with marked ingredients.
     */
    private static boolean leavesPlainContainer(ItemStack[] stacks) {
        if (stacks == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (marked(stack) && stack.getType().isItem() && stack.getType().getCraftingRemainingItem() != null) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        if (leavesPlainContainer(inventory.getMatrix())) {
            inventory.setResult(null);
        } else if (!marked(result) && anyMarked(inventory.getMatrix())) {
            inventory.setResult(mark(result));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();
        if (leavesPlainContainer(inventory.getMatrix())) {
            event.setCancelled(true);
            return;
        }
        if (result != null && !result.isEmpty() && !marked(result) && anyMarked(inventory.getMatrix())) {
            inventory.setResult(mark(result));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (!(event.getBlock().getState(false) instanceof Crafter crafter)) {
            return;
        }
        ItemStack[] contents = crafter.getInventory().getContents();
        if (leavesPlainContainer(contents)) {
            event.setCancelled(true);
            return;
        }
        if (!marked(event.getResult()) && anyMarked(contents)) {
            event.setResult(mark(event.getResult()));
        }
    }

    /** A marked lava bucket burnt as fuel would leave a plain bucket in the furnace. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent event) {
        ItemStack fuel = event.getFuel();
        if (marked(fuel) && fuel.getType().getCraftingRemainingItem() != null) {
            event.setCancelled(true);
        }
    }

    /** Furnaces, smokers, blast furnaces and campfires. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (marked(event.getSource()) && !marked(event.getResult())) {
            event.setResult(mark(event.getResult()));
        }
    }

    /**
     * Smithing tables, anvils, grindstones and the other result screens: a result made with a marked item is marked
     * (a grindstone joining a marked sword with a worn one gives a marked sword, not a plain new one).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareResult(PrepareInventoryResultEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty() || marked(result)) {
            return;
        }
        Inventory inventory = event.getInventory();
        int resultSlot = resultSlot(inventory.getType());
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            // The result slot may still hold the result of the inputs before; only the inputs count.
            if (slot != resultSlot && marked(contents[slot])) {
                event.setResult(mark(result));
                return;
            }
        }
    }

    /** The result slot of a result screen's own inventory, or -1 when it has none there. */
    private static int resultSlot(InventoryType type) {
        return switch (type) {
            case ANVIL, GRINDSTONE, CARTOGRAPHY -> 2;
            case SMITHING, LOOM -> 3;
            case STONECUTTER -> 1;
            default -> -1;
        };
    }

    /**
     * Stonecutters have no event that can change their result, so taking a result made from a marked block marks it
     * on the way out. Only plain pickups are allowed then: shift-clicking repeats the take without asking again, and
     * number keys or dropping take the result by other paths, so those are refused for marked blocks.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStonecutter(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.STONECUTTER || event.getClickedInventory() != top || event.getSlot() != 1) {
            return;
        }
        if (!marked(top.getItem(0))) {
            return;
        }
        InventoryAction action = event.getAction();
        if (action != InventoryAction.PICKUP_ALL && action != InventoryAction.PICKUP_HALF
            && action != InventoryAction.PICKUP_ONE && action != InventoryAction.PICKUP_SOME) {
            event.setCancelled(true);
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result != null && !result.isEmpty() && !marked(result)) {
            event.setCurrentItem(mark(result));
        }
    }

    // ------------------------------------------------------------------ placed marked blocks

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static ChunkKey key(Block block) {
        return new ChunkKey(block.getWorld().getUID(), Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4));
    }

    /** The chunk's marked blocks, read from its data the first time (the block's thread). */
    private Map<Long, String> blocks(Block block) {
        return this.chunks.computeIfAbsent(key(block), k -> read(block.getChunk()));
    }

    private static Map<Long, String> read(Chunk chunk) {
        Map<Long, String> map = new ConcurrentHashMap<>();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        List<String> entries = pdc.get(BLOCKS, PersistentDataType.LIST.strings());
        if (entries == null) {
            return map;
        }
        for (String entry : entries) {
            String[] parts = entry.split(",", 4);
            if (parts.length != 4) {
                continue;
            }
            try {
                map.put(pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])), parts[3]);
            } catch (NumberFormatException ignored) {
                // A damaged entry is dropped.
            }
        }
        return map;
    }

    private void write(Block block, Map<Long, String> map) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        if (map.isEmpty()) {
            pdc.remove(BLOCKS);
            return;
        }
        List<String> entries = new ArrayList<>(map.size());
        for (Map.Entry<Long, String> entry : map.entrySet()) {
            long packed = entry.getKey();
            int x = (int) (packed >> 38);
            int z = (int) (packed << 26 >> 38);
            int y = (int) (packed << 52 >> 52);
            entries.add(x + "," + y + "," + z + "," + entry.getValue());
        }
        pdc.set(BLOCKS, PersistentDataType.LIST.strings(), entries);
    }

    /** Remembers a marked block. */
    private void track(Block block, Material type) {
        Map<Long, String> map = blocks(block);
        map.put(pack(block.getX(), block.getY(), block.getZ()), type.getKey().asString());
        write(block, map);
    }

    /** Forgets a block; returns true when it was a marked block of that type. */
    private boolean untrack(Block block, Material expected) {
        Map<Long, String> map = blocks(block);
        if (map.isEmpty()) {
            return false;
        }
        String known = map.remove(pack(block.getX(), block.getY(), block.getZ()));
        if (known == null) {
            return false;
        }
        write(block, map);
        return expected == null || known.equals(expected.getKey().asString());
    }

    private boolean tracked(Block block) {
        Map<Long, String> map = blocks(block);
        if (map.isEmpty()) {
            return false;
        }
        String known = map.get(pack(block.getX(), block.getY(), block.getZ()));
        return known != null && known.equals(block.getType().getKey().asString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        this.chunks.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getChunkKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        boolean marked = this.enabled.getAsBoolean() && marked(event.getItemInHand());
        List<Block> placed = new ArrayList<>();
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState state : multi.getReplacedBlockStates()) {
                placed.add(state.getBlock());
            }
        } else {
            placed.add(event.getBlockPlaced());
        }
        Material item = event.getItemInHand().getType();
        for (Block block : placed) {
            // Whatever stood here before is gone: a new block always starts fresh.
            untrack(block, null);
            if (marked && placedFrom(block, item)) {
                track(block, block.getType());
            }
        }
    }

    /**
     * Whether a block is the item that was placed: the same type, or the item's own block form (redstone dust is
     * redstone wire, a banner on a wall is a wall banner). Seeds and other plants that grow are not followed into
     * their crop: what a crop yields once it grew is farming, not the traded item.
     */
    private static boolean placedFrom(Block block, Material item) {
        Material type = block.getType();
        if (type == item) {
            return true;
        }
        BlockData data = block.getBlockData();
        return !(data instanceof Ageable) && data.getPlacementMaterial() == item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.isDropItems()) {
            untrack(event.getBlock(), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (untrack(event.getBlock(), event.getBlockState().getType())) {
            for (Item item : event.getItems()) {
                item.setItemStack(mark(item.getItemStack()));
            }
        }
    }

    /** Water washing a block away, or a piston breaking it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBrokenByBlock(BlockBreakBlockEvent event) {
        if (untrack(event.getBlock(), event.getBlock().getType())) {
            List<ItemStack> drops = event.getDrops();
            drops.replaceAll(TradeGuard::mark);
        }
    }

    /** A block popping off (its support was removed), a wither breaking it, and other direct destruction. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        Block block = event.getBlock();
        if (!tracked(block)) {
            return;
        }
        untrack(block, null);
        if (event.willDrop()) {
            event.setWillDrop(false);
            dropMarked(block, block.getDrops());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList());
    }

    /** Marked blocks in a blast break with marked drops instead of the explosion's own drops. */
    private void explode(List<Block> blocks) {
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!tracked(block)) {
                continue;
            }
            iterator.remove();
            untrack(block, null);
            Collection<ItemStack> drops = block.getDrops();
            block.setType(Material.AIR);
            dropMarked(block, drops);
        }
    }

    private static void dropMarked(Block block, Collection<ItemStack> drops) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty()) {
                block.getWorld().dropItemNaturally(center, mark(drop));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        untrack(event.getBlock(), null);
    }

    /** Coral dying, ice melting and the like: a marked block that changes stays marked as what it became. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (!tracked(block)) {
            return;
        }
        untrack(block, null);
        Material next = event.getNewState().getType();
        if (!next.isAir()) {
            track(block, next);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtendMoved(BlockPistonExtendEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetractMoved(BlockPistonRetractEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }

    /** Marked blocks a piston moves keep their mark at their new place (broken ones are marked when they drop). */
    private void moved(List<Block> blocks, BlockFace direction) {
        Map<Block, Material> moving = new HashMap<>();
        for (Block block : blocks) {
            if (block.getPistonMoveReaction() != PistonMoveReaction.BREAK && tracked(block)) {
                moving.put(block, block.getType());
            }
        }
        for (Block block : moving.keySet()) {
            untrack(block, null);
        }
        moving.forEach((block, type) -> track(block.getRelative(direction), type));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock falling) {
            if (event.getTo().isAir()) {
                // A marked block starts to fall: the falling block carries the mark.
                if (tracked(block)) {
                    untrack(block, null);
                    falling.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
                }
            } else if (falling.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) {
                // It lands: the new block is marked.
                untrack(block, null);
                track(block, event.getTo());
            }
            return;
        }
        if (event.getTo().isAir() && tracked(block)) {
            // Endermen, zombies at doors, ravagers: mobs don't take marked blocks away.
            event.setCancelled(true);
        }
    }

    /** A marked block that turns into another block (a log stripped with an axe) stays marked as what it became. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangedBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock || event.getTo().isAir()) {
            return;
        }
        Block block = event.getBlock();
        if (event.getTo() != block.getType() && tracked(block)) {
            untrack(block, null);
            track(block, event.getTo());
        }
    }

    /** Shearing a marked block (a pumpkin into a carved pumpkin) gives marked drops. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShearBlock(PlayerShearBlockEvent event) {
        if (tracked(event.getBlock())) {
            event.getDrops().replaceAll(TradeGuard::mark);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShearedBlock(PlayerShearBlockEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.PUMPKIN && tracked(block)) {
            untrack(block, null);
            track(block, Material.CARVED_PUMPKIN);
        }
    }

    // ------------------------------------------------------------------ buckets

    /** Any bucket, empty or filled (with water, lava, milk, powder snow or a fish). */
    private static boolean bucket(ItemStack stack) {
        return stack != null && stack.getType().getKey().getKey().endsWith("bucket");
    }

    private static ItemStack held(Player player, EquipmentSlot hand) {
        return hand == null ? null : player.getInventory().getItem(hand);
    }

    /**
     * Emptying a marked bucket (a bucket of cod from a fisherman, a bucket of tropical fish from a wandering trader)
     * leaves a marked empty bucket, and a fish that came out of it is marked too.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!marked(held(event.getPlayer(), event.getHand()))) {
            return;
        }
        ItemStack result = event.getItemStack();
        if (result != null && !result.isEmpty()) {
            event.setItemStack(mark(result));
        }
        long now = System.nanoTime();
        for (Block block : List.of(event.getBlock(), event.getBlockClicked().getRelative(event.getBlockFace()))) {
            this.bucketSpawns.put(new SpawnKey(block.getWorld().getUID(), pack(block.getX(), block.getY(), block.getZ())), now);
        }
    }

    /** Filling a marked bucket (water, lava, powder snow, milking a cow) gives a marked filled bucket. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!marked(held(event.getPlayer(), event.getHand()))) {
            return;
        }
        ItemStack result = event.getItemStack();
        if (result != null && !result.isEmpty()) {
            event.setItemStack(mark(result));
        }
    }

    /** A fish can't be caught with a marked bucket, and a fish from one can't be caught again (the new bucket would be plain). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (marked(event.getOriginalBucket()) || event.getEntity().getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    /** The fish (or axolotl) a marked bucket let out is marked; what it drops when killed is marked. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketSpawn(CreatureSpawnEvent event) {
        if (this.bucketSpawns.isEmpty()) {
            return;
        }
        // The fish appears in the same tick, at the block the water went to (or right next to it after its offset).
        Location at = event.getLocation();
        UUID world = at.getWorld().getUID();
        long now = System.nanoTime();
        Long found = null;
        for (int dy : new int[] {0, -1, 1}) {
            found = this.bucketSpawns.get(new SpawnKey(world, pack(at.getBlockX(), at.getBlockY() + dy, at.getBlockZ())));
            if (found != null) {
                break;
            }
        }
        long when = found == null ? Long.MIN_VALUE : found;
        // Forget this bucket (every position it was noted at) and anything too old.
        this.bucketSpawns.values().removeIf(time -> time == when || now - time > BUCKET_SPAWN_WINDOW);
        if (found != null && now - when <= BUCKET_SPAWN_WINDOW) {
            event.getEntity().getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMarkedDeath(EntityDeathEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) {
            event.getDrops().replaceAll(TradeGuard::mark);
        }
    }

    /** Drinking marked milk leaves a marked bucket (the game would hand back a new, plain one). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() == Material.MILK_BUCKET && item.getAmount() == 1 && marked(item)
            && event.getPlayer().getGameMode() != GameMode.CREATIVE && event.getReplacement() == null) {
            event.setReplacement(mark(ItemStack.of(Material.BUCKET)));
        }
    }

    /**
     * Dispensers swap a bucket for a new, plain one with no event that could keep the marker, so they don't use
     * marked buckets. (Cauldrons go through the bucket fill and empty events above.)
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (bucket(event.getItem()) && marked(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDrop(EntityDropItemEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof FallingBlock) && !(entity instanceof Hanging)) {
            return;
        }
        if (!entity.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) {
            return;
        }
        Item item = event.getItemDrop();
        ItemStack stack = item.getItemStack();
        if (entity instanceof Hanging hanging && !isHangingItem(hanging, stack)) {
            // An item frame dropping the item it held: that item keeps its own state.
            return;
        }
        item.setItemStack(mark(stack));
    }

    private static boolean isHangingItem(Hanging hanging, ItemStack stack) {
        Material type = stack.getType();
        return switch (hanging.getType()) {
            case ITEM_FRAME -> type == Material.ITEM_FRAME;
            case GLOW_ITEM_FRAME -> type == Material.GLOW_ITEM_FRAME;
            case PAINTING -> type == Material.PAINTING;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (this.enabled.getAsBoolean() && marked(event.getItemStack())) {
            event.getEntity().getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
