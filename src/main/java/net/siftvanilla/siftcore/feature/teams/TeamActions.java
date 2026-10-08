package net.siftvanilla.siftcore.feature.teams;

import io.papermc.paper.dialog.Dialog;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
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

    private final Services services;
    private final TeamService service;
    private final TeamRegistry registry;
    private final TeamChat chat;
    private final TeamFeedback feedback;
    private final OwnerLimits limits;
    private final Setting<TeamsSettings> settings;
    private final Messenger messenger;
    private final Lang lang;

    TeamActions(Services services, TeamService service, TeamChat chat, TeamFeedback feedback, OwnerLimits limits,
                Setting<TeamsSettings> settings) {
        this.services = services;
        this.service = service;
        this.registry = service.registry();
        this.chat = chat;
        this.feedback = feedback;
        this.limits = limits;
        this.settings = settings;
        this.messenger = services.messenger();
        this.lang = services.lang();
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

    /** The invite answer dialog (also embedded in the invite chat message). */
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
            });
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
        broadcast(outcome.team(), null, TeamsMessages.TEAM_KICKED, Arg.text("name", name(target)), Arg.text("actor", actor.getName()));
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
            broadcast(outcome.team(), null, TeamsMessages.TEAM_PROMOTED, Arg.text("name", name(target)), Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome demote(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.demote(actor.getUniqueId(), target);
        if (outcome.ok()) {
            broadcast(outcome.team(), null, TeamsMessages.TEAM_DEMOTED, Arg.text("name", name(target)), Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome transfer(Player actor, UUID target) {
        TeamService.Outcome outcome = this.service.transfer(actor.getUniqueId(), target);
        if (outcome.ok()) {
            broadcast(outcome.team(), null, TeamsMessages.TEAM_TRANSFERRED, Arg.text("name", name(target)),
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
            broadcast(old, null, TeamsMessages.TEAM_DISBANDED, Arg.text("team", old.name()), Arg.text("actor", actor.getName()));
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

    TeamService.Outcome setHome(Player actor) {
        Location location = actor.getLocation();
        TeamHome home = new TeamHome(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
        TeamService.Outcome outcome = this.service.setHome(actor.getUniqueId(), home);
        if (outcome.ok()) {
            broadcast(outcome.team(), null, TeamsMessages.TEAM_HOME_SET, Arg.text("actor", actor.getName()));
        }
        return outcome;
    }

    TeamService.Outcome friendlyFire(Player actor, Boolean on) {
        TeamService.Outcome outcome = this.service.friendlyFire(actor.getUniqueId(), on);
        if (outcome.ok()) {
            broadcast(outcome.team(), null, outcome.team().friendlyFire() ? TeamsMessages.TEAM_FRIENDLY_FIRE_ON
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
        if (Bukkit.getWorld(team.home().world()) == null) {
            return TeamProblem.HOME_WORLD_MISSING;
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
        if (problem != null) {
            this.feedback.send(player, problem, team, null);
            return null;
        }
        TeamHome home = team.home();
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }

    /** Switches team chat mode; returns why it could not, or null. */
    TeamProblem toggleChat(Player player) {
        if (this.registry.of(player.getUniqueId()).isEmpty()) {
            this.chat.off(player.getUniqueId());
            return TeamProblem.NOT_IN_TEAM;
        }
        boolean on = this.chat.toggle(player.getUniqueId());
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

    // ------------------------------------------------------------------ helpers

    /** Sends a message to every online member of a team except {@code except}. */
    void broadcast(Team team, UUID except, MessageKey key, Arg... args) {
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
