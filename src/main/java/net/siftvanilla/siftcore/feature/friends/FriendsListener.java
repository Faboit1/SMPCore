package net.siftvanilla.siftcore.feature.friends;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Task;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Loads a player's friends before they enter the world and lets them go after they leave, starts the join flow, and
 * opens the player card on a sneak right-click.
 * <ul>
 *   <li>Pre-login (only when the login is allowed): a loading node is installed and its load starts without
 *       blocking. The load is a write unit, so it is ordered with every friends change and changes that arrive while
 *       it runs are replayed. Settings are not loaded yet at this point and are not read here.</li>
 *   <li>The join flow (rank limit, login summary, join alerts) starts once both the join and the load are done.</li>
 *   <li>Quit: the node is kept for the memory grace (a quick rejoin needs no load), the leave alert is scheduled with
 *       the settings as they are now (the core forgets them right after this listener).</li>
 * </ul>
 */
final class FriendsListener implements Listener {

    /** Card cooldown key in the core cooldowns. */
    static final String CARD_COOLDOWN = "friends:card";

    private final Services services;
    private final Setting<FriendsSettings> settings;
    private final FriendService service;
    private final FriendGraph graph;
    private final FriendStore store;
    private final Presence presence;
    private final RankLimits limits;
    private final FriendViews views;
    private final FriendLinks links;
    private final Logger logger;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Set<UUID> started = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Task> evictions = new ConcurrentHashMap<>();

    FriendsListener(Services services, Setting<FriendsSettings> settings, FriendService service, Presence presence,
                    RankLimits limits, FriendViews views) {
        this.services = services;
        this.settings = settings;
        this.service = service;
        this.graph = service.graph();
        this.store = service.store();
        this.presence = presence;
        this.limits = limits;
        this.views = views;
        this.links = service.links();
        this.logger = services.plugin().getLogger();
    }

    // ------------------------------------------------------------------ loading

    /** Installs a loading node and starts its load, unless the player's node is still in memory. Safe from any thread. */
    void prepare(UUID player) {
        long load = this.graph.prepareLoad(player);
        if (load != 0) {
            load(player, load);
        }
        Task eviction = this.evictions.remove(player);
        if (eviction != null) {
            eviction.cancel();
        }
    }

