package net.siftvanilla.siftcore.feature.spawn;

import com.destroystokyo.paper.MaterialTags;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;

/**
 * Keeps the spawn area safe: no building, no use of blocks other than the allowed ones (buttons, doors, crate
 * blocks...), no PvP, no damage of any kind to players, no natural mob spawning, no explosions, fire spread or
 * liquids flowing in, and no editing item frames or armor stands. Builders with {@link #BYPASS} may build and use
 * everything (they are still protected from damage). Every handler runs at LOW priority so other features that
 * listen with {@code ignoreCancelled} (combat tags, crates, stats) never see refused actions, and every check is
 * plain arithmetic on coordinates.
 */
final class SpawnProtection implements Listener {

    static final String BYPASS = "siftcore.spawn.bypass";
    private static final long FEEDBACK_INTERVAL_MILLIS = 1_000;

    private record Allowed(List<String> keys, Set<Material> materials) {
    }

    private final Supplier<ProtectedRegion> region;
    private final Supplier<SpawnSettings> settings;
    private final Messenger messenger;
    private final Map<UUID, Long> lastFeedback = new ConcurrentHashMap<>();
    private volatile Allowed allowed = new Allowed(List.of(), Set.of());

    SpawnProtection(Supplier<ProtectedRegion> region, Supplier<SpawnSettings> settings, Messenger messenger) {
        this.region = region;
        this.settings = settings;
        this.messenger = messenger;
    }

    // ------------------------------------------------------------------ helpers

    private boolean inside(Location location) {
        return location != null && location.getWorld() != null
            && this.region.get().contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    private boolean inside(Block block) {
        return this.region.get().contains(block.getWorld().getName(), block.getX() + 0.5, block.getY(), block.getZ() + 0.5);
    }

    private static boolean bypass(Player player) {
        return player != null && player.hasPermission(BYPASS);
    }

    private void deny(Player player, MessageKey key) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = this.lastFeedback.get(player.getUniqueId());
        if (last == null || now - last >= FEEDBACK_INTERVAL_MILLIS) {
            this.lastFeedback.put(player.getUniqueId(), now);
            this.messenger.send(player, key);
        }
    }

    /** Block types that may be used inside spawn, resolved from the config's names and tags. */
    Set<Material> allowedMaterials() {
        List<String> keys = this.settings.get().protection().allowedInteractions();
        Allowed current = this.allowed;
        if (current.keys() != keys) {
            current = new Allowed(keys, resolve(keys));
            this.allowed = current;
        }
        return current.materials();
    }

