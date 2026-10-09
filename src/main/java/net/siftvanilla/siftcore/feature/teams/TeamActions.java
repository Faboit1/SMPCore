package net.siftvanilla.siftcore.feature.teams;

import io.papermc.paper.dialog.Dialog;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * What a player's team action does around the service call: on success it tells everyone concerned (the actor,
 * the team, the target) and updates chat mode and owner limits; on failure it changes nothing and returns the
 * outcome so the caller can explain it (on the action bar for commands, inside the dialog for menus).
 * Every method runs on the acting player's thread.
 */
final class TeamActions {

    static final String INVITE_COOLDOWN = "teams:invite";
    /** The chat feature's node for players who can't be ignored (staff); their invites always arrive. */
    static final String UNIGNORABLE = "siftcore.chat.unignorable";

    private final Services services;
    private final TeamService service;
    private final TeamRegistry registry;
    private final TeamChat chat;
    private final TeamFeedback feedback;
    private final OwnerLimits limits;
    private final Setting<TeamsSettings> settings;
    private final Messenger messenger;
    private final Lang lang;
    private final SpawnArea spawn;
    private volatile IgnoreLookup ignores = IgnoreLookup.NONE;
    private volatile Predicate<String> homesDisabled = world -> false;

    TeamActions(Services services, TeamService service, TeamChat chat, TeamFeedback feedback, OwnerLimits limits,
                Setting<TeamsSettings> settings, SpawnArea spawn) {
        this.services = services;
        this.service = service;
        this.registry = service.registry();
        this.chat = chat;
        this.feedback = feedback;
        this.limits = limits;
        this.settings = settings;
        this.messenger = services.messenger();
        this.lang = services.lang();
        this.spawn = spawn;
    }

    /** Installs the ignore lists (the chat feature is built after teams). */
    void ignores(IgnoreLookup ignores) {
        this.ignores = ignores;
    }

    /** Installs the worlds where /sethome is turned off (the homes feature is built after teams). */
    void homeWorlds(Predicate<String> disabled) {
        this.homesDisabled = disabled;
    }

    /** Whether team homes are off in this world: teams.yml's home.disabled-worlds or homes.yml's disabled-worlds. */
    private boolean homeDisabled(String world) {
        return this.settings.get().homeDisabled(world) || this.homesDisabled.test(world);
    }

    // ------------------------------------------------------------------ create

    /** Creates a team for the cost the player agreed to (refused if the configured cost changed since). */
    TeamService.Outcome create(Player player, String name, long cost) {
        TeamService.Outcome outcome = this.service.create(player.getUniqueId(), name, cost);
        if (!outcome.ok()) {
            return outcome;
        }
        Team team = outcome.team();
        if (cost > 0) {
            this.messenger.send(player, TeamsMessages.CREATED, Arg.text("name", team.name()), Arg.money("cost", cost));
        } else {
            this.messenger.send(player, TeamsMessages.CREATED_FREE, Arg.text("name", team.name()));
        }
        outcome.stored().whenComplete((ignored, error) -> {
            if (error != null) {
                this.messenger.send(player, CoreMessages.ACTION_FAILED);
            }
        });
        this.limits.refresh(player);
        return outcome;
    }

    // ------------------------------------------------------------------ invites

    /** Time left before the player may invite again, or zero. Does not start the cooldown. */
    Duration inviteCooldown(Player player) {
        if (player.hasPermission("siftcore.bypass.cooldown")) {
            return Duration.ZERO;
        }
        return this.services.cooldowns().remaining(player.getUniqueId(), INVITE_COOLDOWN);
    }