    /** Runs the load {@code load} of the player's loading node; only that node takes its result. */
    private void load(UUID player, long load) {
        FriendsSettings s = this.settings.get();
        this.store.write(this.store.load(player, s.rules(), s.antiFarmRemember().toMillis())).whenComplete((snapshot, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the friends of " + player + "; retrying in a minute", error);
                this.graph.dropLoad(player, load);
                return;
            }
            if (this.graph.install(player, snapshot, load)) {
                this.limits.seed(player, snapshot.storedLimit(), snapshot.storedLabel());
                maybeReady(player);
            }
        });
    }

    /** Loads players who are online without a node (enable, or after a failed load). Blocks when {@code wait}. */
    void loadMissing(boolean wait) throws Exception {
        FriendsSettings s = this.settings.get();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (this.graph.node(id) != null) {
                continue;
            }
            if (!wait) {
                this.joinedAt.putIfAbsent(id, System.currentTimeMillis());
                prepare(id);
                continue;
            }
            this.graph.prepare(id);
            FriendGraph.Snapshot snapshot = this.store.write(this.store.load(id, s.rules(), s.antiFarmRemember().toMillis())).get();
            if (this.graph.install(id, snapshot)) {
                this.limits.seed(id, snapshot.storedLimit(), snapshot.storedLabel());
            }
            // Already online: no summary and no join alerts, but the rank limit is read.
            this.started.add(id);
            this.services.scheduler().entity(online, () -> this.limits.refresh(online), null);
        }
    }

    /**
     * Starts the join flow once the player has joined and their friends are loaded (whichever comes last). Runs on the
     * joining player's thread (join) or a database callback (load), possibly at the same time as a quit or the next
     * join: after claiming the session it checks that the session it read is still the current one, and otherwise
     * gives the claim back and starts the current session instead, so a quit in between can't leave a stale claim
     * that would swallow the next login's summary and join alerts.
     */
    private void maybeReady(UUID id) {
        Long joined = this.joinedAt.get(id);
        Player player = Bukkit.getPlayer(id);
        if (joined == null || player == null || !this.graph.isLoaded(id) || !this.started.add(id)) {
            return;
        }
        if (!joined.equals(this.joinedAt.get(id)) || Bukkit.getPlayer(id) != player) {
            this.started.remove(id);
            maybeReady(id);
            return;
        }
        this.services.scheduler().entity(player, () -> this.limits.refresh(player), null);
        this.presence.ready(player, joined);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        prepare(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Usually a no-op: the pre-login already prepared the node. It loads now when no pre-login was seen (or its
        // load failed), and it takes the node back when it is LEAVING: a second login of an online account runs its
        // pre-login before the old session quits, so that quit marked the node leaving and scheduled its eviction.
        prepare(id);
        // A join starts a new session: whatever the last one claimed is over (its quit may have raced a load).
        this.started.remove(id);
        this.joinedAt.put(id, System.currentTimeMillis());
        maybeReady(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        this.joinedAt.remove(id);
        this.started.remove(id);
        this.presence.left(player);
        long leftAt = this.graph.leave(id);
        if (leftAt > 0) {
            Task eviction = this.services.scheduler().asyncLater(() -> {
                this.evictions.remove(id);
                if (this.graph.evict(id, leftAt)) {
                    this.limits.forget(id);
                }
            }, this.settings.get().memoryGrace());
            Task previous = this.evictions.put(id, eviction);
            if (previous != null) {
                previous.cancel();
            }
        }
    }

    /**
     * The minute sweep: drops nodes of players who never joined (older than two minutes), starts loads for online
     * players who have none, and prunes the relog times, rate buckets and old tombstones.
     */
    void sweep(RateLimiter rates) {
        long now = System.currentTimeMillis();
        for (FriendGraph.Node node : this.graph.nodes()) {
            if (node.phase() != FriendGraph.Phase.LEAVING && Bukkit.getPlayer(node.id()) == null && now - node.created() > 120_000L) {
                this.graph.drop(node.id());
                this.limits.forget(node.id());
            }
        }
        try {
            loadMissing(false);
        } catch (Exception e) {
            this.logger.log(Level.WARNING, "Could not load missing friends", e);
        }
        this.presence.prune();
        rates.prune(now);
        this.graph.pruneTombs(now - this.settings.get().antiFarmRemember().toMillis());
    }

    /** Joins and loads in progress (for the self-test). */
    int pendingJoins() {
        return this.joinedAt.size();
    }

    void stop() {
        this.evictions.values().forEach(Task::cancel);
        this.evictions.clear();
        this.joinedAt.clear();
        this.started.clear();
    }

    // ------------------------------------------------------------------ sneak-click card

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player player = event.getPlayer();
        FriendsSettings s = this.settings.get();
        if (!s.sneakClick() || !player.isSneaking() || !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }
        if (target.hasMetadata("NPC") || player.getGameMode() == GameMode.SPECTATOR || target.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (this.links.combat().tagged(player.getUniqueId()) || this.links.combat().tagged(target.getUniqueId())) {
            return;
        }
        if (this.links.vanish().vanished(target.getUniqueId()) || !player.canSee(target)) {
            return;
        }
        if (!player.hasPermission(FriendCommands.PROFILE_PERMISSION) || !this.graph.isLoaded(player.getUniqueId())) {
            return;
        }
        if (!this.services.cooldowns().tryUse(player.getUniqueId(), CARD_COOLDOWN, s.sneakClickCooldown()).isZero()) {
            return;
        }
        this.views.openProfile(player, target.getUniqueId(), FriendViews.Nav.command(), null);
    }
}
