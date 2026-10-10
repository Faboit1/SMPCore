package net.siftvanilla.siftcore.feature.afk;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.AfkStatusChangeEvent;
import net.siftvanilla.siftcore.api.event.AfkZoneRewardEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.StatusBars;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Point;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.AbstractNautilus;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * The AFK feature at run time: one {@link PlayerAfk} per online player fed by {@link AfkListener}, a once-a-second
 * check per player on that player's own thread (AFK status, kick, zone presence, rewards, the zone countdown on the
 * action bar or the shared boss bar), and the AFK zone itself (from the config, or set in game). What each player
 * sees follows their AFK settings ({@link AfkFeature#STATUS} and the others).
 * <p>
 * Implements {@link AfkStatus} for the rest of SiftCore and {@link AfkZoneInfo} for the shards page. Both read
 * volatile flags only, so they are cheap and safe from any thread.
 */
final class AfkService implements AfkStatus, AfkZoneInfo {

    static final String BYPASS_KICK = "siftcore.afk.bypass-kick";
    static final String TIER_PREFIX = "siftcore.afk.reward.";
    static final String COMMAND_AFK = "siftcore.command.afk";
    static final String COMMAND_ZONE = "siftcore.command.afkzone";
    static final String ADMIN = "siftcore.admin.afk";
    static final String REWARD_KIND = "afk_reward";
    private static final String COOLDOWN_KEY = "afk:zone";
    private static final long MESSAGE_HOLD_MILLIS = 2_500;
    private static final long NOTICE_MILLIS = 1_000;
    /** The zone countdown's owner name on the shared boss bar ({@link StatusBars}). */
    static final String BAR_OWNER = "afk";

    private final Services services;
    private final Setting<AfkSettings> settings;
    private final CombatTags combat;
    private final SpawnArea spawnArea;
    private final VanishStatus vanish;
    private final ZoneStore store;
    private final Logger logger;
    private final Map<UUID, PlayerAfk> players = new ConcurrentHashMap<>();
    private final ZoneSessions sessions = new ZoneSessions();
    private final Map<UUID, PendingCorners> pendingCorners = new ConcurrentHashMap<>();
    private final Object zoneLock = new Object();
    private volatile ZoneSpec override;
    private volatile ZoneBox box;
    private volatile CompletableFuture<Void> lastSave = CompletableFuture.completedFuture(null);

    AfkService(Services services, Setting<AfkSettings> settings, CombatTags combat, SpawnArea spawnArea, VanishStatus vanish,
               ZoneStore store) {
        this.services = services;
        this.settings = settings;
        this.combat = combat;
        this.spawnArea = spawnArea;
        this.vanish = vanish;
        this.store = store;
        this.logger = services.plugin().getLogger();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Reads the zone set in game, if any. Startup. */
    void loadOverride() {
        try {
            this.override = this.store.load().orElse(null);
        } catch (IOException e) {
            this.logger.severe("The AFK zone set in game could not be read (" + e.getMessage()
                + "); using the zone from features/afk.yml until /afkzone sets it again.");
        }
        refreshZone();
    }

    /** Starts tracking a player. Their thread. */
    PlayerAfk join(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        PlayerAfk state = new PlayerAfk(id, this.settings.get().activity(), now);
        PlayerAfk previous = this.players.putIfAbsent(id, state);
        if (previous != null) {
            return previous;
        }
        loadToday(state);
        return state;
    }

    /** Stops tracking a player who left. */
    void quit(UUID player) {
        this.players.remove(player);
        this.pendingCorners.remove(player);
        this.sessions.leave(player, System.currentTimeMillis());
    }

    PlayerAfk state(UUID player) {
        return this.players.get(player);
    }

    /** Reads what the player earned in the zone today, for the daily limit. */
    private void loadToday(PlayerAfk state) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        String account = state.player().toString();
        this.services.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE account = ? AND kind = ? AND ts >= ?")) {
                ps.setString(1, account);
                ps.setString(2, REWARD_KIND);
                ps.setLong(3, start);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).whenComplete((earned, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not read today's AFK zone earnings of " + account
                    + "; assuming none for the daily limit", error);
                state.daily().loaded(today, 0);
                return;
            }
            state.daily().loaded(today, earned);
        });
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    // ------------------------------------------------------------------ AfkStatus

    @Override
    public boolean afk(UUID player) {
        PlayerAfk state = this.players.get(player);
        return state != null && state.afk();
    }

    /** Online players being tracked, for /afk list and the self-test. */
    List<PlayerAfk> tracked() {
        return new ArrayList<>(this.players.values());
    }

    // ------------------------------------------------------------------ the zone

    /** The zone in use: set in game, else the config's. */
    ZoneSpec spec() {
        ZoneSpec set = this.override;
        return set != null ? set : this.settings.get().zone();
    }

    boolean overridden() {
        return this.override != null;
    }

    /** The zone as a box right now, or null when it is turned off or its world is not loaded. */
    ZoneBox box() {
        return this.box;
    }

    /** Resolves the zone again (the config, the spawn point or the zone set in game may have changed). Any thread. */
    void refreshZone() {
        AfkSettings s = this.settings.get();
        ZoneBox next = null;
        if (s.zoneEnabled()) {
            ZoneSpec spec = spec();
            World world = Bukkit.getWorld(spec.world());
            if (world != null) {
                if (spec.anchor() == ZoneSpec.Anchor.SPAWN) {
                    Location spawn = world.getSpawnLocation();
                    next = spec.resolve(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
                } else {
                    next = spec.resolve(0, 0, 0);
                }
            }
        }
        synchronized (this.zoneLock) {
            if (Objects.equals(next, this.box)) {
                return;
            }
            this.box = next;
            // Everyone re-enters on their next check, against the new box.
            this.sessions.clear();
            for (PlayerAfk state : this.players.values()) {
                state.inZone(false);
            }
        }
    }

    /** Whether a player standing here, alive and not spectating, is in the zone. Player's thread. */
    private boolean inside(Player player, Location location) {
        ZoneBox current = this.box;
        return current != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
            && current.contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    /** True when every corner of the zone lies in the protected spawn area. */
    boolean insideSpawn(ZoneBox zone) {
        World world = Bukkit.getWorld(zone.world());
        if (world == null) {
            return false;
        }
        for (double[] point : zone.cornerPoints()) {
            if (!this.spawnArea.contains(new Location(world, point[0], point[1], point[2]))) {
                return false;
            }
        }
        return true;
    }

    private String connection(UUID player) {
        return this.services.directory().connection(player);
    }

    /** Records whether the player is in the zone and tells them when that changed. Player's thread. */
    private void zone(Player player, PlayerAfk state, boolean inside, long now) {
        if (!state.inZone(inside)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (inside) {
            this.sessions.enter(id, connection(id), now);
            AfkSettings s = this.settings.get();
            this.services.messenger().send(player, AfkMessages.ZONE_ENTERED, Arg.shards("amount", shardsPerInterval(player)),
                Arg.value("time", s.interval()));
            state.holdStatus(now, MESSAGE_HOLD_MILLIS);
        } else {
            this.sessions.leave(id, now);
            hideBar(player);
            this.services.messenger().send(player, AfkMessages.ZONE_LEFT);
        }
    }

    // ------------------------------------------------------------------ the once-a-second check

    /** Runs every second on an async thread: refreshes the zone and checks every player on their own thread. */
    void tickAll() {
        refreshZone();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.services.scheduler().entity(player, () -> tick(player), null);
        }
    }

    /** The check of one player. Player's thread. */
    void tick(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        PlayerAfk state = this.players.get(id);
        if (state == null) {
            state = join(player);
        }
        long now = System.currentTimeMillis();
        AfkSettings s = this.settings.get();
        state.settings(s.activity());
        zone(player, state, inside(player, player.getLocation()), now);
        AfkClock.Change change = state.tick(now, s.timing(), !player.hasPermission(BYPASS_KICK));
        apply(player, state, change, false, now);
        if (change == AfkClock.Change.KICK) {
            return;
        }
        if (state.inZone()) {
            reward(player, state, s, now);
        } else {
            state.nextShardSeconds(-1);
            hideBar(player);
        }
    }

    private void reward(Player player, PlayerAfk state, AfkSettings s, long now) {
        UUID id = player.getUniqueId();
        LocalDate today = today();
        long cap = s.dailyCap();
        ZoneSessions.Block block;
        if (this.combat.tagged(id)) {
            block = ZoneSessions.Block.COMBAT;
        } else if (cap > 0 && !state.daily().isLoaded()) {
            block = ZoneSessions.Block.LOADING;
        } else if (cap > 0 && state.daily().earned(today) >= cap) {
            block = ZoneSessions.Block.CAPPED;
        } else {
            block = ZoneSessions.Block.NONE;
        }
        ZoneSessions.Status status = this.sessions.update(id, now, s.interval().toMillis(), block);
        if (status == null) {
            // The zone was refreshed between this player's entry and now: enter again.
            this.sessions.enter(id, connection(id), now);
            return;
        }
        state.nextShardSeconds(status.state() == ZoneSessions.State.EARNING ? seconds(status.nextInMillis()) : -1);
        if (status.due()) {
            pay(player, state, s, today, now);
            return;
        }
        AlertStyle style = s.statusEvery().isZero() ? AlertStyle.OFF : this.services.settings().get(id, AfkFeature.STATUS);
        if (style == AlertStyle.BOSSBAR) {
            showBar(player, status, s.interval().toMillis(), cap);
            return;
        }
        hideBar(player);
        if (style != AlertStyle.ACTIONBAR || this.services.teleports().pending(id) || !state.statusDue(now, s.statusEvery().toMillis())) {
            return;
        }
        switch (status.state()) {
            case EARNING -> {
                long shards = shardsPerInterval(player);
                Duration next = Duration.ofSeconds(seconds(status.nextInMillis()));
                if (shards == 1) {
                    this.services.messenger().send(player, AfkMessages.ZONE_STATUS, Arg.value("time", next));
                } else {
                    this.services.messenger().send(player, AfkMessages.ZONE_STATUS_MANY, Arg.shards("amount", shards),
                        Arg.value("time", next));
                }
            }
            case WAITING_ALT -> this.services.messenger().send(player, AfkMessages.ZONE_WAITING_ALT);
            case CAPPED -> this.services.messenger().send(player, AfkMessages.ZONE_CAPPED, Arg.shards("cap", cap));
            // The combat timer owns the action bar while tagged; nothing to show while loading.
            case COMBAT, LOADING -> {
            }
        }
    }

    /**
     * Whole seconds of a countdown, rounded to the nearest: the zone clock runs on the rhythm of the once-a-second
     * check, so the countdown goes down by exactly one each second.
     */
    static int seconds(long millis) {
        return (int) Math.max(0, (millis + 500) / 1000);
    }

    /**
     * Shows the zone countdown on the player's boss bar (the combat timer outranks it there): the time to the next
     * shard with a bar that fills towards it, or why nothing is being earned. Player's thread.
     */
    private void showBar(Player player, ZoneSessions.Status status, long intervalMillis, long cap) {
        Component text;
        float progress;
        switch (status.state()) {
            case EARNING -> {
                long shards = shardsPerInterval(player);
                Arg time = Arg.value("time", Duration.ofSeconds(seconds(status.nextInMillis())));
                text = shards == 1 ? this.services.lang().get(AfkMessages.ZONE_STATUS, time)
                    : this.services.lang().get(AfkMessages.ZONE_STATUS_MANY, Arg.shards("amount", shards), time);
                progress = barProgress(status.nextInMillis(), intervalMillis);
            }
            case WAITING_ALT -> {
                text = this.services.lang().get(AfkMessages.ZONE_WAITING_ALT);
                progress = 0f;
            }
            case CAPPED -> {
                text = this.services.lang().get(AfkMessages.ZONE_CAPPED, Arg.shards("cap", cap));
                progress = 1f;
            }
            // In combat the combat timer speaks for itself; nothing to show while today's earnings load.
            default -> {
                hideBar(player);
                return;
            }
        }
        this.services.statusBars().show(player, BAR_OWNER, new StatusBars.Bar(text, progress, BossBar.Color.PURPLE,
            BossBar.Overlay.PROGRESS, StatusBars.PRIORITY_IDLE));
    }

    /** How full the countdown bar is: the part of the interval already waited (it fills up towards the next shard). */
    static float barProgress(long nextInMillis, long intervalMillis) {
        if (intervalMillis <= 0) {
            return 1f;
        }
        return Math.clamp(1f - (float) Math.max(0, nextInMillis) / intervalMillis, 0f, 1f);
    }

    /** Takes the zone countdown off the player's boss bar, if it is there. Any thread. */
    private void hideBar(Player player) {
        if (this.services.statusBars().has(player.getUniqueId(), BAR_OWNER)) {
            this.services.statusBars().hide(player, BAR_OWNER);
        }
    }

    /** Pays one interval's shards. Player's thread. */
    private void pay(Player player, PlayerAfk state, AfkSettings s, LocalDate today, long now) {
        UUID id = player.getUniqueId();
        long cap = s.dailyCap();
        long amount = DailyCounter.allowance(cap, state.daily().earned(today), shardsPerInterval(player));
        if (amount <= 0) {
            return;
        }
        if (!new AfkZoneRewardEvent(player, amount).callEvent()) {
            return;
        }
        LedgerTx tx = LedgerTx.builder()
            .actor("system")
            .note("AFK zone")
            .source(id, Currency.SHARDS, amount, REWARD_KIND, null)
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        if (!result.success()) {
            if (this.services.debug()) {
                this.logger.info("AFK zone reward for " + player.getName() + " not paid: " + result.status());
            }
            return;
        }
        state.daily().add(today, amount);
        state.earned(amount, now);
        // The moment a shard is reached: a chime, under the player's sound settings (volume, success sounds, quiet in
        // combat).
        this.services.messenger().sounds().play(player, s.shardSound(), Feedback.SUCCESS);
        long balance = this.services.ledger().balance(id, Currency.SHARDS);
        AlertStyle payouts = this.services.settings().get(id, AfkFeature.PAYOUTS);
        if (amount == 1) {
            this.services.messenger().alert(player, payouts, AfkMessages.ZONE_EARNED_ONE, Arg.shards("balance", balance));
        } else {
            this.services.messenger().alert(player, payouts, AfkMessages.ZONE_EARNED_MANY, Arg.shards("amount", amount),
                Arg.shards("balance", balance));
        }
        if (payouts == AlertStyle.ACTIONBAR) {
            // Keep the countdown from replacing the payout line at once.
            state.holdStatus(now, MESSAGE_HOLD_MILLIS);
        }
        if (cap > 0 && state.daily().earned(today) >= cap) {
            this.services.messenger().send(player, AfkMessages.ZONE_CAPPED_NOW, Arg.shards("cap", cap));
        }
    }

    /**
     * Acts on a change of a player's AFK clock, in the styles the player chose (AFK status messages, the kick
     * warning, the welcome-back summary). Any thread (sending text and firing the event are thread-safe).
     */
    void apply(Player player, PlayerAfk state, AfkClock.Change change, boolean manual, long now) {
        UUID id = player.getUniqueId();
        switch (change) {
            case NONE -> {
            }
            case BECAME_AFK -> {
                boolean byCommand = state.manual();
                statusLine(player, state, byCommand ? AfkMessages.NOW_AFK_MANUAL : AfkMessages.NOW_AFK, byCommand, now);
                new AfkStatusChangeEvent(player, true, byCommand).callEvent();
            }
            case RETURNED -> {
                statusLine(player, state, AfkMessages.BACK, manual, now);
                PlayerAfk.Spell spell = state.lastSpell();
                if (spell != null && spell.worthTelling() && this.services.settings().get(id, AfkFeature.RETURN_SUMMARY)) {
                    Arg time = Arg.time("time", Duration.ofSeconds(Math.max(1, spell.millis() / 1000)));
                    if (spell.shards() == 1) {
                        this.services.messenger().chat(player, AfkMessages.RETURN_SUMMARY_SHARD, time);
                    } else if (spell.shards() > 1) {
                        this.services.messenger().chat(player, AfkMessages.RETURN_SUMMARY_SHARDS, time, Arg.shards("amount", spell.shards()));
                    } else {
                        this.services.messenger().chat(player, AfkMessages.RETURN_SUMMARY, time);
                    }
                }
                new AfkStatusChangeEvent(player, false, manual).callEvent();
            }
            case KICK_WARNING -> {
                Arg time = Arg.time("time", Duration.ofMillis(Math.max(1_000, state.untilKick(now, this.settings.get().timing()))));
                if (this.services.settings().get(id, AfkFeature.KICK_WARNING) == AlertStyle.TITLE) {
                    this.services.messenger().title(player, AfkMessages.KICK_WARNING_TITLE, AfkMessages.KICK_WARNING_SUBTITLE, time);
                } else {
                    this.services.messenger().send(player, AfkMessages.KICK_WARNING, time);
                }
            }
            case KICK -> kick(player, state, now);
        }
    }

    /** "You are now AFK" or "Welcome back" where the player wants it; {@code /afk} always gets an answer. */
    private void statusLine(Player player, PlayerAfk state, MessageKey key, boolean byCommand, long now) {
        AlertStyle style = statusStyle(this.services.settings().get(player.getUniqueId(), AfkFeature.STATUS_MESSAGES), byCommand);
        this.services.messenger().alert(player, style, key);
        if (style == AlertStyle.ACTIONBAR) {
            state.holdStatus(now, MESSAGE_HOLD_MILLIS);
        }
    }

    /**
     * Where an AFK status line goes: the player's choice, except that a player who used {@code /afk} always gets an
     * answer (on the action bar when they turned the lines off).
     */
    static AlertStyle statusStyle(AlertStyle chosen, boolean byCommand) {
        return byCommand && chosen == AlertStyle.OFF ? AlertStyle.ACTIONBAR : chosen;
    }

    private void kick(Player player, PlayerAfk state, long now) {
        long afkFor = state.afkSince() < 0 ? 0 : now - state.afkSince();
        this.logger.info("Kicked " + player.getName() + " for being AFK (AFK for " + Durations.format(Duration.ofMillis(afkFor)) + ").");
        this.services.audit().record("system", "afk.kick", player.getUniqueId().toString(),
            "AFK for " + Duration.ofMillis(afkFor).toSeconds() + "s");
        player.kick(this.services.lang().get(AfkMessages.KICK_REASON), PlayerKickEvent.Cause.IDLING);
    }

    // ------------------------------------------------------------------ observations from the listener

    /** A movement packet. Player's thread. */
    void moved(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerAfk state = this.players.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        Entity vehicle = player.getVehicle();
        // A mount the rider steers with the view (a horse, a camel, a happy ghast) moves like the player walking; boats,
        // minecarts, pigs and anything else carry the player.
        float vehicleYaw = vehicle == null || steered(vehicle) ? Float.NaN : vehicle.getYaw();
        long now = System.currentTimeMillis();
        AfkClock.Change change = state.move(new ActivityClassifier.Move(from.getX(), from.getY(), from.getZ(),
            to.getX(), to.getY(), to.getZ(), from.getYaw(), from.getPitch(), to.getYaw(), to.getPitch(),
            vehicleYaw, player.isInWater() || player.isInLava(), player.isGliding(), player.isRiptiding(), now),
            this.settings.get().timing());
        apply(player, state, change, false, now);
        if (event.hasChangedBlock()) {
            zone(player, state, inside(player, to), now);
        }
    }

    /**
     * True for mounts that turn and go where their rider looks and walks: horses, donkeys, mules, camels, happy ghasts
     * and nautiluses. Llamas wander on their own; boats and minecarts are carried by water, ice and rails; pigs and
     * striders only follow a stick on a fishing rod, which a script can hold.
     */
    static boolean steered(Entity vehicle) {
        return vehicle instanceof AbstractHorse && !(vehicle instanceof Llama) || vehicle instanceof HappyGhast
            || vehicle instanceof AbstractNautilus;
    }

    /** The current timing, for the listener's observations. */
    AfkClock.Timing timing() {
        return this.settings.get().timing();
    }

    /** Anything else the player did (chat, a command, an interaction). Any thread. */
    void observed(Player player, AfkClock.Change change) {
        if (change != AfkClock.Change.NONE) {
            PlayerAfk state = this.players.get(player.getUniqueId());
            if (state != null) {
                apply(player, state, change, false, System.currentTimeMillis());
            }
        }
    }

    // ------------------------------------------------------------------ /afk

    /** /afk: AFK now, or back when already AFK. Player's thread. */
    void toggle(Player player) {
        PlayerAfk state = this.players.get(player.getUniqueId());
        if (state == null) {
            state = join(player);
        }
        long now = System.currentTimeMillis();
        if (state.afk()) {
            apply(player, state, state.comeBack(now), true, now);
        } else {
            apply(player, state, state.goAfk(now, this.settings.get().timing()), true, now);
        }
    }

    // ------------------------------------------------------------------ AfkZoneInfo

    @Override
    public boolean open() {
        return this.box != null;
    }

    @Override
    public boolean inside(UUID player) {
        PlayerAfk state = this.players.get(player);
        return state != null && state.inZone();
    }

    @Override
    public long shardsPerInterval(Player player) {
        return this.settings.get().shardsFor(tier -> player.hasPermission(TIER_PREFIX + tier));
    }

    @Override
    public Duration interval() {
        return this.settings.get().interval();
    }

    @Override
    public long earnedToday(UUID player) {
        PlayerAfk state = this.players.get(player);
        return state == null ? 0 : state.daily().earned(today());
    }

    @Override
    public long dailyCap() {
        return this.settings.get().dailyCap();
    }

    @Override
    public boolean mayTeleport(Player player) {
        return player.hasPermission(COMMAND_ZONE);
    }

    /** /afkzone: teleports the player to the zone after the warmup. Player's thread. */
    @Override
    public void teleport(Player player) {
        if (this.box == null) {
            this.services.messenger().send(player, AfkMessages.ZONE_CLOSED);
            return;
        }
        UUID id = player.getUniqueId();
        if (inside(id)) {
            this.services.messenger().send(player, AfkMessages.ZONE_ALREADY_THERE);
            return;
        }
        Duration left = this.services.cooldowns().remaining(id, COOLDOWN_KEY);
        if (!left.isZero() && !player.hasPermission("siftcore.bypass.cooldown")) {
            this.services.messenger().send(player, CoreMessages.COOLDOWN, Arg.time("time", left));
            return;
        }
        AfkSettings s = this.settings.get();
        this.services.teleports().teleport(player, "afkzone", s.teleportWarmup(), () -> destination(player), ok -> {
            if (ok) {
                this.services.cooldowns().start(id, COOLDOWN_KEY, this.settings.get().teleportCooldown());
            }
        });
    }

    /** Where /afkzone lands: the arrival point, or the ground in the middle of the zone (read on its region thread). */
    CompletableFuture<Location> destination(Player player) {
        ZoneBox current = this.box;
        World world = current == null ? null : Bukkit.getWorld(current.world());
        if (current == null || world == null) {
            this.services.messenger().send(player, AfkMessages.ZONE_CLOSED);
            return CompletableFuture.completedFuture(null);
        }
        Point arrival = current.arrival();
        if (arrival != null) {
            return CompletableFuture.completedFuture(new Location(world, arrival.x(), arrival.y(), arrival.z(), arrival.yaw(), arrival.pitch()));
        }
        int x = (int) Math.floor(current.centerX());
        int z = (int) Math.floor(current.centerZ());
        float yaw = player.getLocation().getYaw();
        CompletableFuture<Location> result = new CompletableFuture<>();
        world.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            this.services.scheduler().region(world, x >> 4, z >> 4, () -> {
                try {
                    int y = Math.clamp(world.getHighestBlockYAt(x, z) + 1, current.minY(), current.maxY());
                    result.complete(new Location(world, x + 0.5, y, z + 0.5, yaw, 0f));
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        });
        return result;
    }

    // ------------------------------------------------------------------ zone administration

    /** Sets corner 1 or 2 of an in-game zone at the admin's feet; both corners make a new zone. Admin's thread. */
    Optional<ZoneSpec> corner(Player admin, int index) {
        Location here = admin.getLocation();
        Corner corner = new Corner(here.getBlockX(), here.getBlockY(), here.getBlockZ());
        String world = here.getWorld().getName();
        PendingCorners pending = this.pendingCorners.compute(admin.getUniqueId(), (k, old) ->
            (old == null || !old.world().equals(world) ? new PendingCorners(world, null, null) : old).with(index, corner));
        if (pending.one() == null || pending.two() == null) {
            return Optional.empty();
        }
        this.pendingCorners.remove(admin.getUniqueId(), pending);
        return Optional.of(set(world, pending.one(), pending.two()));
    }

    /** Corners an admin set with /afkzone pos1 and pos2 that don't form a zone yet (both must be in one world). */
    private record PendingCorners(String world, Corner one, Corner two) {

        PendingCorners with(int index, Corner corner) {
            return index == 1 ? new PendingCorners(this.world, corner, this.two) : new PendingCorners(this.world, this.one, corner);
        }
    }

    /** Sets the zone to a box in a world; keeps the arrival point if it is still inside. Any thread. */
    ZoneSpec set(String world, Corner a, Corner b) {
        ZoneSpec spec;
        synchronized (this.zoneLock) {
            ZoneSpec previous = this.override;
            Point arrival = previous == null ? null : previous.arrival();
            ZoneBox candidate = ZoneBox.of(world, a, b, null);
            if (arrival != null && !candidate.contains(world, arrival.x(), arrival.y(), arrival.z())) {
                arrival = null;
            }
            spec = new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, world, a, b, arrival);
            this.override = spec;
        }
        this.lastSave = this.store.save(spec);
        refreshZone();
        return spec;
    }

    /** Sets where /afkzone lands; it must be inside the zone. Returns false when it is not. Any thread. */
    boolean arrival(Location location) {
        ZoneBox current = this.box;
        if (current == null || !current.contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ())) {
            return false;
        }
        Point point = new Point(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        ZoneSpec spec;
        synchronized (this.zoneLock) {
            // Pin the zone where it is now (a spawn-anchored zone becomes world coordinates), with the new arrival.
            spec = new ZoneSpec(ZoneSpec.Anchor.ABSOLUTE, current.world(), new Corner(current.minX(), current.minY(), current.minZ()),
                new Corner(current.maxX(), current.maxY(), current.maxZ()), point);
            this.override = spec;
        }
        this.lastSave = this.store.save(spec);
        refreshZone();
        return true;
    }

    /** Drops the zone set in game. Returns false when there was none. Any thread. */
    boolean reset() {
        synchronized (this.zoneLock) {
            if (this.override == null) {
                return false;
            }
            this.override = null;
        }
        this.lastSave = this.store.clear();
        refreshZone();
        return true;
    }

    /** Players in the zone that the viewer may know about (vanished staff are left out). */
    int visibleInside() {
        int count = 0;
        for (UUID player : this.sessions.players()) {
            if (!this.vanish.vanished(player)) {
                count++;
            }
        }
        return count;
    }

    /** Players in the zone who hold their connection's slot (so they earn), vanished staff left out. */
    int visibleEarning() {
        int count = 0;
        for (UUID player : this.sessions.players()) {
            if (!this.vanish.vanished(player) && this.sessions.holderFor(player) == null) {
                count++;
            }
        }
        return count;
    }

    ZoneSessions sessions() {
        return this.sessions;
    }

    /** Completes when the last change of the zone set in game is on disk (fails when it could not be saved). */
    CompletableFuture<Void> saved() {
        return this.lastSave;
    }

    /** Waits for the zone file (shutdown). */
    void flush() {
        this.store.flush(10);
    }
}