    /** Resolves {@code oak_door} or {@code #minecraft:doors}; unknown keys resolve to nothing. */
    static Set<Material> resolve(List<String> keys) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String key : keys) {
            if (key.startsWith("#")) {
                NamespacedKey tagKey = NamespacedKey.fromString(key.substring(1));
                Tag<Material> tag = tagKey == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, tagKey, Material.class);
                if (tag != null) {
                    result.addAll(tag.getValues());
                }
            } else {
                Material material = Material.matchMaterial(key);
                if (material != null && material.isBlock()) {
                    result.add(material);
                }
            }
        }
        return result;
    }

    /** Whether a key from the config names an existing block or block tag (used when parsing the config). */
    static boolean exists(String key) {
        if (key.startsWith("#")) {
            NamespacedKey tagKey = NamespacedKey.fromString(key.substring(1));
            return tagKey != null && Bukkit.getTag(Tag.REGISTRY_BLOCKS, tagKey, Material.class) != null;
        }
        Material material = Material.matchMaterial(key);
        return material != null && material.isBlock();
    }

    /**
     * Whether refusing a right click is worth explaining: the block reacts to clicks, or the item would have placed
     * or changed something. Uses Paper's (deprecated, approximate) interactable flag purely as this hint; the
     * protection itself refuses every right click on a block that is not allowed.
     */
    @SuppressWarnings("deprecation")
    private static boolean noticeable(Material clicked, ItemStack hand) {
        if (clicked.isInteractable()) {
            return true;
        }
        if (hand == null || hand.getType().isAir()) {
            return false;
        }
        Material type = hand.getType();
        return type.isBlock() || type == Material.BUCKET || type.name().endsWith("_BUCKET") || type == Material.FLINT_AND_STEEL
            || type == Material.FIRE_CHARGE || MaterialTags.SPAWN_EGGS.isTagged(type);
    }

    /** The entity responsible for damage: the shooter of a projectile, the causing entity, or the direct damager. */
    private static Entity attacker(EntityDamageByEntityEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null) {
            return causing;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return event.getDamager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.lastFeedback.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ building

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inside(event.getBlock()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inside(event.getBlock()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (inside(event.getBlock()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (inside(event.getBlock()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (inside(event.getEntity().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (inside(event.getEntity().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (!inside(event.getBlock())) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            if (!bypass(player)) {
                event.setCancelled(true);
                deny(player, SpawnMessages.BUILD_DENIED);
            }
        } else if (!(event.getEntity() instanceof FallingBlock)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ using blocks and entities

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (block == null || (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL)) {
            return;
        }
        Player player = event.getPlayer();
        if (!inside(block) || bypass(player)) {
            return;
        }
        Material type = block.getType();
        if (allowedMaterials().contains(type)) {
            return;
        }
        event.setCancelled(true);
        if (action == Action.RIGHT_CLICK_BLOCK && noticeable(type, event.getItem())) {
            deny(player, SpawnMessages.USE_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        boolean guarded = entity instanceof ItemFrame || entity instanceof ArmorStand
            || (entity instanceof Vehicle && entity instanceof InventoryHolder);
        if (guarded && inside(entity.getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.USE_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (inside(event.getRightClicked().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.USE_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!inside(event.getEntity().getLocation())) {
            return;
        }
        Player remover = event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player ? player : null;
        if (!bypass(remover)) {
            event.setCancelled(true);
            deny(remover, SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!inside(event.getVehicle().getLocation())) {
            return;
        }
        Entity attacker = event.getAttacker();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        Player player = attacker instanceof Player p ? p : null;
        if (!bypass(player)) {
            event.setCancelled(true);
            deny(player, SpawnMessages.BUILD_DENIED);
        }
    }

    // ------------------------------------------------------------------ damage and PvP

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (victim instanceof Player victimPlayer) {
            if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) {
                return;
            }
            if (inside(victimPlayer.getLocation())) {
                event.setCancelled(true);
                if (event instanceof EntityDamageByEntityEvent byEntity && attacker(byEntity) instanceof Player attacker
                    && !attacker.equals(victimPlayer)) {
                    deny(attacker, SpawnMessages.PVP_DENIED);
                }
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEntity && attacker(byEntity) instanceof Player attacker
                && !attacker.equals(victimPlayer) && inside(attacker.getLocation())) {
                event.setCancelled(true);
                deny(attacker, SpawnMessages.PVP_DENIED);
            }
            return;
        }
        if ((victim instanceof ItemFrame || victim instanceof ArmorStand) && inside(victim.getLocation())) {
            Player attacker = event instanceof EntityDamageByEntityEvent byEntity && attacker(byEntity) instanceof Player p ? p : null;
            if (!bypass(attacker)) {
                event.setCancelled(true);
                deny(attacker, SpawnMessages.USE_DENIED);
            }
        }
    }

    /** Whether any of the effects hurts or hinders (poison, slowness, weakness, harming...). */
    static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Harmful splash potions thrown by a player don't touch other players inside spawn (no PvP), wherever they were
     * thrown from; a harmful potion that bursts inside spawn affects no other player at all.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player thrower) || !harmful(potion.getEffects())) {
            return;
        }
        boolean burstInside = inside(potion.getLocation());
        boolean refused = false;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && !victim.equals(thrower) && (burstInside || inside(victim.getLocation()))) {
                event.setIntensity(victim, 0);
                refused = true;
            }
        }
        if (refused) {
            deny(thrower, SpawnMessages.PVP_DENIED);
        }
    }

    /** Lingering clouds left by a player: the same rule as splash potions. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower) || !harmful(cloudEffects(cloud))) {
            return;
        }
        boolean cloudInside = inside(cloud.getLocation());
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim && !victim.equals(thrower)
            && (cloudInside || inside(victim.getLocation())));
    }

    private static List<PotionEffect> cloudEffects(AreaEffectCloud cloud) {
        List<PotionEffect> effects = new ArrayList<>();
        PotionType base = cloud.getBasePotionType();
        if (base != null) {
            effects.addAll(base.getPotionEffects());
        }
        if (cloud.hasCustomEffects()) {
            effects.addAll(cloud.getCustomEffects());
        }
        return effects;
    }

    /** A fishing rod can't pull another player out of (or around inside) spawn. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || !(event.getCaught() instanceof Player caught)
            || caught.equals(event.getPlayer())) {
            return;
        }
        if (inside(caught.getLocation()) || inside(event.getPlayer().getLocation())) {
            event.setCancelled(true);
            event.getHook().remove();
            deny(event.getPlayer(), SpawnMessages.PVP_DENIED);
        }
    }

    // ------------------------------------------------------------------ world: mobs, explosions, fire, liquids

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (this.settings.get().protection().blockedSpawnReasons().contains(event.getSpawnReason()) && inside(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::inside);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::inside);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (inside(event.getBlock()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer(), SpawnMessages.BUILD_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (Tag.FIRE.isTagged(event.getNewState().getType()) && inside(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (inside(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (inside(event.getToBlock()) && !inside(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        if (inside(piston)) {
            return;
        }
        BlockFace direction = event.getDirection();
        if (inside(piston.getRelative(direction))) {
            event.setCancelled(true);
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (inside(moved) || inside(moved.getRelative(direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (inside(event.getBlock())) {
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (inside(moved)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
