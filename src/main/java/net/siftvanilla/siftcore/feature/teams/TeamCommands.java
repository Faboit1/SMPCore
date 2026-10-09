package net.siftvanilla.siftcore.feature.teams;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /team} (alias {@code /t}) with every subcommand, and {@code /teamchat} (alias {@code /tc}). */
final class TeamCommands {

    static final String TEAM_PERMISSION = "siftcore.command.team";
    static final String CHAT_PERMISSION = "siftcore.command.teamchat";

    private static final int MAX_SUGGESTIONS = 50;

    private final Services services;
    private final CommandSupport support;
    private final Messenger messenger;
    private final TeamService service;
    private final TeamRegistry registry;
    private final TeamActions actions;
    private final TeamMenus menus;
    private final TeamFeedback feedback;
    private final TeamPresence presence;
    private final Setting<TeamsSettings> settings;
    private final Toggle spyToggle;

    TeamCommands(Services services, TeamService service, TeamActions actions, TeamMenus menus, TeamFeedback feedback,
                 TeamPresence presence, Setting<TeamsSettings> settings, Toggle spyToggle) {
        this.services = services;
        this.support = services.commands();
        this.messenger = services.messenger();
        this.service = service;
        this.registry = service.registry();
        this.actions = actions;
        this.menus = menus;
        this.feedback = feedback;
        this.presence = presence;
        this.settings = settings;
        this.spyToggle = spyToggle;
    }

    List<SiftCommand> all() {
        return List.of(team(), teamChat());
    }

    // ------------------------------------------------------------------ /team

