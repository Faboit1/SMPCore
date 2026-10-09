package net.siftvanilla.siftcore.feature.stats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.CommittedTx;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Lifetime stats and leaderboards: kills, deaths, streaks (reported by the combat feature through
 * {@link #recorder()}), mobs killed, blocks mined, money earned (from the ledger) and active playtime. Stats live in
 * memory and are written behind; leaderboards are cached snapshots rebuilt off-thread.
 * <p>
 * Player settings: {@code leaderboard-rank-alerts} (Combat &amp; stats). Reads the shared {@code hide-from-leaderboards}
 * (hidden players are left off every board, the money board and the holograms that show them) and
 * {@code balance-privacy} (the balance line of another player's stats).
 */
public final class StatsFeature implements Feature {

    /** When a player is told they moved up a leaderboard. */
    public static final Choice<RankAlerts> RANK_ALERTS = Choice.ofEnum("leaderboard-rank-alerts", RankAlerts.class, RankAlerts::id,
            RankAlerts.TOP_10)
        .option(RankAlerts.TOP_10, RankAlerts.TOP_10.label())
        .option(RankAlerts.ALL, RankAlerts.ALL.label())
        .option(RankAlerts.OFF, RankAlerts.OFF.label())
        .text(StatsMessages.SETTING_RANK_ALERTS, StatsMessages.SETTING_RANK_ALERTS_DESCRIPTION).build();

    private static final StatsDelta ONE_SECOND = StatsDelta.add(Counter.PLAYTIME, 1);
    private static final Duration SHUTDOWN_SAVE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STARTUP_BOARDS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PLACED_SWEEP = Duration.ofMinutes(5);
    private static final int MAX_PLACE = 100;
    private static final UUID SELF_TEST_PLAYER = new UUID(0L, 0L);

    private final Services services;
    private final Setting<StatsSettings> settings;
    private final AfkStatus afk;
    private final VanishStatus vanish;
    private final Logger logger;
    private final StatsStorage storage;
    private final StatsStore store;
    private final Leaderboards boards;
    private final PlacedBlocks placed;
    private final StatsViews views;
    private final StatsCommands commands;
    private final List<Task> timers = new ArrayList<>();
    private Duration scheduledSave;
    private Duration scheduledRefresh;

    /**
     * @param afk    AFK players earn no playtime
     * @param vanish vanished staff earn no playtime either, so /playtime and /stats don't show that they are online
     */
    public StatsFeature(Services services, List<ConfigProblem> problems, AfkStatus afk, EconomyApi economy, AdminFeature admin,
                        VanishStatus vanish) {
        this.services = services;
        this.afk = afk;
        this.vanish = vanish;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register("features/stats.yml",
            reader -> StatsSettings.parse(reader, StatsFeature::isBlock), problems);
        services.lang().register(StatsMessages.class);
        registerSettings(services.settings());
        var perms = services.permissions();
        perms.declare(StatsCommands.STATS, "Use /stats", true);
        perms.declare(StatsCommands.STATS_OTHERS, "See other players' stats", true);
        perms.declare(StatsViews.TOP_PERMISSION, "Use /top and the leaderboards", true);
        perms.declare(StatsCommands.PLAYTIME, "Use /playtime", true);
        perms.declare(StatsCommands.PLAYTIME_OTHERS, "See other players' playtime", true);
        perms.declare(StatsCommands.ADMIN, "Reset and correct stats and rebuild leaderboards with /sift stats", false);
        StatsSettings initial = this.settings.get();
        this.storage = new SqlStatsStorage(services.database());
        this.store = new StatsStore(this.storage, this.logger, System::currentTimeMillis, initial.keepOffline());
        HiddenPlayers hidden = new HiddenPlayers(services.database(), services.settings());
        this.boards = new Leaderboards(this.store, this.storage, limit -> economy.top(Currency.MONEY, limit),
            services.directory()::name, uuid -> services.directory().get(uuid).isPresent(),
            () -> this.settings.get().leaderboardSize(), () -> this.settings.get().kdrMinKills(), this.logger, System::currentTimeMillis,
            hidden::load);
        this.boards.onSwap(this::climbed);
        this.placed = new PlacedBlocks(initial.placedMemory());
        this.views = new StatsViews(services, this.store, this.boards, this.settings);
        this.commands = new StatsCommands(services, this.store, this.boards, this.views);
        admin.addPart(this.commands.adminPart());
    }

    /**
     * Registers the climb alerts and declares the shared settings stats acts on: hiding from the leaderboards and who
     * may see a player's balance in their stats.
     */
    static void registerSettings(PlayerSettings prefs) {
        prefs.register(SettingCategories.COMBAT, RANK_ALERTS, SettingOptions.<RankAlerts>builder().order(9).build());
        prefs.reads(SharedSettings.HIDE_FROM_LEADERBOARDS);
        prefs.reads(SharedSettings.BALANCE_PRIVACY);
    }

    @Override
    public String id() {
        return "stats";
    }

    /** The recorder the combat feature (kills, deaths) and others report to. */
    public StatsRecorder recorder() {
        return this.store;
    }

    /** Whether a lowercase name without namespace (such as {@code melon}) is a block type in this version. */
    private static boolean isBlock(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isBlock();
    }

    @Override
    public void enable() throws Exception {
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.store.join(online.getUniqueId());
        }
        Bukkit.getPluginManager().registerEvents(new StatsListener(this.store, this.placed, this.settings, this.logger),
            this.services.plugin());
        this.services.ledger().subscribe(this::committed);
        this.settings.onReload(this::reloaded);
        startTimers();
        this.services.hub().register(new HubEntry("stats", 70, StatsMessages.HUB_LABEL, StatsMessages.HUB_DESCRIPTION,
            StatsCommands.STATS, player -> this.views.openStats(player, player.getUniqueId(), s -> openMenu(s.player()))));
        registerPlaceholders(this.services.placeholders());
        buildBoardsAtStartup();
    }

    /** Builds the leaderboards once before players join; a slow or failing database only delays them. */
    private void buildBoardsAtStartup() throws InterruptedException {
        boolean built;
        try {
            built = this.boards.refresh().get(STARTUP_BOARDS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            built = false;
        }
        if (!built) {
            this.logger.warning("The leaderboards could not be built at startup; they are retried every "
                + Durations.format(this.settings.get().leaderboardRefresh()) + ".");
        }
    }

    @Override
    public void disable() {
        synchronized (this.timers) {
            this.timers.forEach(Task::cancel);
            this.timers.clear();
        }
        this.store.close(SHUTDOWN_SAVE_TIMEOUT);
        this.placed.clear();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    // ------------------------------------------------------------------ sources

    /** Money earned, from committed ledger transactions (runs on a database callback thread; memory only). */
    private void committed(CommittedTx tx) {
        StatsSettings settings = this.settings.get();
        Map<UUID, Long> earned = Earnings.of(tx.postings(), settings.earnKinds(), settings.taxKinds(),
            account -> this.services.directory().get(account).isPresent());
        earned.forEach((player, amount) -> this.store.record(player, StatsDelta.add(Counter.EARNED, amount)));
    }

    /** Active playtime: one second for every online player who is playing (not AFK, not vanished). */
    private void tickPlaytime() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (playing(uuid, this.afk, this.vanish)) {
                this.store.record(uuid, ONE_SECOND);
            }
        }
    }

    /**
     * Whether an online player's second counts as active playtime. Vanished staff are not playing, and a counter that
     * grows while they are hidden would tell anyone polling {@code /playtime <name>} that they are online.
     */
    static boolean playing(UUID player, AfkStatus afk, VanishStatus vanish) {
        return !afk.afk(player) && !vanish.vanished(player);
    }

    /**
     * Tells online players who moved up a leaderboard in the last rebuild, as their {@code leaderboard-rank-alerts}
     * choice says (runs on a database callback thread; packets only).
     */
    private void climbed(Map<Board, Leaderboard> before, Map<Board, Leaderboard> after) {
        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        var settings = this.services.settings();
        for (Climbs.Climb climb : Climbs.of(before, after, online, uuid -> settings.get(uuid, RANK_ALERTS))) {
            Player player = Bukkit.getPlayer(climb.player());
            if (player != null) {
                this.services.messenger().send(player, StatsMessages.CLIMBED, Arg.number("rank", climb.rank()),
                    Arg.component("board", this.services.lang().get(StatsMessages.button(climb.board()))));
            }
        }
    }

    // ------------------------------------------------------------------ timers

    private void startTimers() {
        StatsSettings settings = this.settings.get();
        var scheduler = this.services.scheduler();
        synchronized (this.timers) {
            this.timers.forEach(Task::cancel);
            this.timers.clear();
            this.scheduledSave = settings.saveInterval();
            this.scheduledRefresh = settings.leaderboardRefresh();
            this.timers.add(scheduler.asyncTimer(this::tickPlaytime, Duration.ofSeconds(1), Duration.ofSeconds(1)));
            this.timers.add(scheduler.asyncTimer(this::saveCycle, this.scheduledSave, this.scheduledSave));
            this.timers.add(scheduler.asyncTimer(this::refreshCycle, this.scheduledRefresh, this.scheduledRefresh));
            this.timers.add(scheduler.asyncTimer(this::sweepPlaced, PLACED_SWEEP, PLACED_SWEEP));
        }
    }

    private void saveCycle() {
        this.store.save();
        this.store.sweep();
        this.store.retryLoads();
    }

    private void refreshCycle() {
        this.boards.refresh().exceptionally(error -> {
            this.logger.log(Level.WARNING, "Rebuilding the leaderboards failed", error);
            return false;
        });
    }

    private void sweepPlaced() {
        Duration window = this.settings.get().ignorePlacedFor();
        if (window.isZero()) {
            this.placed.clear();
        } else {
            this.placed.sweep(System.currentTimeMillis(), window.toMillis());
        }
    }

    private void reloaded(StatsSettings settings) {
        this.store.keepOffline(settings.keepOffline());
        this.placed.capacity(settings.placedMemory());
        if (settings.ignorePlacedFor().isZero()) {
            this.placed.clear();
        }
        boolean reschedule;
        synchronized (this.timers) {
            reschedule = !settings.saveInterval().equals(this.scheduledSave) || !settings.leaderboardRefresh().equals(this.scheduledRefresh);
        }
        if (reschedule) {
            startTimers();
        }
    }

    // ------------------------------------------------------------------ hub, placeholders

    private void openMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }

    private void registerPlaceholders(Placeholders placeholders) {
        stat(placeholders, "kills", "Your kills", s -> Lang.number(s.kills()));
        stat(placeholders, "deaths", "Your deaths", s -> Lang.number(s.deaths()));
        stat(placeholders, "kdr", "Your kills per death with two decimals (1.50)", StatsSnapshot::kdr);
        stat(placeholders, "streak", "Your current kill streak", s -> Lang.number(s.streak()));
        stat(placeholders, "best_streak", "Your best kill streak", s -> Lang.number(s.bestStreak()));
        stat(placeholders, "playtime", "Your active playtime (3d 4h)", s -> Durations.format(Duration.ofSeconds(s.playtime())));
        stat(placeholders, "playtime_hours", "Your active playtime in whole hours", s -> Long.toString(s.playtime() / 3600));
        stat(placeholders, "mobs", "Mobs you killed", s -> Lang.number(s.mobs()));
        stat(placeholders, "blocks", "Blocks you mined", s -> Lang.number(s.blocks()));
        // In the money format of the player PlaceholderAPI asks for, like the balance placeholders.
        placeholders.register("stats_earned", "Money you earned, in your money format ($1.5m) (online players)", player -> {
            StatsSnapshot stats = player == null ? null : current(player);
            return this.services.lang().moneyFor(player, (stats == null ? StatsSnapshot.ZERO : stats).earned());
        });
        for (Board board : Board.values()) {
            String prefix = "top_" + board.id() + "_";
            placeholders.registerPrefix(prefix + "name_", prefix + "name_<n>",
                "Name at place <n> (1-" + MAX_PLACE + ") of the " + board.id() + " leaderboard, - when empty",
                (player, place) -> entry(board, place).map(Leaderboard.Entry::name).orElse("-"));
            placeholders.registerPrefix(prefix + "value_", prefix + "value_<n>",
                "Value at place <n> (1-" + MAX_PLACE + ") of the " + board.id() + " leaderboard, - when empty",
                (player, place) -> entry(board, place).map(e -> this.views.plain(board, e.value(), e.secondary(), player)).orElse("-"));
            placeholders.register(prefix + "rank", "Your place on the " + board.id() + " leaderboard, 0 when not listed",
                player -> player == null ? "0" : Integer.toString(this.boards.board(board).rankOf(player.getUniqueId())));
        }
    }

    private void stat(Placeholders placeholders, String name, String description, java.util.function.Function<StatsSnapshot, String> format) {
        placeholders.register("stats_" + name, description + " (online players)", player -> {
            StatsSnapshot stats = player == null ? null : current(player);
            return format.apply(stats == null ? StatsSnapshot.ZERO : stats);
        });
    }

    private StatsSnapshot current(OfflinePlayer player) {
        return this.store.current(player.getUniqueId());
    }

    private Optional<Leaderboard.Entry> entry(Board board, String place) {
        int position;
        try {
            position = Integer.parseInt(place);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return position < 1 || position > MAX_PLACE ? Optional.empty() : this.boards.board(board).at(position);
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "kdr math", () -> {
            String a = Kdr.format(3, 0);
            String b = Kdr.format(5, 2);
            String c = Kdr.format(2, 3);
            return a.equals("3.00") && b.equals("2.50") && c.equals("0.67") && Kdr.compare(1, 3, 2, 6) == 0
                ? null : "got " + a + ", " + b + ", " + c;
        });
        test.check(id(), "streaks survive split saves", () -> {
            StatsDelta first = StatsDelta.kill().then(StatsDelta.kill()).then(StatsDelta.death());
            StatsDelta second = StatsDelta.kill().then(StatsDelta.kill()).then(StatsDelta.kill());
            StatsSnapshot start = new StatsSnapshot(10, 4, 3, 7, 0, 0, 0, 0);
            StatsSnapshot split = second.applyTo(first.applyTo(start));
            StatsSnapshot joined = first.then(second).applyTo(start);
            return split.equals(joined) && split.streak() == 3 && split.bestStreak() == 7 && split.kills() == 15 && split.deaths() == 5
                ? null : "split " + split + " vs joined " + joined;
        });
        test.checkAsync(id(), "stats table is readable", () -> this.storage.load(SELF_TEST_PLAYER).thenApply(ignored -> null));
        test.check(id(), "leaderboards are fresh", () -> {
            long built = this.boards.refreshedAt();
            if (built == 0) {
                return "not built yet";
            }
            long age = System.currentTimeMillis() - built;
            long limit = this.settings.get().leaderboardRefresh().toMillis() * 3 + 5_000;
            return age <= limit ? null : "last built " + Durations.format(Duration.ofMillis(age)) + " ago";
        });
        test.check(id(), "online players are loaded", () -> {
            List<UUID> online = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                online.add(player.getUniqueId());
            }
            List<UUID> missing = this.store.notLoaded(online);
            if (missing.isEmpty()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (UUID uuid : missing) {
                names.add(this.services.directory().name(uuid));
            }
            return "not loaded: " + String.join(", ", names);
        });
        test.check(id(), "stats are being saved", () -> {
            int failing = this.store.failing();
            return failing == 0 ? null : "the last save of " + failing + " player(s) failed; " + this.store.unsaved()
                + " player(s) have unsaved stats (they are retried with every save)";
        });
    }
}
