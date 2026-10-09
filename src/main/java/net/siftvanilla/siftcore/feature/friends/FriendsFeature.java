package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

/**
 * Friends: players add friends, see at a glance who is online, get one dependable signal when friends come online,
 * and reach the rest of the server from a friend's profile (message, teleport, pay, stats, team invite). Implements
 * {@link FriendLookup} for other features through {@link #lookup()}.
 * <p>
 * Storage is the truth: every action is one ordered write unit that checks and changes atomically, so offline players
 * never need to be in memory and two actions can never race. Memory holds the friends of loaded players (from just
 * before they join until shortly after they leave) and takes each committed change in commit order.
 */
public final class FriendsFeature implements Feature {

    private static final Duration SWEEP_STORAGE = Duration.ofHours(1);
    private static final Duration SWEEP_MEMORY = Duration.ofMinutes(1);
    private static final Duration LIMIT_REFRESH = Duration.ofSeconds(60);
    /** READY nodes compared with storage by the self-test. */
    private static final int SELF_TEST_NODES = 20;

    private final Services services;
    private final Setting<FriendsSettings> settings;
    private final Logger logger;
    private final FriendGraph graph;
    private final FriendStore store;
    private final FriendPrefs prefs;
    private final FriendLinks links;
    private final RateLimiter rates = new RateLimiter();
    private final InFlight inFlight = new InFlight();
    private final RequestAlerts alerts;
    private final FriendService service;
    private final Presence presence;
    private final RankLimits limits;
    private final ProfileButtons buttons;
    private final FriendViews views;
    private final FriendsListener listener;
    private final FriendCommands commands;
    private final FriendPlaceholders placeholders;
    private final List<Task> tasks = new ArrayList<>();

    public FriendsFeature(Services services, List<ConfigProblem> problems, AdminFeature admin, CombatStatus combat,
                          IgnoreLookup ignores, VanishStatus vanish, AfkStatus afk, TeamLookup teams, Ranks ranks,
                          MuteStatus mutes) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register("features/friends.yml", FriendsSettings::parse, problems);
        services.lang().register(FriendsMessages.class);
        var perms = services.permissions();
        perms.declare(FriendCommands.FRIEND_PERMISSION, "Use /friend (/f, /friends)", true);
        perms.declare(FriendCommands.PROFILE_PERMISSION, "See player cards with /profile and a sneak right-click", true);
        perms.declare(RankLimits.PREFIX + ".unlimited", "Have as many friends as the hard cap allows", PermissionDefault.FALSE);
        perms.declare(FriendAdmin.PERMISSION, "Use /sift friends", false);
        // Settings, Friends & teams: who can send requests, alerts, the login summary and the list order.
        FriendPrefs.register(services.settings(), () -> this.settings.get().favouritesOn());

