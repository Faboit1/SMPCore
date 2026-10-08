package net.siftvanilla.siftcore.feature.afk;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

/**
 * Feeds what players do into their {@link PlayerAfk}, and keeps the AFK zone peaceful when {@code zone.safe} is on.
 * Activity handlers run at MONITOR and also see cancelled events (a refused click is still someone at the keyboard),
 * except movement, whose cancelled packets never happened. Every handler does a little arithmetic and a map lookup.
 */
final class AfkListener implements Listener {

    /** Labels of /afk itself: typing it must not count as coming back before the command toggles. */
    private static final Set<String> AFK_LABELS = Set.of("afk", "siftcore:afk");
    /** Damage that still reaches players in the zone: falling out of the world and kill commands. */
    private static final Set<EntityDamageEvent.DamageCause> UNSTOPPABLE = Set.of(EntityDamageEvent.DamageCause.VOID,
        EntityDamageEvent.DamageCause.KILL, EntityDamageEvent.DamageCause.SUICIDE, EntityDamageEvent.DamageCause.WORLD_BORDER);

    private final AfkService service;
    private final Supplier<AfkSettings> settings;
    private final Messenger messenger;

    AfkListener(AfkService service, Supplier<AfkSettings> settings, Messenger messenger) {
        this.service = service;
        this.settings = settings;
        this.messenger = messenger;
    }

    // ------------------------------------------------------------------ joining and leaving

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.service.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.service.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerAfk state = this.service.state(event.getPlayer().getUniqueId());
        if (state != null) {
            state.rebase();
        }
    }

    // ------------------------------------------------------------------ activity

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        this.service.moved(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PlayerAfk state = this.service.state(player.getUniqueId());
        if (state != null) {
            String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
            this.service.observed(player, state.chat(text, System.currentTimeMillis(), this.service.timing()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String line = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        if (AFK_LABELS.contains(label)) {
            return;
        }
        Player player = event.getPlayer();
        PlayerAfk state = this.service.state(player.getUniqueId());
        if (state != null) {
            this.service.observed(player, state.command(line, System.currentTimeMillis(), this.service.timing()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            // Pressure plates, tripwires and farmland: stepping on them can be automated.
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        String target = block == null ? "air" : block.getX() + "," + block.getY() + "," + block.getZ();
        interaction(player, "use:" + event.getAction() + ":" + event.getHand() + ":" + target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        interaction(event.getPlayer(), "entity:" + event.getHand() + ":" + position(event.getRightClicked()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        interaction(event.getPlayer(), "attack:" + position(event.getAttacked()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            interaction(player, "inventory:" + event.getView().getType() + ":" + event.getRawSlot() + ":" + event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDialogClick(PlayerCustomClickEvent event) {
        if (event.getCommonConnection() instanceof PlayerGameConnection connection) {
            Player player = connection.getPlayer();
            interaction(player, "dialog:" + event.getIdentifier().asString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItem(PlayerItemHeldEvent event) {
        interaction(event.getPlayer(), "held:" + event.getNewSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        interaction(event.getPlayer(), "swap");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSign(SignChangeEvent event) {
        Block block = event.getBlock();
        interaction(event.getPlayer(), "sign:" + block.getX() + "," + block.getY() + "," + block.getZ() + ":"
            + event.lines().hashCode());
    }

    /**
     * Records an interaction. The signature includes the player's view direction, so the same click on the same spot
     * from the same angle (an auto-clicker) repeats it, while a player who looks somewhere else makes a new one.
     */
    private void interaction(Player player, String signature) {
        PlayerAfk state = this.service.state(player.getUniqueId());
        if (state == null) {
            return;
        }
        Location eye = player.getLocation();
        String full = signature + "@" + ActivityClassifier.bucket(eye.getYaw(), eye.getPitch());
        this.service.observed(player, state.interaction(full, System.currentTimeMillis(), this.service.timing()));
    }

    private static String position(Entity entity) {
        Location location = entity.getLocation();
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    // ------------------------------------------------------------------ being pushed

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerAfk state = this.service.state(player.getUniqueId());
            if (state != null) {
                state.pushed(System.currentTimeMillis());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerAfk state = this.service.state(event.getPlayer().getUniqueId());
        if (state != null) {
            state.pushed(System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ a safe zone

    /**
     * With {@code zone.safe}, players in the zone take no damage (except the void and kill commands), and players in
     * the zone can't hurt anyone outside it either. Runs at LOW like spawn protection, so combat tags and stats never
     * see the refused hits. Uses the zone flags the players' own threads keep, so it never reads another region.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!this.settings.get().zoneSafe()) {
            return;
        }
        if (event.getEntity() instanceof Player victim && !UNSTOPPABLE.contains(event.getCause())
            && this.service.inside(victim.getUniqueId())) {
            event.setCancelled(true);
            Player attacker = attacker(event);
            if (attacker != null && !attacker.equals(victim)) {
                refuse(attacker);
            }
            return;
        }
        Player attacker = attacker(event);
        if (attacker != null && this.service.inside(attacker.getUniqueId())) {
            event.setCancelled(true);
            refuse(attacker);
        }
    }

    private void refuse(Player attacker) {
        PlayerAfk state = this.service.state(attacker.getUniqueId());
        if (state != null && state.noticeDue(System.currentTimeMillis(), 1_000)) {
            this.messenger.send(attacker, AfkMessages.ZONE_NO_FIGHTING);
        }
    }

    /** The player behind a hit: the attacker, or the shooter of a projectile. Null for anything else. */
    private static Player attacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
