package net.siftvanilla.siftcore.feature.teams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

/**
 * Teams: players create a team for money, invite friends, give them roles, share a home and a private chat, and
 * compete on team leaderboards. Implements {@link TeamLookup} for other features (friendly fire, same-team checks).
 * Uses {@link StatsRecorder} for team kills and deaths, {@link MuteStatus} so a mute covers team chat, and
 * {@link VanishStatus} so vanished members never show as online. All state lives in memory (loaded at enable) and
 * every change is written through in order; creating a team is a single ledger transaction.
 */
public final class TeamsFeature implements Feature {

    /** Staff setting (Staff group): see the chat of every team (needs {@value TeamChat#SPY_PERMISSION}). */
    public static final Toggle SPY = new Toggle("team-spy", true, TeamsMessages.SETTING_SPY,
        TeamsMessages.SETTING_SPY_DESCRIPTION, TeamChat.SPY_PERMISSION);

    private static final Duration INVITE_SWEEP = Duration.ofSeconds(30);
    private static final Duration OWNER_REFRESH = Duration.ofSeconds(60);

    private final Services services;
    private final Setting<TeamsSettings> settings;
    private final StatsRecorder stats;
    private final Logger logger;
    private final AtomicLong nextId = new AtomicLong(1);
    private final TeamRegistry registry = new TeamRegistry();
    private final Invites invites = new Invites();
    private final TeamStore store;
    private final TeamService service;
    private final TeamTop top = new TeamTop();
    private final TeamPresence presence;
    private final LoginAlerts loginAlerts;
    private final TeamChat chat;
    private final TeamFeedback feedback;
    private final OwnerLimits limits;
    private final TeamActions actions;
    private final TeamMenus menus;
    private final TeamCommands commands;
    private final FriendlyFireGuard guard;
    private final List<Task> tasks = new ArrayList<>();
    private volatile Task topTask = Task.NONE;

    /**
     * @param spawn no team home inside the protected spawn area, as with /sethome
     */
    public TeamsFeature(Services services, List<ConfigProblem> problems, StatsRecorder stats, MuteStatus mutes,
                        VanishStatus vanish, SpawnArea spawn) {
        this.services = services;
        this.stats = stats;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register("features/teams.yml",
            reader -> TeamsSettings.parse(reader, services.core().get().money(), name -> Bukkit.getWorld(name) != null), problems);
        services.lang().register(TeamsMessages.class);
        // Settings: team news, teammate logins, who can invite, team chat mode (Friends & teams); team chat spy (Staff).
        TeamPrefs.register(services.settings(), SPY, services.relations());
        var perms = services.permissions();
        perms.declare(TeamCommands.TEAM_PERMISSION, "Use /team", true);
        perms.declare(TeamCommands.CHAT_PERMISSION, "Use /teamchat (/tc)", true);
        perms.declare(TeamMenus.CREATE_PERMISSION, "Create teams", true);
        perms.declare(TeamChat.SPY_PERMISSION, "See the chat of every team", false);
        perms.declare(TeamMenus.ADMIN_PERMISSION, "Manage any team with /team admin", false);
        perms.declare(OwnerLimits.PREFIX + ".unlimited", "Teams you own have no member limit", PermissionDefault.FALSE);

        this.store = new TeamStore(services.database());
        this.service = new TeamService(services.ledger(), this.registry, this.store, this.invites, new TeamEventGate.Bukkit(),
            this.settings::get, this.nextId::getAndIncrement, System::currentTimeMillis, this.logger);
        this.presence = new TeamPresence(vanish);
        this.loginAlerts = new LoginAlerts(this.registry, this.presence, services.settings(), services.relations(), services.messenger(),
            services.scheduler(), this.settings);
        this.chat = new TeamChat(this.registry, services.messenger(), services.settings(), SPY, this.settings, mutes);
        this.feedback = new TeamFeedback(services.messenger(), this.settings, this.service);
        this.limits = new OwnerLimits(this.registry, this.service, services.scheduler());
        this.actions = new TeamActions(services, this.service, this.chat, this.feedback, this.limits, this.settings, spawn);
        this.menus = new TeamMenus(services, this.service, this.actions, this.feedback, this.top, stats, this.presence,
            this.settings, new TeamSeen(services.settings(), services.relations(), this.store));
        this.commands = new TeamCommands(services, this.service, this.actions, this.menus, this.feedback, this.presence,
            this.settings, SPY);
        this.guard = new FriendlyFireGuard(this.registry, this.settings, services.messenger(), services.cooldowns());
    }

    @Override
    public String id() {
        return "teams";
    }

    /**
     * The ignore lists (the chat feature, built after this one): a player who ignores the inviter gets no team invite.
     * Until set, nobody ignores anybody.
     */
    public void ignores(IgnoreLookup ignores) {
        this.actions.ignores(ignores);
    }

    /**
     * The worlds where /sethome is turned off (the homes feature, built after this one): team homes can't be set or
     * used there either, on top of teams.yml's own home.disabled-worlds. Until set, only teams.yml's list applies.
     */
    public void homeWorlds(Predicate<String> disabled) {
        this.actions.homeWorlds(disabled);
    }

    /** Read-only view of teams for other features. Thread-safe. */
    public TeamLookup lookup() {
        return this.registry;
    }