    private SiftCommand team() {
        return new SimpleCommand("team", List.of("t"), "Your team: members, home, chat and leaderboards", TEAM_PERMISSION,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(TEAM_PERMISSION))
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        this.menus.open(player);
                    } else {
                        help(ctx.getSource().getSender());
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
                .then(Commands.literal("create")
                    .requires(CommandSupport.playerPermission(TeamMenus.CREATE_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> {
                        this.menus.show(player, this.menus.createForm(player, ""));
                    }))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(this.playerOnly((player, ctx) -> create(player, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("invite")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> this.menus.show(player, this.menus.inviteForm(player, ""))))
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(this::invitees)
                        .executes(this.playerOnly(this::invite))))
                .then(Commands.literal("join")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .then(Commands.argument("team", StringArgumentType.word()).suggests(this::invitingTeams)
                        .executes(this.playerOnly((player, ctx) -> answer(player, ctx, true)))))
                .then(Commands.literal("decline")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .then(Commands.argument("team", StringArgumentType.word()).suggests(this::invitingTeams)
                        .executes(this.playerOnly((player, ctx) -> answer(player, ctx, false)))))
                .then(Commands.literal("leave")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> report(player, this.actions.leave(player), null))))
                .then(memberAction("kick", (player, target) -> this.actions.kick(player, target)))
                .then(memberAction("promote", (player, target) -> this.actions.promote(player, target)))
                .then(memberAction("demote", (player, target) -> this.actions.demote(player, target)))
                .then(Commands.literal("transfer")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(this::teammates)
                        .executes(this.playerOnly(this::transfer))))
                .then(Commands.literal("disband")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> disband(player))))
                .then(Commands.literal("sethome")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> report(player, this.actions.setHome(player), null))))
                .then(Commands.literal("home")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> {
                        TeamProblem problem = this.actions.home(player);
                        if (problem != null) {
                            this.feedback.send(player, problem, null, null);
                        }
                    })))
                .then(Commands.literal("friendlyfire")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> report(player, this.actions.friendlyFire(player, null), null)))
                    .then(Commands.literal("on").executes(this.playerOnly((player, ctx) ->
                        report(player, this.actions.friendlyFire(player, true), null))))
                    .then(Commands.literal("off").executes(this.playerOnly((player, ctx) ->
                        report(player, this.actions.friendlyFire(player, false), null)))))
                .then(Commands.literal("chat")
                    .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> toggleChat(player))))
                .then(Commands.literal("info")
                    .executes(ctx -> info(ctx.getSource().getSender(), null))
                    .then(Commands.argument("team", StringArgumentType.word()).suggests(this::allTeams)
                        .executes(ctx -> info(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "team")))))
                .then(Commands.literal("list")
                    .executes(ctx -> list(ctx.getSource().getSender(), 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000))
                        .executes(ctx -> list(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("top")
                    .executes(ctx -> top(ctx.getSource().getSender(), TeamTop.Board.KILLS))
                    .then(Commands.literal("kills").executes(ctx -> top(ctx.getSource().getSender(), TeamTop.Board.KILLS)))
                    .then(Commands.literal("money").executes(ctx -> top(ctx.getSource().getSender(), TeamTop.Board.MONEY))))
                .then(Commands.literal("spy")
                    .requires(CommandSupport.playerPermission(TeamChat.SPY_PERMISSION))
                    .executes(this.playerOnly((player, ctx) -> spy(player))))
                .then(admin()));
    }

    /** Wraps a player-only handler (the console gets the usual "players only" message). */
    private Command<CommandSourceStack> playerOnly(PlayerHandler handler) {
        return ctx -> {
            Player player = this.support.player(ctx);
            if (player != null) {
                handler.handle(player, ctx);
            }
            return CommandSupport.OK;
        };
    }

    @FunctionalInterface
    private interface PlayerHandler {
        void handle(Player player, CommandContext<CommandSourceStack> ctx);
    }

    private int help(CommandSender sender) {
        this.messenger.chat(sender, TeamsMessages.HELP);
        if (sender.hasPermission(TeamMenus.ADMIN_PERMISSION)) {
            this.messenger.chat(sender, TeamsMessages.HELP_ADMIN);
        }
        return CommandSupport.OK;
    }

    /** Sends the outcome's problem on the action bar; success messages were already sent by the action. */
    private void report(CommandSender sender, TeamService.Outcome outcome, String target) {
        if (!outcome.ok()) {
            this.feedback.send(sender, outcome.problem(), outcome.team(), target);
        }
    }

    private void create(Player player, String name) {
        TeamProblem problem = this.service.checkCreate(player.getUniqueId(), name);
        if (problem != null) {
            this.feedback.send(player, problem, null, name);
            return;
        }
        long cost = this.settings.get().createCost();
        if (cost > 0) {
            this.menus.show(player, this.menus.confirmCreate(player, name, null));
        } else {
            report(player, this.actions.create(player, name, cost), name);
        }
    }

    private void invite(Player player, CommandContext<CommandSourceStack> ctx) {
        Player target = this.support.online(ctx, "player");
        if (target == null) {
            return;
        }
        if (this.presence.hidden(target.getUniqueId())) {
            this.messenger.send(player, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", StringArgumentType.getString(ctx, "player")));
            return;
        }
        Duration wait = this.actions.inviteCooldown(player);
        if (!wait.isZero()) {
            this.messenger.send(player, CoreMessages.COOLDOWN, Arg.time("time", wait));
            return;
        }
        report(player, this.actions.invite(player, target), target.getName());
    }

    private void answer(Player player, CommandContext<CommandSourceStack> ctx, boolean accept) {
        String name = StringArgumentType.getString(ctx, "team");
        Optional<Team> team = this.registry.byName(name);
        if (team.isEmpty()) {
            this.messenger.send(player, TeamsMessages.TEAM_NOT_FOUND, Arg.text("name", name));
            return;
        }
        TeamService.Outcome outcome = accept ? this.actions.accept(player, team.get().id()) : this.actions.decline(player, team.get().id());
        report(player, outcome, team.get().name());
    }

    /** {@code /team <action> <member>}: resolves the name among the player's teammates, then acts. */
    private LiteralArgumentBuilder<CommandSourceStack> memberAction(String literal, BiFunction<Player, UUID, TeamService.Outcome> action) {
        return Commands.literal(literal)
            .requires(CommandSupport.playerPermission(TEAM_PERMISSION))
            .then(Commands.argument("player", StringArgumentType.word()).suggests(this::teammates)
                .executes(this.playerOnly((player, ctx) -> {
                    String name = StringArgumentType.getString(ctx, "player");
                    Team team = this.registry.of(player.getUniqueId()).orElse(null);
                    if (team == null) {
                        this.feedback.send(player, TeamProblem.NOT_IN_TEAM, null, name);
                        return;
                    }
                    UUID target = member(team, name);
                    if (target == null) {
                        this.feedback.send(player, TeamProblem.TARGET_NOT_MEMBER, team, name);
                        return;
                    }
                    report(player, action.apply(player, target), this.actions.name(target));
                })));
    }

    private void transfer(Player player, CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            this.feedback.send(player, TeamProblem.NOT_IN_TEAM, null, name);
            return;
        }
        UUID target = member(team, name);
        TeamProblem problem = TeamRules.transfer(team.role(player.getUniqueId()), target == null ? null : team.role(target),
            player.getUniqueId().equals(target));
        if (problem != null) {
            this.feedback.send(player, problem, team, name);
            return;
        }
        this.menus.show(player, this.menus.confirmTransfer(player, target));
    }

    private void disband(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        TeamProblem problem = team == null ? TeamProblem.NOT_IN_TEAM : TeamRules.disband(team.role(player.getUniqueId()));
        if (problem != null) {
            this.feedback.send(player, problem, team, null);
            return;
        }
        this.menus.show(player, this.menus.confirmDisband(player));
    }

    private void toggleChat(Player player) {
        TeamProblem problem = this.actions.toggleChat(player);
        if (problem != null) {
            this.feedback.send(player, problem, null, null);
        }
    }

    /** Flips team chat spy through the settings registry; a locked or hidden spy setting is refused with the reason. */
    private void spy(Player player) {
        boolean on = !this.services.settings().get(player, this.spyToggle);
        switch (this.services.settings().set(player, this.spyToggle, on, Change.command(player.getName()))) {
            case CHANGED, UNCHANGED -> this.messenger.send(player, on ? TeamsMessages.SPY_ON : TeamsMessages.SPY_OFF);
            case LOCKED -> this.messenger.send(player, TeamsMessages.SPY_LOCKED);
            default -> this.messenger.send(player, TeamsMessages.SPY_UNAVAILABLE);
        }
    }

    private int info(CommandSender sender, String name) {
        Team team;
        if (name == null) {
            if (!(sender instanceof Player player)) {
                help(sender);
                return CommandSupport.OK;
            }
            team = this.registry.of(player.getUniqueId()).orElse(null);
            if (team == null) {
                this.feedback.send(sender, TeamProblem.NOT_IN_TEAM, null, null);
                return CommandSupport.OK;
            }
        } else {
            team = this.registry.byName(name).orElse(null);
            if (team == null) {
                this.messenger.send(sender, TeamsMessages.TEAM_NOT_FOUND, Arg.text("name", name));
                return CommandSupport.OK;
            }
        }
        if (sender instanceof Player player) {
            this.menus.showInfo(player, team, null);
            return CommandSupport.OK;
        }
        this.messenger.chat(sender, TeamsMessages.INFO_HEADER, Arg.text("name", team.name()));
        for (Component line : this.menus.infoLines(sender, team)) {
            sender.sendMessage(line);
        }
        List<String> names = new ArrayList<>();
        for (TeamMember member : team.sortedMembers()) {
            names.add(this.actions.name(member.uuid()) + " (" + this.services.lang().plain(TeamMenus.roleKey(member.role())) + ")");
        }
        this.messenger.chat(sender, TeamsMessages.INFO_MEMBER_NAMES, Arg.text("names", String.join(", ", names)));
        return CommandSupport.OK;
    }

    private int list(CommandSender sender, int page) {
        if (sender instanceof Player player) {
            this.menus.show(player, this.menus.listView(player, page, null));
            return CommandSupport.OK;
        }
        int pageSize = this.settings.get().pageSize();
        List<Team> teams = this.menus.sortedTeams();
        int pages = Math.max(1, (teams.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        this.messenger.chat(sender, TeamsMessages.LIST_HEADER, Arg.number("page", current), Arg.number("pages", pages),
            Arg.number("count", teams.size()));
        if (teams.isEmpty()) {
            this.messenger.chat(sender, TeamsMessages.LIST_EMPTY);
        }
        for (Team team : teams.subList(Math.min(teams.size(), (current - 1) * pageSize), Math.min(teams.size(), current * pageSize))) {
            this.messenger.chat(sender, TeamsMessages.LIST_LINE, Arg.text("name", team.name()), Arg.number("members", team.size()),
                Arg.number("online", this.presence.online(team, null)));
        }
        return CommandSupport.OK;
    }

    private int top(CommandSender sender, TeamTop.Board board) {
        if (sender instanceof Player player) {
            this.menus.show(player, this.menus.topView(player, board, null));
            return CommandSupport.OK;
        }
        this.messenger.chat(sender, board == TeamTop.Board.KILLS ? TeamsMessages.TOP_KILLS_HEADER : TeamsMessages.TOP_MONEY_HEADER);
        for (Component line : this.menus.topLines(board)) {
            sender.sendMessage(line);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /team admin

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
            .requires(CommandSupport.permission(TeamMenus.ADMIN_PERMISSION))
            .executes(ctx -> {
                this.messenger.chat(ctx.getSource().getSender(), TeamsMessages.HELP_ADMIN);
                return CommandSupport.OK;
            })
            .then(Commands.literal("add")
                .then(teamArgument().then(this.support.knownPlayer("player").executes(this::adminAdd))))
            .then(Commands.literal("kick")
                .then(this.support.knownPlayer("player").executes(this::adminKick)))
            .then(Commands.literal("transfer")
                .then(teamArgument().then(this.support.knownPlayer("player").executes(this::adminTransfer))))
            .then(Commands.literal("rename")
                .then(teamArgument().then(Commands.argument("name", StringArgumentType.word()).executes(this::adminRename))))
            .then(Commands.literal("delhome")
                .then(teamArgument().executes(this::adminDeleteHome)))
            .then(Commands.literal("disband")
                .then(teamArgument().executes(this::adminDisband)));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> teamArgument() {
        return Commands.argument("team", StringArgumentType.word()).suggests(this::allTeams);
    }

    /** The team named by the "team" argument, or null after telling the sender it doesn't exist. */
    private Team team(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "team");
        Team team = this.registry.byName(name).orElse(null);
        if (team == null) {
            this.messenger.send(ctx.getSource().getSender(), TeamsMessages.TEAM_NOT_FOUND, Arg.text("name", name));
        }
        return team;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private int adminAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Team team = team(ctx);
        Optional<UUID> player = team == null ? Optional.empty() : this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.actions.name(player.get());
        TeamService.Outcome outcome = this.service.adminAdd(team.id(), player.get());
        if (!outcome.ok()) {
            report(sender, outcome, name);
            return CommandSupport.OK;
        }
        Team after = outcome.team();
        this.messenger.chat(sender, TeamsMessages.ADMIN_ADDED, Arg.text("name", name), Arg.text("team", after.name()));
        Player online = Bukkit.getPlayer(player.get());
        if (online != null) {
            this.messenger.send(online, TeamsMessages.JOINED, Arg.text("team", after.name()));
        }
        this.actions.broadcast(after, player.get(), TeamsMessages.TEAM_JOINED, Arg.text("name", name));
        this.services.audit().record(actor(sender), "teams.add", player.get().toString(), "team " + after.id() + " " + after.name());
        return CommandSupport.OK;
    }

    private int adminKick(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.actions.name(player.get());
        TeamService.Outcome outcome = this.service.adminKick(player.get());
        if (!outcome.ok()) {
            switch (outcome.problem()) {
                case TARGET_NOT_MEMBER -> this.messenger.chat(sender, TeamsMessages.ADMIN_NOT_IN_TEAM, Arg.text("name", name));
                case OWNER_CANT_LEAVE -> this.messenger.chat(sender, TeamsMessages.ADMIN_IS_OWNER, Arg.text("name", name),
                    Arg.text("team", outcome.team().name()));
                default -> report(sender, outcome, name);
            }
            return CommandSupport.OK;
        }
        Team after = outcome.team();
        this.messenger.chat(sender, TeamsMessages.ADMIN_KICKED, Arg.text("name", name), Arg.text("team", after.name()));
        this.actions.removed(player.get(), after);
        this.actions.broadcast(after, null, TeamsMessages.TEAM_KICKED_STAFF, Arg.text("name", name));
        this.services.audit().record(actor(sender), "teams.kick", player.get().toString(), "team " + after.id() + " " + after.name());
        return CommandSupport.OK;
    }

    private int adminTransfer(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Team team = team(ctx);
        Optional<UUID> player = team == null ? Optional.empty() : this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.actions.name(player.get());
        TeamService.Outcome outcome = this.service.adminTransfer(team.id(), player.get());
        if (!outcome.ok()) {
            switch (outcome.problem()) {
                case TARGET_NOT_MEMBER -> this.messenger.chat(sender, TeamsMessages.ADMIN_NOT_MEMBER, Arg.text("name", name),
                    Arg.text("team", team.name()));
                case UNCHANGED -> this.messenger.chat(sender, TeamsMessages.ADMIN_ALREADY_OWNER, Arg.text("name", name));
                default -> report(sender, outcome, name);
            }
            return CommandSupport.OK;
        }
        Team after = outcome.team();
        this.messenger.chat(sender, TeamsMessages.ADMIN_TRANSFERRED, Arg.text("name", name), Arg.text("team", after.name()));
        // The new owner is always told (what they may do changed); the rest of the team as they picked.
        this.actions.news(after, Set.of(player.get()), TeamsMessages.TEAM_TRANSFERRED_STAFF, Arg.text("name", name));
        this.actions.refreshOwnerLater(player.get());
        this.services.audit().record(actor(sender), "teams.transfer", player.get().toString(), "team " + after.id() + " " + after.name());
        return CommandSupport.OK;
    }

    private int adminRename(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Team team = team(ctx);
        if (team == null) {
            return CommandSupport.OK;
        }
        String name = StringArgumentType.getString(ctx, "name");
        TeamService.Outcome outcome = this.service.adminRename(team.id(), name);
        if (!outcome.ok()) {
            report(sender, outcome, name);
            return CommandSupport.OK;
        }
        this.messenger.chat(sender, TeamsMessages.ADMIN_RENAMED, Arg.text("old", team.name()), Arg.text("name", outcome.team().name()));
        this.actions.broadcast(outcome.team(), null, TeamsMessages.TEAM_RENAMED, Arg.text("name", outcome.team().name()));
        this.services.audit().record(actor(sender), "teams.rename", Long.toString(team.id()), team.name() + " -> " + outcome.team().name());
        return CommandSupport.OK;
    }

    private int adminDeleteHome(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Team team = team(ctx);
        if (team == null) {
            return CommandSupport.OK;
        }
        TeamService.Outcome outcome = this.service.adminDeleteHome(team.id());
        if (!outcome.ok()) {
            report(sender, outcome, team.name());
            return CommandSupport.OK;
        }
        this.messenger.chat(sender, TeamsMessages.ADMIN_HOME_REMOVED, Arg.text("team", team.name()));
        this.actions.broadcast(outcome.team(), null, TeamsMessages.TEAM_HOME_REMOVED);
        this.services.audit().record(actor(sender), "teams.delhome", Long.toString(team.id()), team.name());
        return CommandSupport.OK;
    }

    private int adminDisband(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Team team = team(ctx);
        if (team == null) {
            return CommandSupport.OK;
        }
        TeamService.Outcome outcome = this.service.adminDisband(team.id());
        if (!outcome.ok()) {
            report(sender, outcome, team.name());
            return CommandSupport.OK;
        }
        Team old = outcome.team();
        this.actions.disbanded(old);
        this.messenger.chat(sender, TeamsMessages.ADMIN_DISBANDED, Arg.text("team", old.name()));
        this.actions.announce(old, null, TeamsMessages.TEAM_DISBANDED_STAFF, Arg.text("team", old.name()));
        this.services.audit().record(actor(sender), "teams.disband", Long.toString(old.id()), old.name() + ", " + old.size() + " members");
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /teamchat

    private SiftCommand teamChat() {
        return new SimpleCommand("teamchat", List.of("tc"), "Talk to your team", CHAT_PERMISSION,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(CHAT_PERMISSION))
                .executes(this.playerOnly((player, ctx) -> toggleChat(player)))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(this.playerOnly((player, ctx) -> {
                        TeamProblem problem = this.actions.sendChat(player, StringArgumentType.getString(ctx, "message"));
                        if (problem != null) {
                            this.feedback.send(player, problem, null, null);
                        }
                    }))));
    }

    // ------------------------------------------------------------------ names and suggestions

    /** The member of {@code team} with this name (ignoring case), or null. */
    private UUID member(Team team, String name) {
        for (UUID member : team.memberIds()) {
            if (this.actions.name(member).equalsIgnoreCase(name)) {
                return member;
            }
        }
        return null;
    }

    private CompletableFuture<Suggestions> teammates(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            String remaining = builder.getRemainingLowerCase();
            this.registry.of(player.getUniqueId()).ifPresent(team -> {
                for (UUID member : team.memberIds()) {
                    String name = this.actions.name(member);
                    if (!member.equals(player.getUniqueId()) && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            });
        }
        return builder.buildFuture();
    }

    /**
     * The players {@code /team invite} offers: online players the sender may see (never vanished staff, whom an invite
     * refuses anyway), leaving out those whose {@code team-invites} refuses the sender. Thread-safe (suggestions are
     * computed off the main threads; the settings, relations and vanish lookups are all safe from any thread).
     */
    private CompletableFuture<Suggestions> invitees(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
        String remaining = builder.getRemainingLowerCase();
        for (Player target : Bukkit.getOnlinePlayers()) {
            UUID id = target.getUniqueId();
            if (!target.getName().toLowerCase(Locale.ROOT).startsWith(remaining) || this.presence.hidden(id)) {
                continue;
            }
            if (sender instanceof Player player && (player.getUniqueId().equals(id) || !player.canSee(target)
                || !this.actions.takesInvitesFrom(id, player.getUniqueId()))) {
                continue;
            }
            builder.suggest(target.getName());
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> invitingTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            String remaining = builder.getRemainingLowerCase();
            for (Invites.Invite invite : this.service.invites().pendingFor(player.getUniqueId(), this.service.now())) {
                this.registry.get(invite.team()).ifPresent(team -> {
                    if (team.name().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(team.name());
                    }
                });
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> allTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        int count = 0;
        for (String name : this.registry.names()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
                if (++count >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return builder.buildFuture();
    }
}