    TeamService.Outcome invite(Player inviter, Player target) {
        if (this.ignores.ignores(target.getUniqueId(), inviter.getUniqueId()) && !inviter.hasPermission(UNIGNORABLE)) {
            return TeamService.Outcome.fail(TeamProblem.INVITE_BLOCKED);
        }
        // Who may invite the target is their choice (team-invites); refused like an ignore, with the same words.
        if (!acceptsInvite(this.services.settings().get(target.getUniqueId(), TeamPrefs.INVITES), target.getUniqueId(), inviter.getUniqueId())) {
            return TeamService.Outcome.fail(TeamProblem.INVITES_CLOSED);
        }
        TeamService.Outcome outcome = this.service.invite(inviter.getUniqueId(), target.getUniqueId());
        if (!outcome.ok()) {
            return outcome;
        }
        this.services.cooldowns().start(inviter.getUniqueId(), INVITE_COOLDOWN, this.settings.get().inviteCooldown());
        Duration ttl = this.settings.get().inviteExpiry();
        this.messenger.send(inviter, TeamsMessages.INVITE_SENT, Arg.text("name", target.getName()), Arg.time("time", ttl));
        Team team = outcome.team();
        Dialog dialog = this.services.dialogs().inline(target, inviteView(target, team, inviter.getName()));
        Component message = this.lang.get(TeamsMessages.INVITE_RECEIVED, Arg.text("inviter", inviter.getName()),
                Arg.text("team", team.name()))
            .clickEvent(ClickEvent.showDialog(dialog))
            .hoverEvent(HoverEvent.showText(this.lang.get(TeamsMessages.INVITE_HOVER)));
        target.sendMessage(message);
        this.messenger.feedback(target, Feedback.NOTIFY);
        return outcome;
    }

    /**
     * The invite answer dialog (also embedded in the invite chat message). Join and Decline both finish it, so the
     * client closes it at once on either.
     */
    View inviteView(Player viewer, Team team, String inviterName) {
        Invites.Invite invite = this.service.invites().get(viewer.getUniqueId(), team.id(), this.service.now());
        Duration left = invite == null ? this.settings.get().inviteExpiry()
            : Duration.ofMillis(invite.remainingMillis(this.service.now()));
        long teamId = team.id();
        List<Component> body = this.lang.lines(TeamsMessages.INVITE_BODY,
            Arg.text("inviter", inviterName),
            Arg.text("team", team.name()),
            Arg.number("members", team.size()),
            this.feedback.limit("limit", team),
            Arg.time("time", left));
        return this.services.templates().confirm(this.lang.get(TeamsMessages.INVITE_TITLE), body,
            this.lang.get(TeamsMessages.INVITE_JOIN), this.lang.get(TeamsMessages.INVITE_DECLINE),
            submission -> {
                submission.close();
                TeamService.Outcome outcome = accept(submission.player(), teamId);
                if (!outcome.ok()) {
                    this.feedback.send(submission.player(), outcome.problem(), outcome.team(), team.name());
                }
            },
            submission -> {
                submission.close();
                TeamService.Outcome outcome = decline(submission.player(), teamId);
                if (!outcome.ok()) {
                    this.feedback.send(submission.player(), outcome.problem(), outcome.team(), team.name());
                }
            }).closing();
    }

    TeamService.Outcome accept(Player player, long teamId) {
        TeamService.Outcome outcome = this.service.join(player.getUniqueId(), teamId);
        if (!outcome.ok()) {
            return outcome;
        }
        Team team = outcome.team();
        this.messenger.send(player, TeamsMessages.JOINED, Arg.text("team", team.name()));
        broadcast(team, player.getUniqueId(), TeamsMessages.TEAM_JOINED, Arg.text("name", player.getName()));
        return outcome;
    }

    TeamService.Outcome decline(Player player, long teamId) {
        TeamService.Outcome outcome = this.service.decline(player.getUniqueId(), teamId);
        if (!outcome.ok()) {
            return outcome;
        }
        String teamName = outcome.team() == null ? "" : outcome.team().name();
        this.messenger.send(player, TeamsMessages.INVITE_DECLINED, Arg.text("team", teamName));
        Player inviter = outcome.subject() == null ? null : Bukkit.getPlayer(outcome.subject());
        if (inviter != null) {
            this.messenger.send(inviter, TeamsMessages.INVITE_DECLINED_INVITER, Arg.text("name", player.getName()));
        }
        return outcome;
    }

    // ------------------------------------------------------------------ membership

    TeamService.Outcome leave(Player player) {
        TeamService.Outcome outcome = this.service.leave(player.getUniqueId());
        if (!outcome.ok()) {
            return outcome;
        }
        this.chat.off(player.getUniqueId());
        this.messenger.send(player, TeamsMessages.LEFT, Arg.text("team", outcome.team().name()));
        broadcast(outcome.team(), null, TeamsMessages.TEAM_LEFT, Arg.text("name", player.getName()));
        return outcome;
    }

