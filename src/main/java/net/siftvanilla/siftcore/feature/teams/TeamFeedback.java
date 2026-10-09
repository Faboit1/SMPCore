package net.siftvanilla.siftcore.feature.teams;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;

/** Turns a {@link TeamProblem} into its message, sent on its channel or shown inside a dialog. */
final class TeamFeedback {

    /** A message and its arguments. */
    record Line(MessageKey key, Arg... args) {
    }

    private final Messenger messenger;
    private final Lang lang;
    private final Setting<TeamsSettings> settings;
    private final TeamService service;

    TeamFeedback(Messenger messenger, Setting<TeamsSettings> settings, TeamService service) {
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.settings = settings;
        this.service = service;
    }

    /**
     * The message for a problem.
     *
     * @param team   the team concerned, if known
     * @param target the name the player typed or picked (a player or team name), if any
     */
    Line explain(TeamProblem problem, Team team, String target) {
        String name = target == null ? "" : target;
        String teamName = team != null ? team.name() : name;
        TeamsSettings s = this.settings.get();
        return switch (problem) {
            case NOT_IN_TEAM -> new Line(TeamsMessages.NOT_IN_TEAM);
            case ALREADY_IN_TEAM -> new Line(TeamsMessages.ALREADY_IN_TEAM);
            case OWNER_ONLY -> new Line(TeamsMessages.OWNER_ONLY);
            case ADMINS_ONLY -> new Line(TeamsMessages.ADMINS_ONLY);
            case NOT_YOURSELF -> new Line(CoreMessages.NOT_YOURSELF);
            case TARGET_NOT_MEMBER -> new Line(TeamsMessages.TARGET_NOT_MEMBER, Arg.text("name", name));
            case TARGET_IN_TEAM -> new Line(TeamsMessages.TARGET_IN_TEAM, Arg.text("name", name));
            case TARGET_ALREADY_MEMBER -> new Line(TeamsMessages.TARGET_ALREADY_MEMBER, Arg.text("name", name));
            case RANK_TOO_HIGH -> new Line(TeamsMessages.RANK_TOO_HIGH, Arg.text("name", name));
            case ALREADY_ADMIN -> new Line(TeamsMessages.ALREADY_ADMIN, Arg.text("name", name));
            case NOT_ADMIN -> new Line(TeamsMessages.NOT_ADMIN, Arg.text("name", name));
            case OWNER_CANT_LEAVE -> new Line(TeamsMessages.OWNER_CANT_LEAVE);
            case TEAM_FULL -> team == null
                ? new Line(TeamsMessages.OTHER_TEAM_FULL, Arg.text("team", teamName))
                : new Line(TeamsMessages.TEAM_FULL, Arg.number("members", team.size()), limit("limit", team));
            case JOIN_TEAM_FULL -> new Line(TeamsMessages.OTHER_TEAM_FULL, Arg.text("team", teamName));
            case TEAM_GONE -> new Line(TeamsMessages.TEAM_GONE);
            case NO_INVITE -> new Line(TeamsMessages.NO_INVITE, Arg.text("team", teamName));
            case INVITE_EXPIRED -> new Line(TeamsMessages.INVITE_EXPIRED);
            case ALREADY_INVITED -> new Line(TeamsMessages.ALREADY_INVITED, Arg.text("name", name));
            case INVITE_BLOCKED -> new Line(TeamsMessages.INVITE_BLOCKED, Arg.text("name", name));
            case TOO_MANY_INVITES -> new Line(TeamsMessages.TOO_MANY_INVITES);
            case NO_HOME -> new Line(TeamsMessages.NO_HOME);
            case HOME_WORLD_MISSING -> new Line(TeamsMessages.HOME_WORLD_MISSING);
            case HOME_WORLD_DISABLED -> new Line(TeamsMessages.HOME_WORLD_DISABLED);
            case HOME_IN_SPAWN -> new Line(TeamsMessages.HOME_IN_SPAWN);
            case HOME_AT_SPAWN -> new Line(TeamsMessages.HOME_AT_SPAWN);
            case NAME_TOO_SHORT, NAME_TOO_LONG -> new Line(TeamsMessages.NAME_LENGTH,
                Arg.number("min", s.nameMinLength()), Arg.number("max", s.nameMaxLength()));
            case NAME_CHARACTERS -> new Line(TeamsMessages.NAME_CHARACTERS);
            case NAME_BLOCKED -> new Line(TeamsMessages.NAME_BLOCKED);
            case NAME_TAKEN -> new Line(TeamsMessages.NAME_TAKEN, Arg.text("name", name));
            case NAME_UNCHANGED -> new Line(TeamsMessages.NAME_UNCHANGED);
            case NOT_ENOUGH_MONEY -> new Line(CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", s.createCost()));
            case COST_CHANGED -> new Line(TeamsMessages.COST_CHANGED);
            case ECONOMY_UNAVAILABLE -> new Line(CoreMessages.ECONOMY_UNAVAILABLE);
            case CANCELLED -> new Line(TeamsMessages.CANCELLED);
            case UNCHANGED -> team != null
                ? new Line(team.friendlyFire() ? TeamsMessages.FRIENDLY_FIRE_ALREADY_ON : TeamsMessages.FRIENDLY_FIRE_ALREADY_OFF)
                : new Line(CoreMessages.ACTION_FAILED);
            case FAILED -> new Line(CoreMessages.ACTION_FAILED);
        };
    }

    /** Sends the problem on its message's channel (the action bar for players, chat for the console). */
    void send(Audience to, TeamProblem problem, Team team, String target) {
        Line line = explain(problem, team, target);
        this.messenger.send(to, line.key(), line.args());
    }

    /** The problem as a component, for an error line inside a dialog. */
    Component component(TeamProblem problem, Team team, String target) {
        Line line = explain(problem, team, target);
        return this.lang.get(line.key(), line.args());
    }

    /** A member limit as an argument ("unlimited" when there is none). */
    Arg limit(String name, Team team) {
        int limit = this.service.memberLimit(team);
        return limit == TeamRules.UNLIMITED
            ? Arg.text(name, this.lang.plain(TeamsMessages.UNLIMITED))
            : Arg.number(name, limit);
    }
}
