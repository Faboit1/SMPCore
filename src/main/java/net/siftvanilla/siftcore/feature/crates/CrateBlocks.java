package net.siftvanilla.siftcore.feature.crates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Blocks that act as crates: right-click shows the crate (sneak + right-click opens keys straight away when
 * quick-open is on and the player's Sneak + right-click a crate setting is not the crate window), left-click shows
 * its rewards. They come from {@code features/crates.yml} and from
 * {@code /crates block add} (stored in {@code crate_blocks}); a file entry wins when both name the same block.
 * Crate blocks can't be broken, blown up, burnt, pushed by pistons or changed by mobs.
 * <p>
 * The lookup is an immutable snapshot replaced on every change, so the event handlers (on many region threads)
 * read it without locking and return at once when there are no crate blocks.
 */
final class CrateBlocks implements Listener {

    /** A crate block: which crate, and whether it comes from the file. */
    record Entry(String crate, boolean fromFile) {
    }

    /** Opens the crate screens; implemented by the feature. {@code block} is the crate block clicked. */
    interface Actions {
        void view(Player player, String crate, BlockKey block);

        void preview(Player player, String crate, BlockKey block);

        void quickOpen(Player player, String crate);
    }

    private static final Duration CLICK_GAP = Duration.ofMillis(250);

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final Actions actions;
    private final Map<BlockKey, String> placed = new ConcurrentHashMap<>();
    private final Set<BlockKey> saving = ConcurrentHashMap.newKeySet();
    private final String upsert;
    private volatile Map<BlockKey, Entry> index = Map.of();

    CrateBlocks(Services services, Setting<CratesSettings> settings, Actions actions) {
        this.services = services;
        this.settings = settings;
        this.actions = actions;
        this.upsert = services.database().dialect().replaceUpsert("crate_blocks", new String[] {"world", "x", "y", "z"},
            new String[] {"crate", "placed_by", "placed_at"});
    }

    /** Loads the blocks placed in-game. Blocking; call at startup. */
    void load() throws Exception {
        Map<BlockKey, String> rows = this.services.database().read(c -> {
            Map<BlockKey, String> result = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT world, x, y, z, crate FROM crate_blocks")) {
                while (rs.next()) {
                    result.put(new BlockKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), rs.getString(5));
                }
            }
            return result;
        }).get();
        this.placed.clear();
        this.placed.putAll(rows);
        rebuild();
    }

    /** Rebuilds the lookup from the file and the placed blocks (after a reload or a change). */
    synchronized void rebuild() {
        CratesSettings settings = this.settings.get();
        Map<BlockKey, Entry> next = new LinkedHashMap<>();
        for (Crate crate : settings.crates()) {
            for (BlockKey block : crate.blocks()) {
                next.putIfAbsent(block, new Entry(crate.id(), true));
            }
        }
        for (Map.Entry<BlockKey, String> entry : this.placed.entrySet()) {
            if (settings.crate(entry.getValue()) != null && Bukkit.getWorld(entry.getKey().world()) != null) {
                next.putIfAbsent(entry.getKey(), new Entry(entry.getValue(), false));
            }
        }
        this.index = Map.copyOf(next);
    }

    Map<BlockKey, Entry> all() {
        return this.index;
    }

    /** Placed blocks whose crate is no longer configured or whose world is not loaded (kept, but inactive). */
    List<BlockKey> inactive() {
        List<BlockKey> result = new ArrayList<>();
        for (BlockKey key : this.placed.keySet()) {
            if (!this.index.containsKey(key)) {
                result.add(key);
            }
        }
        return result;
    }

    Entry at(BlockKey key) {
        return this.index.get(key);
    }

    static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private boolean isCrate(Block block) {
        Map<BlockKey, Entry> current = this.index;
        return !current.isEmpty() && current.containsKey(key(block));
    }

    // ------------------------------------------------------------------ staff changes

    /** The outcome of adding or removing a block. */
    enum Change {
        DONE, TAKEN, NOT_CRATE, IN_FILE, BUSY, FAILED
    }

    /** Makes a block a crate. Completes after the row is stored and the block is live. */
    CompletableFuture<Change> add(BlockKey block, String crate, String actor) {
        Entry existing = this.index.get(block);
        if (existing != null) {
            return CompletableFuture.completedFuture(Change.TAKEN);
        }
        if (!this.saving.add(block)) {
            return CompletableFuture.completedFuture(Change.BUSY);
        }
        long now = System.currentTimeMillis();
        String by = actor.length() > 36 ? actor.substring(0, 36) : actor;
        return this.services.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.upsert)) {
                ps.setString(1, block.world());
                ps.setInt(2, block.x());
                ps.setInt(3, block.y());
                ps.setInt(4, block.z());
                ps.setString(5, crate);
                ps.setString(6, by);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return null;
        }).handle((ignored, error) -> {
            this.saving.remove(block);
            if (error != null) {
                this.services.plugin().getLogger().log(java.util.logging.Level.WARNING, "Storing the crate block " + block + " failed", error);
                return Change.FAILED;
            }
            this.placed.put(block, crate);
            rebuild();
            this.services.audit().record(actor, "crates.block.add", crate, block.toString());
            return Change.DONE;
        });
    }

    /** Stops a placed block from being a crate. Completes after the row is deleted. */
    CompletableFuture<Change> remove(BlockKey block, String actor) {
        Entry existing = this.index.get(block);
        if (existing == null && !this.placed.containsKey(block)) {
            return CompletableFuture.completedFuture(Change.NOT_CRATE);
        }
        if (existing != null && existing.fromFile()) {
            return CompletableFuture.completedFuture(Change.IN_FILE);
        }
        if (!this.saving.add(block)) {
            return CompletableFuture.completedFuture(Change.BUSY);
        }
        String crate = this.placed.get(block);
        return this.services.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM crate_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, block.world());
                ps.setInt(2, block.x());
                ps.setInt(3, block.y());
                ps.setInt(4, block.z());
                ps.executeUpdate();
            }
            return null;
        }).handle((ignored, error) -> {
            this.saving.remove(block);
            if (error != null) {
                this.services.plugin().getLogger().log(java.util.logging.Level.WARNING, "Removing the crate block " + block + " failed", error);
                return Change.FAILED;
            }
            this.placed.remove(block);
            rebuild();
            this.services.audit().record(actor, "crates.block.remove", crate, block.toString());
            return Change.DONE;
        });
    }

    // ------------------------------------------------------------------ events

    /**
     * First half of a click on a crate block, before anything else sees it: the block never acts as itself (no chest
     * opening, no mining start), and the click counts as handled, so protections that skip handled clicks (the spawn
     * area) neither refuse it nor tell the player they can't use the block.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractFirst(PlayerInteractEvent event) {
        if (crateClick(event) != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /**
     * Second half, after every other plugin: the crate acts unless something refused the player in between (a frozen
     * player, for example, has the item use denied too). The item in hand is never used on a crate block.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Entry entry = crateClick(event);
        if (entry == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        boolean refused = event.useItemInHand() == Event.Result.DENY;
        event.setUseItemInHand(Event.Result.DENY);
        if (refused || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(CratesFeature.PERMISSION_USE)) {
            return;
        }
        if (!this.services.cooldowns().tryUse(player.getUniqueId(), "crates:block", CLICK_GAP).isZero()) {
            return;
        }
        BlockKey clicked = key(event.getClickedBlock());
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            this.actions.preview(player, entry.crate(), clicked);
        } else if (CratePlayerSettings.quickOpens(this.services.settings().get(player, CratePlayerSettings.QUICK_OPEN),
            this.settings.get().quickOpen(), player.isSneaking())) {
            this.actions.quickOpen(player, entry.crate());
        } else {
            this.actions.view(player, entry.crate(), clicked);
        }
    }

    /** The crate block a left or right click is on, or null. */
    private Entry crateClick(PlayerInteractEvent event) {
        Map<BlockKey, Entry> current = this.index;
        Block block = event.getClickedBlock();
        if (current.isEmpty() || block == null) {
            return null;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return null;
        }
        return current.get(key(block));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Map<BlockKey, Entry> current = this.index;
        if (current.isEmpty()) {
            return;
        }
        Entry entry = current.get(key(event.getBlock()));
        if (entry == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.hasPermission(CratesFeature.PERMISSION_ADMIN)) {
            Crate crate = this.settings.get().crate(entry.crate());
            this.services.messenger().send(player, CratesMessages.BLOCK_PROTECTED,
                net.siftvanilla.siftcore.core.text.Arg.text("name", crate == null ? entry.crate() : crate.name()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!this.index.isEmpty()) {
            event.blockList().removeIf(this::isCrate);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!this.index.isEmpty()) {
            event.blockList().removeIf(this::isCrate);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!this.index.isEmpty() && event.getBlocks().stream().anyMatch(this::isCrate)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!this.index.isEmpty() && event.getBlocks().stream().anyMatch(this::isCrate)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isCrate(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (isCrate(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