    TeamService.Outcome kick(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.kick(actor.getUniqueId(), target);
        if (!outcome.ok()) {
            return outcome;
        }
        removed(target, outcome.team());
        news(outcome.team(), Set.of(actor.getUniqueId()), TeamsMessages.TEAM_KICKED, Arg.text("name", name(target)),
            Arg.text("actor", actor.getName()));
        return outcome;
    }

    /** Tells a player who was removed from a team, and turns their team chat off. */
    void removed(UUID player, Team team) {
        this.chat.off(player);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.messenger.send(online, TeamsMessages.KICKED, Arg.text("team", team.name()));
        }
    }

    TeamService.Outcome promote(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.promote(actor.getUniqueId(), target);
        if (outcome.ok()) {
            news(outcome.team(), Set.of(actor.getUniqueId()), TeamsMessages.TEAM_PROMOTED, Arg.text("name", name(target)),
                Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome demote(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.demote(actor.getUniqueId(), target);
        if (outcome.ok()) {
            news(outcome.team(), Set.of(actor.getUniqueId()), TeamsMessages.TEAM_DEMOTED, Arg.text("name", name(target)),
                Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome transfer(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.transfer(actor.getUniqueId(), target);
        if (outcome.ok()) {
            // The new owner is always told too: what they may do changed.
            news(outcome.team(), Set.of(actor.getUniqueId(), target), TeamsMessages.TEAM_TRANSFERRED, Arg.text("name", name(target)),
                Arg.text("actor", actor.getName()));
            refreshOwnerLater(target);
        }
        return outcome;
    }

    TeamService.Outcome disband(Player actor) {
        TeamService.Outcome outcome = this.service.disband(actor.getUniqueId());
        if (outcome.ok()) {
            Team old = outcome.team();
            disbanded(old);
            announce(old, null, TeamsMessages.TEAM_DISBANDED, Arg.text("team", old.name()), Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    /** Turns team chat off for every former member of a disbanded team. */
    void disbanded(Team old) {
        for (UUID member : old.memberIds()) {
            this.chat.off(member);
        }
    }

    /** Re-reads a new owner's rank limit on their thread, if they are online. */
    void refreshOwnerLater(UUID owner) {
        this.limits.refreshLater(owner);
    }

    // ------------------------------------------------------------------ home, friendly fire, chat

    /** Sets the team home where the actor stands, where /sethome would allow a home (not at spawn, not in a disabled world). */
    TeamService.Outcome setHome(Player actor) {
        Location location = actor.getLocation();
        TeamHome home = new TeamHome(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
        TeamService.Outcome outcome = this.service.setHome(actor.getUniqueId(), home,
            homeDisabled(home.world()), this.spawn.contains(location));
        if (outcome.ok()) {
            news(outcome.team(), Set.of(actor.getUniqueId()), TeamsMessages.TEAM_HOME_SET, Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome friendlyFire(Player actor, Boolean on) {
        TeamService.Outcome outcome = this.service.friendlyFire(actor.getUniqueId(), on);
        if (outcome.ok()) {
            news(outcome.team(), Set.of(actor.getUniqueId()), outcome.team().friendlyFire() ? TeamsMessages.TEAM_FRIENDLY_FIRE_ON
                : TeamsMessages.TEAM_FRIENDLY_FIRE_OFF, Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    /** Starts the warmup to the team home; returns why it could not start, or null. */
    TeamProblem home(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return TeamProblem.NOT_IN_TEAM;
        }
        if (team.home() == null) {
            return TeamProblem.NO_HOME;
        }
        World world = Bukkit.getWorld(team.home().world());
        if (world == null) {
            return TeamProblem.HOME_WORLD_MISSING;
        }
        TeamProblem refused = TeamRules.useHome(homeDisabled(team.home().world()), this.spawn.contains(location(world, team.home())));
        if (refused != null) {
            return refused;
        }
        this.services.teleports().teleport(player, "team_home", this.settings.get().homeWarmup(),
            () -> CompletableFuture.completedFuture(destination(player)), null);
        return null;
    }

    /** The home at the end of the warmup, checked again (the player may have left, the home may have moved). */
    private Location destination(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        TeamProblem problem = team == null ? TeamProblem.NOT_IN_TEAM : team.home() == null ? TeamProblem.NO_HOME : null;
        World world = problem == null ? Bukkit.getWorld(team.home().world()) : null;
        if (problem == null && world == null) {
            problem = TeamProblem.HOME_WORLD_MISSING;
        }
        Location home = problem == null ? location(world, team.home()) : null;
        if (problem == null) {
            problem = TeamRules.useHome(homeDisabled(team.home().world()), this.spawn.contains(home));
        }
        if (problem != null) {
            this.feedback.send(player, problem, team, null);
            return null;
        }
        return home;
    }

    private static Location location(World world, TeamHome home) {
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }

    /** Switches team chat mode; returns why it could not, or null. */
    TeamProblem toggleChat(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            this.chat.off(player.getUniqueId());
            return TeamProblem.NOT_IN_TEAM;
        }
        boolean on = this.chat.toggle(player.getUniqueId(), team);
        this.messenger.send(player, on ? TeamsMessages.CHAT_ON : TeamsMessages.CHAT_OFF);
        return null;
    }

    /** Sends one team chat message; returns why it could not, or null. */
    TeamProblem sendChat(Player player, String message) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return TeamProblem.NOT_IN_TEAM;
        }
        this.chat.send(player, team, message);
        return null;
    }

    boolean inChatMode(Player player) {
        return this.chat.inChatMode(player.getUniqueId());
    }

    /** Whether the target's {@code team-invites} choice lets the inviter invite them (friends ask the friends feature). */
    private boolean acceptsInvite(Audience audience, UUID target, UUID inviter) {
        return this.services.relations().allows(audience, target, inviter);
    }

    /**
     * Whether {@code target} takes team invites from {@code inviter} ({@code team-invites}), for suggestions that leave
     * out players an invite would be refused for. Thread-safe.
     */
    boolean takesInvitesFrom(UUID target, UUID inviter) {
        return acceptsInvite(this.services.settings().get(target, TeamPrefs.INVITES), target, inviter);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Team news to every online member of a team except {@code except}, in the style each one picked
     * ({@code team-notices}: chat, above the hotbar, or off).
     */
    void broadcast(Team team, UUID except, MessageKey key, Arg... args) {
        for (UUID member : team.memberIds()) {
            if (!member.equals(except)) {
                tell(member, false, key, args);
            }
        }
    }

    /**
     * Team news about something a member did: every online member gets it in the style they picked
     * ({@code team-notices}), but the members in {@code told} (the one who did it, and a new owner) always get it. For
     * the actor the line is the only confirmation that their command went through, so with team news off it comes in
     * chat ({@link #newsStyle}).
     */
    void news(Team team, Set<UUID> told, MessageKey key, Arg... args) {
        for (UUID member : team.memberIds()) {
            tell(member, told.contains(member), key, args);
        }
    }

    private void tell(UUID member, boolean told, MessageKey key, Arg... args) {
        Player online = Bukkit.getPlayer(member);
        if (online != null) {
            this.messenger.alert(online, newsStyle(this.services.settings().get(member, TeamPrefs.NOTICES), told), key, args);
        }
    }

    /**
     * How a member sees one piece of team news: as they picked ({@code team-notices}), except that a member who must be
     * told (the actor's confirmation, a new owner) gets it in chat instead of not at all.
     */
    static AlertStyle newsStyle(AlertStyle picked, boolean told) {
        return told && picked == AlertStyle.OFF ? AlertStyle.CHAT : picked;
    }

    /** News every member must see (the team was disbanded): a chat line whatever their {@code team-notices}. */
    void announce(Team team, UUID except, MessageKey key, Arg... args) {
        for (UUID member : team.memberIds()) {
            if (member.equals(except)) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                this.messenger.send(online, key, args);
            }
        }
    }

    String name(UUID player) {
        Player online = Bukkit.getPlayer(player);
        return online != null ? online.getName() : this.services.directory().name(player);
    }
}