        this.prefs = new FriendPrefs(services.settings());
        this.links = new FriendLinks(combat, ignores, vanish, afk, teams, ranks, mutes);
        this.graph = new FriendGraph(System::currentTimeMillis, () -> this.settings.get().antiFarmRemember(),
            new FriendGraph.TeleportPolicy() {
                @Override
                public AutoAccept mode(UUID target) {
                    AutoAccept mode = FriendsFeature.this.prefs.autoTpa(target);
                    // Without favourites (limits.favourites 0) stored favourite flags mean nothing, here as everywhere.
                    return mode == AutoAccept.FAVOURITES && !FriendsFeature.this.settings.get().favouritesOn()
                        ? AutoAccept.NOBODY : mode;
                }

                @Override
                public boolean ignores(UUID player, UUID other) {
                    return FriendsFeature.this.links.ignores().ignores(player, other);
                }

                @Override
                public boolean favouritesOn() {
                    return FriendsFeature.this.settings.get().favouritesOn();
                }

                @Override
                public boolean sameTeam(UUID a, UUID b) {
                    return FriendsFeature.this.links.teams().sameTeam(a, b);
                }
            });
        // The request unit reads the target's privacy with the server's lock and default (features/settings.yml).
        this.store = new FriendStore(services.database(), System::currentTimeMillis, this.prefs::privacyRule);
        this.alerts = new RequestAlerts(services.scheduler(), services.messenger(), this.settings, this.prefs, this.links,
            services.directory()::name);
        this.service = new FriendService(services, this.settings, this.graph, this.store, this.prefs, this.links, this.rates,
            this.inFlight, this.alerts);
        this.alerts.incoming(this.service::incoming);
        this.presence = new Presence(services.scheduler(), services.messenger(), this.settings, this.service, this.alerts, this.logger);
        this.limits = new RankLimits(this.graph, this.store, ranks, services.scheduler(), this.logger);
        this.buttons = new ProfileButtons(teams, mutes, services.messenger(), this.logger, services.settings(), this.graph);
        this.views = new FriendViews(services, this.settings, this.service, this.presence, this.buttons,
            new SeenPrivacy(services.settings(), this.graph, this.store));
        this.listener = new FriendsListener(services, this.settings, this.service, this.presence, this.limits, this.views);
        this.commands = new FriendCommands(services, this.settings, this.service, this.views);
        this.placeholders = new FriendPlaceholders(this.service);
        admin.addPart(new FriendAdmin(services, this.service).part());
    }

    @Override
    public String id() {
        return "friends";
    }

    /** Friendships for other features (TPA auto-accept, combat anti-farm, chat, scoreboard). Thread-safe. */
    public FriendLookup lookup() {
        return this.graph;
    }

    @Override
    public void enable() throws Exception {
        this.presence.start(System.currentTimeMillis());
        Bukkit.getPluginManager().registerEvents(this.listener, this.services.plugin());
        this.listener.loadMissing(true);
        var scheduler = this.services.scheduler();
        this.tasks.add(scheduler.asyncTimer(this::sweepStorage, SWEEP_STORAGE, SWEEP_STORAGE));
        this.tasks.add(scheduler.asyncTimer(() -> {
            this.listener.sweep(this.rates);
            this.placeholders.prune();
        }, SWEEP_MEMORY, SWEEP_MEMORY));
        this.tasks.add(scheduler.asyncTimer(this.limits::refreshOnline, LIMIT_REFRESH, LIMIT_REFRESH));
        this.services.hub().register(new HubEntry("friends", 52, FriendsMessages.HUB_LABEL, FriendsMessages.HUB_DESCRIPTION,
            FriendCommands.FRIEND_PERMISSION, player -> this.views.openList(player, FriendViews.Nav.menu(), null)));
        this.placeholders.register(this.services.placeholders());
    }

    /** Deletes run-out requests, closed rows past the deny memory and old history, in one unit. */
    private void sweepStorage() {
        FriendsSettings s = this.settings.get();
        this.store.write(this.store.sweep(s.rules(), s.logKeep().toMillis())).whenComplete((deleted, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "The friends sweep failed", error);
            } else if (deleted > 0 && this.services.debug()) {
                this.logger.info("Friends sweep removed " + deleted + " old rows");
            }
        });
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        for (Task task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();
        this.presence.stop();
        this.alerts.stop();
        this.listener.stop();
        this.limits.clear();
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.checkAsync(id(), "friendships are symmetric", () -> this.store.invariants().thenApply(counts ->
            counts[0] == 0 ? null : counts[0] + " friend row(s) without the other direction"));
        test.checkAsync(id(), "nobody is their own friend", () -> this.store.invariants().thenApply(counts ->
            counts[1] == 0 ? null : counts[1] + " row(s) of a player with themselves"));
        test.checkAsync(id(), "no requests between friends", () -> this.store.invariants().thenApply(counts ->
            counts[2] == 0 ? null : counts[2] + " request row(s) between players who are friends"));
        test.checkAsync(id(), "memory matches storage", () -> compareNodes(true));
        test.check(id(), "no stray nodes in memory", this::strayNodes);
        test.check(id(), "decision table", FriendsSelfTest::decisions);
        test.check(id(), "note text is cleaned", FriendsSelfTest::notes);
    }

    /** Compares up to 20 loaded nodes with storage; a mismatch is checked once more after half a second. */
    private CompletableFuture<String> compareNodes(boolean retry) {
        List<FriendGraph.Node> ready = new ArrayList<>();
        for (FriendGraph.Node node : this.graph.nodes()) {
            if (node.phase() == FriendGraph.Phase.READY && ready.size() < SELF_TEST_NODES) {
                ready.add(node);
            }
        }
        if (ready.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        FriendRules rules = this.settings.get().rules();
        List<CompletableFuture<String>> checks = new ArrayList<>();
        for (FriendGraph.Node node : ready) {
            checks.add(this.store.read(node.id(), rules).thenApply(snapshot -> difference(node.id(), snapshot, rules)));
        }
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            List<String> problems = new ArrayList<>();
            for (CompletableFuture<String> check : checks) {
                String problem = check.join();
                if (problem != null) {
                    problems.add(problem);
                }
            }
            if (problems.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            if (retry) {
                // A change may have committed between the read and memory taking it: look again shortly.
                CompletableFuture<String> again = new CompletableFuture<>();
                this.services.scheduler().asyncLater(() -> compareNodes(false).whenComplete((r, e) ->
                    again.complete(e != null ? "threw " + e : r)), Duration.ofMillis(500));
                return again;
            }
            return CompletableFuture.completedFuture(String.join("; ", problems));
        });
    }

    private String difference(UUID player, FriendGraph.Snapshot stored, FriendRules rules) {
        FriendGraph.Node node = this.graph.loaded(player);
        if (node == null) {
            return null;
        }
        String name = this.services.directory().name(player);
        if (!node.friends().equals(stored.friends())) {
            return name + ": friends differ (memory " + node.friends().size() + ", storage " + stored.friends().size() + ")";
        }
        long expired = rules.expiredBefore(System.currentTimeMillis());
        if (!fresh(node.incoming(), expired).equals(fresh(stored.incoming(), expired))) {
            return name + ": incoming requests differ";
        }
        if (!fresh(node.outgoing(), expired).equals(fresh(stored.outgoing(), expired))) {
            return name + ": sent requests differ";
        }
        return null;
    }

    private static Set<UUID> fresh(Map<UUID, Long> requests, long expiredBefore) {
        Set<UUID> result = new HashSet<>();
        requests.forEach((id, created) -> {
            if (created >= expiredBefore) {
                result.add(id);
            }
        });
        return result;
    }

    /** Loaded nodes of players who are offline and not leaving, and older than the never-joined allowance. */
    private String strayNodes() {
        long now = System.currentTimeMillis();
        int stray = 0;
        for (FriendGraph.Node node : this.graph.nodes()) {
            if (node.phase() != FriendGraph.Phase.LEAVING && Bukkit.getPlayer(node.id()) == null
                && now - node.created() > 3 * SWEEP_MEMORY.toMillis()) {
                stray++;
            }
        }
        int online = Bukkit.getOnlinePlayers().size();
        long leaving = this.graph.nodes().stream().filter(n -> n.phase() == FriendGraph.Phase.LEAVING).count();
        if (stray > 0) {
            return stray + " node(s) of players who are not online (" + this.graph.size() + " nodes, " + online + " online, "
                + leaving + " leaving)";
        }
        return null;
    }
}
