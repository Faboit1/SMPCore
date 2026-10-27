package net.siftvanilla.siftcore.feature.integrations;

import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.permission.Permissions;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Lets players with {@code siftcore.join.full} (a rank perk) or {@code siftcore.join.full.staff} join when the server
 * is full. The server decides "full" in {@link PlayerServerFullCheckEvent}, before the player exists, so their
 * permissions are not known there yet: they are read from LuckPerms at login instead (on the login thread, from the
 * user LuckPerms loads for that login) and remembered for a minute. Without LuckPerms nobody gets past a full server
 * this way. Joining this way looks like any other join to the player; it is logged.
 * <p>
 * Only nodes LuckPerms grants count: the server's "operators get it by default" does not apply before join, so an
 * operator needs the node in LuckPerms too. Vanilla's own bypass (an ops.json entry with {@code bypassesPlayerLimit})
 * is already part of the event's answer and is left alone.
 */
final class FullServerJoins implements Listener {

    static final String NODE = "siftcore.join.full";
    static final String STAFF = "siftcore.join.full.staff";
    /** How long a login's answer is kept for the full-server check that follows it. */
    private static final long REMEMBER_MILLIS = 60_000;
    /** How long a login may wait for LuckPerms to read the player. */
    private static final long LOOKUP_SECONDS = 3;

    private final Supplier<RankAccess> ranks;
    private final BooleanSupplier enabled;
    private final Logger logger;
    private final LongSupplier clock;
    /** Players who may join a full server: the node that lets them, until when (epoch millis). */
    private final Map<UUID, Allowed> allowed = new ConcurrentHashMap<>();
    /** Players already logged as let in during this login (the server checks twice per login). */
    private final Map<UUID, Boolean> logged = new ConcurrentHashMap<>();

    private record Allowed(String node, long until) {
    }

    FullServerJoins(Supplier<RankAccess> ranks, BooleanSupplier enabled, Logger logger, LongSupplier clock) {
        this.ranks = ranks;
        this.enabled = enabled;
        this.logger = logger;
        this.clock = clock;
    }

    static void declare(Permissions perms) {
        // Nobody by default, operators included: the node is read from LuckPerms at login, where server defaults don't apply.
        perms.declare(NODE, "Join when the server is full (a rank perk). Read from LuckPerms at login: only a LuckPerms grant "
            + "counts, operators included", PermissionDefault.FALSE);
        perms.declare(STAFF, "Join when the server is full, for staff. Read from LuckPerms at login: only a LuckPerms grant counts "
            + "(or bypassesPlayerLimit in ops.json, which the server checks itself)", PermissionDefault.FALSE);
    }

    /** Reads the player's nodes from LuckPerms while they log in (the login thread may wait). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            remember(event.getUniqueId(), event.getName());
        }
    }

    /** Reads whether a player who is logging in may join a full server, and remembers it for a minute. Blocks briefly. */
    void remember(UUID id, String name) {
        if (!this.enabled.getAsBoolean()) {
            return;
        }
        prune();
        RankAccess access = this.ranks.get();
        if (!access.available()) {
            return;
        }
        try {
            String node = access.permission(id, NODE).get(LOOKUP_SECONDS, TimeUnit.SECONDS) ? NODE
                : access.permission(id, STAFF).get(LOOKUP_SECONDS, TimeUnit.SECONDS) ? STAFF : null;
            if (node != null) {
                this.allowed.put(id, new Allowed(node, this.clock.getAsLong() + REMEMBER_MILLIS));
            } else {
                this.allowed.remove(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            this.logger.warning("Could not read whether " + name + " may join a full server (" + e + "); they can't this time.");
        }
    }

    /** Lets a remembered player past the player limit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFullCheck(PlayerServerFullCheckEvent event) {
        if (event.isAllowed()) {
            return;
        }
        UUID id = event.getPlayerProfile().getId();
        if (id == null || !letIn(id)) {
            return;
        }
        event.allow(true);
        Allowed why = this.allowed.get(id);
        if (why != null && this.logged.putIfAbsent(id, Boolean.TRUE) == null) {
            this.logger.info("Let " + event.getPlayerProfile().getName() + " join the full server (" + Bukkit.getOnlinePlayers().size() + "/"
                + Bukkit.getMaxPlayers() + " online; " + why.node() + ").");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** Whether a player logging in may go past a full server: their login found the node within the last minute. */
    boolean letIn(UUID player) {
        if (!this.enabled.getAsBoolean()) {
            return false;
        }
        Allowed until = this.allowed.get(player);
        return until != null && until.until() >= this.clock.getAsLong();
    }

    private void forget(UUID player) {
        this.allowed.remove(player);
        this.logged.remove(player);
    }

    /** Drops answers of logins that never finished. */
    private void prune() {
        long now = this.clock.getAsLong();
        this.allowed.entrySet().removeIf(entry -> {
            boolean old = entry.getValue().until() < now;
            if (old) {
                this.logged.remove(entry.getKey());
            }
            return old;
        });
    }
}