    @Override
    public void enable() throws Exception {
        load();
        var plugins = Bukkit.getPluginManager();
        plugins.registerEvents(this.chat, this.services.plugin());
        plugins.registerEvents(this.guard, this.services.plugin());
        plugins.registerEvents(this.limits, this.services.plugin());
        this.loginAlerts.start(System.currentTimeMillis());
        plugins.registerEvents(this.loginAlerts, this.services.plugin());

        var scheduler = this.services.scheduler();
        this.tasks.add(scheduler.asyncTimer(() -> {
            this.invites.sweep(System.currentTimeMillis());
            this.loginAlerts.prune();
        }, INVITE_SWEEP, INVITE_SWEEP));
        this.tasks.add(scheduler.asyncTimer(this.limits::refreshOnlineOwners, OWNER_REFRESH, OWNER_REFRESH));
        // A first board from what is in memory now, then a full one once offline members' stats are read.
        refreshTop(false);
        this.tasks.add(scheduler.asyncLater(() -> refreshTop(true), Duration.ofSeconds(5)));
        scheduleTop();
        this.settings.onReload(s -> scheduleTop());

        this.services.hub().register(new HubEntry("teams", 50, TeamsMessages.HUB_LABEL, TeamsMessages.HUB_DESCRIPTION,
            TeamCommands.TEAM_PERMISSION, this.menus::open));
        registerPlaceholders();
    }

    /** Reads every team, repairs inconsistent rows (logged), and seeds the id sequence. */
    private void load() throws Exception {
        TeamStore.Rows rows = this.store.load().get();
        for (String note : rows.unreadable()) {
            this.logger.warning("Skipped an unreadable team row: " + note);
        }
        TeamLoader.Result result = TeamLoader.assemble(rows.teams(), rows.members());
        if (!result.repairs().isEmpty()) {
            this.store.write(this.store.repair(result.repairs())).get();
            for (String note : result.notes()) {
                this.logger.warning("Repaired team data: " + note);
            }
        }
        this.registry.load(result.teams());
        this.nextId.set(this.store.highestId(TeamService.CREATE_KIND).get() + 1);
    }

    private synchronized void scheduleTop() {
        this.topTask.cancel();
        Duration period = this.settings.get().topRefresh();
        this.topTask = this.services.scheduler().asyncTimer(() -> refreshTop(true), period, period);
    }

    /**
     * @param preload read offline members' stats first (async threads only), so team kill totals count everyone.
     *                Loading also keeps them cached until the next refresh, which makes /team info exact as well.
     */
    private void refreshTop(boolean preload) {
        if (preload) {
            List<UUID> members = new ArrayList<>();
            for (Team team : this.registry.all()) {
                members.addAll(team.memberIds());
            }
            try {
                this.stats.preload(members).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                this.services.plugin().getLogger().log(Level.WARNING, "Team leaderboards: member stats took too long to load", e);
            }
        }
        this.top.refresh(this.registry.all(),
            member -> this.stats.get(member, StatsRecorder.Stat.KILLS),
            member -> this.services.ledger().balance(member, Currency.MONEY),
            this.settings.get().topSize(), System.currentTimeMillis());
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        var lang = this.services.lang();
        placeholders.register("team_name", "Your team's name (empty without a team)",
            player -> this.registry.of(player.getUniqueId()).map(Team::name).orElse(""));
        placeholders.register("team_role", "Your role in your team: owner, admin or member (empty without a team)",
            player -> this.registry.of(player.getUniqueId())
                .map(team -> lang.plain(TeamMenus.roleKey(team.role(player.getUniqueId()))))
                .orElse(""));
        placeholders.register("team_members", "Members in your team (0 without a team)",
            player -> Integer.toString(this.registry.of(player.getUniqueId()).map(Team::size).orElse(0)));
        placeholders.register("team_online", "Members of your team online now (0 without a team)",
            player -> Integer.toString(this.registry.of(player.getUniqueId())
                .map(team -> this.presence.online(team, player.getUniqueId())).orElse(0)));
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
        this.topTask.cancel();
        this.loginAlerts.stop();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "name rules", () -> {
            if (TeamNames.validate("Good_Team1", 3, 16, List.of()) != null) {
                return "a valid name was refused";
            }
            if (TeamNames.validate("ab", 3, 16, List.of()) != TeamProblem.NAME_TOO_SHORT
                || TeamNames.validate("bad name", 3, 16, List.of()) != TeamProblem.NAME_CHARACTERS
                || TeamNames.validate("B_4_D_team", 3, 16, List.of("bad")) != TeamProblem.NAME_BLOCKED) {
                return "an invalid name was accepted";
            }
            return null;
        });
        test.check(id(), "role permissions", () -> {
            boolean ok = TeamRules.invite(TeamRole.ADMIN) == null
                && TeamRules.invite(TeamRole.MEMBER) != null
                && TeamRules.kick(TeamRole.ADMIN, TeamRole.MEMBER) == null
                && TeamRules.kick(TeamRole.ADMIN, TeamRole.ADMIN) != null
                && TeamRules.promote(TeamRole.ADMIN, TeamRole.MEMBER) != null
                && TeamRules.promote(TeamRole.OWNER, TeamRole.MEMBER) == null
                && TeamRules.disband(TeamRole.ADMIN) != null
                && TeamRules.leave(TeamRole.OWNER) != null;
            return ok ? null : "the role matrix does not match the rules";
        });
        test.check(id(), "team indexes agree", this.registry::verify);
        test.checkAsync(id(), "storage matches memory", () -> this.store.counts().thenApply(counts -> {
            int teams = this.registry.count();
            int members = this.registry.memberCount();
            return counts[0] == teams && counts[1] == members ? null
                : "storage has " + counts[0] + " teams and " + counts[1] + " members, memory " + teams + " and " + members;
        }));
        test.check(id(), "member limit default", () -> {
            int limit = TeamRules.memberLimit(0, this.settings.get().defaultMemberLimit());
            return limit >= 1 ? null : "the default member limit is " + limit;
        });
    }
}
