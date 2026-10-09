package net.siftvanilla.siftcore.feature.teams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The team dialogs. {@code /team} opens the main dialog: without a team it offers creating one and lists open
 * invites; with a team it shows the members, home and friendly fire, and the buttons the player's role allows.
 * Actions that need a target open a form (invite) or a member choice (kick, promote, demote, transfer); lasting
 * decisions ask for confirmation. Every handler re-checks through the service: a dialog only shows state, it never
 * proves it. After an action the main dialog is shown again, fresh, with the reason inside it if something was
 * refused.
 * <p>
 * Screens with a member list ({@link #showMain}, {@link #showInfo}) first read who may see each offline member's
 * last-seen time ({@code seen-privacy}, {@link TeamSeen}); the dialog that was clicked stays on screen meanwhile.
 */
final class TeamMenus {

    static final String CREATE_PERMISSION = "siftcore.teams.create";
    static final String ADMIN_PERMISSION = "siftcore.admin.teams";

    /** Actions that pick a member. */
    enum Pick {
        KICK,
        PROMOTE,
        DEMOTE,
        TRANSFER
    }

    private final Services services;
    private final TeamService service;
    private final TeamRegistry registry;
    private final TeamActions actions;
    private final TeamFeedback feedback;
    private final TeamTop top;
    private final StatsRecorder stats;
    private final TeamPresence presence;
    private final Setting<TeamsSettings> settings;
    private final TeamSeen seen;
    private final Lang lang;
    private final Templates templates;
    private final Logger logger;

    TeamMenus(Services services, TeamService service, TeamActions actions, TeamFeedback feedback, TeamTop top,
              StatsRecorder stats, TeamPresence presence, Setting<TeamsSettings> settings, TeamSeen seen) {
        this.services = services;
        this.service = service;
        this.registry = service.registry();
        this.actions = actions;
        this.feedback = feedback;
        this.top = top;
        this.stats = stats;
        this.presence = presence;
        this.settings = settings;
        this.seen = seen;
        this.lang = services.lang();
        this.templates = services.templates();
        this.logger = services.plugin().getLogger();
    }

    void open(Player player) {
        showMain(player, null);
    }

    void show(Player player, View view) {
        this.services.dialogs().show(player, view);
    }

    /**
     * Shows the main dialog, with an error line when {@code error} is not null. Without a team it shows at once; in a
     * team it shows once the members' last-seen times are read. Call on the player's thread.
     */
    void showMain(Player player, Component error) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            show(player, withError(noTeam(player), error));
            return;
        }
        withRoster(player, team, visible -> main(player, error, visible));
    }

    /**
     * The main dialog as it is now (the team may have changed while the last-seen times were read: members not in
     * {@code visible} show as offline without a time).
     */
    private View main(Player player, Component error, Set<UUID> visible) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        return withError(team == null ? noTeam(player) : teamView(player, team, visible), error);
    }

    /** The no-team main dialog with the reason a team action was refused (the player turned out to have no team). */
    private View noTeamAfter(Player player, TeamProblem problem) {
        return withError(noTeam(player), error(problem));
    }

    private static View withError(View view, Component error) {
        return error == null ? view : view.withError(error, FormValues.EMPTY);
    }

    /** Why an action was refused, or null when it went through. */
    private Component error(TeamService.Outcome outcome, String target) {
        return outcome.ok() ? null : this.feedback.component(outcome.problem(), outcome.team(), target);
    }

    private Component error(TeamProblem problem) {
        return problem == null ? null : this.feedback.component(problem, null, null);
    }

    /**
     * Builds a screen with the team's member list once it is known whose last-seen time the viewer may see, on the
     * viewer's thread, and shows it. The dialog the viewer clicked stays on screen meanwhile; a failed read closes it
     * and tells them. Call on the viewer's thread (the staff check).
     */
    private void withRoster(Player viewer, Team team, Function<Set<UUID>, View> build) {
        UUID self = viewer.getUniqueId();
        List<UUID> offline = new ArrayList<>(team.size());
        for (UUID member : team.memberIds()) {
            if (!this.presence.online(member, self)) {
                offline.add(member);
            }
        }
        this.services.dialogs().markShown(viewer);
        this.seen.visible(viewer, offline).whenComplete((visible, error) -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (error != null) {
                this.logger.log(Level.WARNING, "Reading the last-seen privacy of team " + team.name() + " for " + viewer.getName()
                    + " failed", error);
                this.services.messenger().send(viewer, CoreMessages.ACTION_FAILED);
                this.services.dialogs().close(viewer);
                return;
            }
            onThread(viewer, () -> {
                if (viewer.isOnline()) {
                    show(viewer, build.apply(visible));
                }
            });
        });
    }

    private void onThread(Player player, Runnable task) {
        if (this.services.scheduler().owns(player)) {
            task.run();
        } else {
            this.services.scheduler().entity(player, task, null);
        }
    }

    // ------------------------------------------------------------------ main: no team

    private View noTeam(Player player) {
        long cost = this.settings.get().createCost();
        List<Component> lines = new ArrayList<>(cost > 0
            ? this.lang.lines(TeamsMessages.MENU_NONE, Arg.money("cost", cost))
            : this.lang.lines(TeamsMessages.MENU_NONE_FREE));
        List<Button> buttons = new ArrayList<>();
        if (player.hasPermission(CREATE_PERMISSION)) {
            Component tooltip = cost > 0 ? this.lang.get(TeamsMessages.BUTTON_CREATE_TOOLTIP, Arg.money("cost", cost)) : null;
            buttons.add(Button.of(this.lang.get(TeamsMessages.BUTTON_CREATE), tooltip, s -> s.show(createForm(s.player(), ""))).width(150));
        }
        long now = this.service.now();
        List<Invites.Invite> invites = this.service.invites().pendingFor(player.getUniqueId(), now);
        boolean header = false;
        for (Invites.Invite invite : invites) {
            Team team = this.registry.get(invite.team()).orElse(null);
            if (team == null) {
                continue;
            }
            if (!header) {
                lines.add(Component.empty());
                lines.add(this.lang.get(TeamsMessages.MENU_INVITES));
                header = true;
            }
            String inviter = this.actions.name(invite.inviter());
            lines.add(this.lang.get(TeamsMessages.MENU_INVITE_LINE, Arg.text("team", team.name()), Arg.text("inviter", inviter),
                Arg.time("time", Duration.ofMillis(invite.remainingMillis(now)))));
            buttons.add(Button.of(this.lang.get(TeamsMessages.BUTTON_ANSWER_INVITE, Arg.text("team", team.name())),
                s -> s.show(this.actions.inviteView(s.player(), team, inviter))).width(150));
        }
        buttons.add(Button.of(this.lang.get(TeamsMessages.BUTTON_LIST), s -> s.show(listView(s.player(), 1, toMain()))).width(150));
        buttons.add(Button.of(this.lang.get(TeamsMessages.BUTTON_TOP), s -> s.show(topView(s.player(), TeamTop.Board.KILLS, toMain()))).width(150));
        return this.templates.list(this.lang.get(TeamsMessages.MENU_TITLE), lines, buttons, 2, toHub());
    }

    // ------------------------------------------------------------------ main: in a team

    private View teamView(Player player, Team team, Set<UUID> visible) {
        UUID self = player.getUniqueId();
        TeamRole role = team.role(self);
        List<Component> lines = new ArrayList<>(this.lang.lines(TeamsMessages.MENU_SUMMARY,
            Arg.text("owner", this.actions.name(team.owner())),
            Arg.number("members", team.size()),
            this.feedback.limit("limit", team),
            Arg.number("online", this.presence.online(team, self))));
        lines.add(homeLine(team));
        lines.add(this.lang.get(team.friendlyFire() ? TeamsMessages.MENU_FRIENDLY_FIRE_ON : TeamsMessages.MENU_FRIENDLY_FIRE_OFF));
        boolean chatOn = this.actions.inChatMode(player);
        if (chatOn) {
            lines.add(this.lang.get(TeamsMessages.MENU_CHAT_ON));
        }
        lines.add(Component.empty());
        lines.add(this.lang.get(TeamsMessages.MENU_MEMBERS));
        lines.addAll(memberLines(team, self, visible));

        List<Button> buttons = new ArrayList<>();
        if (team.home() != null) {
            buttons.add(button(TeamsMessages.BUTTON_HOME, this.lang.get(TeamsMessages.BUTTON_HOME_TOOLTIP,
                Arg.time("time", this.settings.get().homeWarmup())), s -> {
                    TeamProblem problem = this.actions.home(s.player());
                    if (problem == null) {
                        s.close();
                    } else {
                        showMain(s.player(), error(problem));
                    }
                }));
        }
        buttons.add(button(chatOn ? TeamsMessages.BUTTON_CHAT_OFF : TeamsMessages.BUTTON_CHAT_ON,
            this.lang.get(TeamsMessages.BUTTON_CHAT_TOOLTIP), s -> showMain(s.player(), error(this.actions.toggleChat(s.player())))));
        if (role != null && role.atLeast(TeamRole.ADMIN)) {
            buttons.add(button(TeamsMessages.BUTTON_INVITE, null, s -> s.show(inviteForm(s.player(), ""))));
            buttons.add(button(TeamsMessages.BUTTON_KICK, null, s -> s.show(picker(s.player(), Pick.KICK))));
            buttons.add(button(TeamsMessages.BUTTON_SET_HOME, this.lang.get(TeamsMessages.BUTTON_SET_HOME_TOOLTIP),
                s -> showMain(s.player(), error(this.actions.setHome(s.player()), null))));
            buttons.add(button(team.friendlyFire() ? TeamsMessages.BUTTON_FRIENDLY_FIRE_OFF : TeamsMessages.BUTTON_FRIENDLY_FIRE_ON,
                this.lang.get(TeamsMessages.BUTTON_FRIENDLY_FIRE_TOOLTIP),
                s -> showMain(s.player(), error(this.actions.friendlyFire(s.player(), !team.friendlyFire()), null))));
        }
        if (role == TeamRole.OWNER) {
            buttons.add(button(TeamsMessages.BUTTON_PROMOTE, null, s -> s.show(picker(s.player(), Pick.PROMOTE))));
            buttons.add(button(TeamsMessages.BUTTON_DEMOTE, null, s -> s.show(picker(s.player(), Pick.DEMOTE))));
            buttons.add(button(TeamsMessages.BUTTON_TRANSFER, this.lang.get(TeamsMessages.BUTTON_TRANSFER_TOOLTIP),
                s -> s.show(picker(s.player(), Pick.TRANSFER))));
            buttons.add(button(TeamsMessages.BUTTON_DISBAND, null, s -> s.show(confirmDisband(s.player()))));
        } else {
            buttons.add(button(TeamsMessages.BUTTON_LEAVE, null, s -> s.show(confirmLeave(s.player()))));
        }
        long teamId = team.id();
        buttons.add(button(TeamsMessages.BUTTON_STATS, null, s -> this.registry.get(teamId).ifPresentOrElse(
            current -> showInfo(s.player(), current, toMain()),
            () -> showMain(s.player(), null))));
        buttons.add(button(TeamsMessages.BUTTON_TOP, null, s -> s.show(topView(s.player(), TeamTop.Board.KILLS, toMain()))));
        buttons.add(button(TeamsMessages.BUTTON_LIST, null, s -> s.show(listView(s.player(), 1, toMain()))));
        return this.templates.list(this.lang.get(TeamsMessages.MENU_TEAM_TITLE, Arg.text("name", team.name())), lines, buttons, 2, toHub());
    }

    private Button button(MessageKey label, Component tooltip, Button.Handler handler) {
        return Button.of(this.lang.get(label), tooltip, handler).width(150);
    }

    private Component homeLine(Team team) {
        TeamHome home = team.home();
        if (home == null) {
            return this.lang.get(TeamsMessages.MENU_NO_HOME);
        }
        return this.lang.get(TeamsMessages.MENU_HOME, Arg.number("x", home.blockX()), Arg.number("y", home.blockY()),
            Arg.number("z", home.blockZ()), Arg.text("world", home.world()));
    }

    /**
     * One line per member, owner first: name, role and whether they are online (or when they were last seen), as
     * {@code viewer} may see it: vanished members show as offline, and an offline member's last-seen time only shows
     * when they are in {@code visible} (their {@code seen-privacy} lets the viewer see it, see {@link TeamSeen}).
     */
    List<Component> memberLines(Team team, UUID viewer, Set<UUID> visible) {
        List<Component> lines = new ArrayList<>(team.size());
        long now = System.currentTimeMillis();
        for (TeamMember member : team.sortedMembers()) {
            Arg name = Arg.text("name", this.actions.name(member.uuid()));
            Arg role = Arg.text("role", this.lang.plain(roleKey(member.role())));
            if (this.presence.online(member.uuid(), viewer)) {
                lines.add(this.lang.get(TeamsMessages.MENU_MEMBER_ONLINE, name, role));
            } else if (visible.contains(member.uuid())) {
                long seen = this.services.directory().get(member.uuid()).map(known -> known.lastSeen()).orElse(member.joined());
                lines.add(this.lang.get(TeamsMessages.MENU_MEMBER_OFFLINE, name, role,
                    Arg.time("time", Duration.ofMillis(Math.max(0, now - seen)))));
            } else {
                lines.add(this.lang.get(TeamsMessages.MENU_MEMBER_OFFLINE_HIDDEN, name, role));
            }
        }
        return lines;
    }

    static MessageKey roleKey(TeamRole role) {
        return switch (role) {
            case OWNER -> TeamsMessages.ROLE_OWNER;
            case ADMIN -> TeamsMessages.ROLE_ADMIN;
            case MEMBER -> TeamsMessages.ROLE_MEMBER;
        };
    }

    private Button.Handler toMain() {
        return s -> showMain(s.player(), null);
    }

    /** Back to the main menu of the hub, or null (a Close button) when there is none. */
    private Button.Handler toHub() {
        HubEntry menu = this.services.hub().get("menu");
        return menu == null ? null : s -> menu.open().accept(s.player());
    }

    // ------------------------------------------------------------------ create

    View createForm(Player player, String initial) {
        TeamsSettings s = this.settings.get();
        List<Component> body = s.createCost() > 0
            ? this.lang.lines(TeamsMessages.CREATE_FORM_BODY, Arg.number("min", s.nameMinLength()), Arg.number("max", s.nameMaxLength()),
                Arg.money("cost", s.createCost()))
            : this.lang.lines(TeamsMessages.CREATE_FORM_BODY_FREE, Arg.number("min", s.nameMinLength()), Arg.number("max", s.nameMaxLength()));
        return this.templates.form(this.lang.get(TeamsMessages.CREATE_FORM_TITLE), body,
            List.of(Templates.text("name", this.lang.get(TeamsMessages.CREATE_FORM_NAME), initial, TeamNames.MAX_LENGTH)),
            this.lang.get(TeamsMessages.CREATE_FORM_BUTTON),
            submission -> {
                Player actor = submission.player();
                String name = submission.values().text("name");
                TeamProblem problem = this.service.checkCreate(actor.getUniqueId(), name);
                if (problem != null) {
                    submission.error(this.feedback.component(problem, null, name));
                    return;
                }
                long cost = this.settings.get().createCost();
                if (cost > 0) {
                    submission.show(confirmCreate(actor, name, null));
                } else {
                    showMain(actor, error(this.actions.create(actor, name, 0), name));
                }
            },
            submission -> showMain(submission.player(), null));
    }

    /**
     * Asks to confirm the cost of starting a team (only used while starting one costs money). The handler charges
     * exactly the cost shown here; if a reload changed it in the meantime nothing is charged and the new cost is
     * shown instead.
     */
    View confirmCreate(Player player, String name, Component error) {
        long cost = this.settings.get().createCost();
        View view = this.templates.confirm(this.lang.get(TeamsMessages.CREATE_CONFIRM_TITLE),
            this.lang.lines(TeamsMessages.CREATE_CONFIRM_BODY, Arg.text("name", name), Arg.money("cost", cost)),
            this.lang.get(TeamsMessages.CREATE_CONFIRM_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            submission -> {
                Player actor = submission.player();
                TeamService.Outcome outcome = this.actions.create(actor, name, cost);
                if (outcome.problem() == TeamProblem.COST_CHANGED) {
                    afterCostChange(actor, name);
                } else {
                    showMain(actor, error(outcome, name));
                }
            },
            submission -> showMain(submission.player(), null));
        return withError(view, error);
    }

    /** After the cost changed under a confirmation: the new cost to confirm, or the new team when starting one is free now. */
    private void afterCostChange(Player player, String name) {
        if (this.settings.get().createCost() > 0) {
            show(player, confirmCreate(player, name, this.feedback.component(TeamProblem.COST_CHANGED, null, name)));
            return;
        }
        showMain(player, error(this.actions.create(player, name, 0), name));
    }

    // ------------------------------------------------------------------ invite

    View inviteForm(Player player, String initial) {
        return this.templates.form(this.lang.get(TeamsMessages.INVITE_FORM_TITLE),
            this.lang.lines(TeamsMessages.INVITE_FORM_BODY, Arg.time("time", this.settings.get().inviteExpiry())),
            List.of(Templates.text("player", this.lang.get(TeamsMessages.INVITE_FORM_PLAYER), initial, 16)),
            this.lang.get(TeamsMessages.INVITE_FORM_BUTTON),
            submission -> {
                String name = submission.values().text("player");
                Player target = Bukkit.getPlayerExact(name);
                if (target == null || !this.services.commands().canSee(submission.player(), target)
                    || this.presence.hidden(target.getUniqueId())) {
                    submission.error(this.lang.get(CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", name)));
                    return;
                }
                Duration wait = this.actions.inviteCooldown(submission.player());
                if (!wait.isZero()) {
                    submission.error(this.lang.get(CoreMessages.COOLDOWN, Arg.time("time", wait)));
                    return;
                }
                TeamService.Outcome outcome = this.actions.invite(submission.player(), target);
                if (!outcome.ok()) {
                    submission.error(this.feedback.component(outcome.problem(), outcome.team(), target.getName()));
                    return;
                }
                showMain(submission.player(), null);
            },
            submission -> showMain(submission.player(), null));
    }

    // ------------------------------------------------------------------ member pickers

    View picker(Player player, Pick pick) {
        UUID self = player.getUniqueId();
        Team team = this.registry.of(self).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        TeamRole actor = team.role(self);
        List<Input.Option> options = new ArrayList<>();
        for (TeamMember member : team.sortedMembers()) {
            if (member.uuid().equals(self) || !eligible(pick, actor, member.role())) {
                continue;
            }
            options.add(new Input.Option(member.uuid().toString(), this.lang.get(TeamsMessages.PICK_OPTION,
                Arg.text("name", this.actions.name(member.uuid())), Arg.text("role", this.lang.plain(roleKey(member.role()))))));
        }
        MessageKey title = switch (pick) {
            case KICK -> TeamsMessages.PICK_KICK_TITLE;
            case PROMOTE -> TeamsMessages.PICK_PROMOTE_TITLE;
            case DEMOTE -> TeamsMessages.PICK_DEMOTE_TITLE;
            case TRANSFER -> TeamsMessages.PICK_TRANSFER_TITLE;
        };
        if (options.isEmpty()) {
            return this.templates.notice(this.lang.get(title), List.of(this.lang.get(TeamsMessages.PICK_NOBODY)),
                this.lang.get(CoreMessages.UI_BACK), s -> showMain(s.player(), null));
        }
        MessageKey label = switch (pick) {
            case KICK -> TeamsMessages.PICK_KICK_BUTTON;
            case PROMOTE -> TeamsMessages.PICK_PROMOTE_BUTTON;
            case DEMOTE -> TeamsMessages.PICK_DEMOTE_BUTTON;
            case TRANSFER -> TeamsMessages.PICK_TRANSFER_BUTTON;
        };
        return this.templates.form(this.lang.get(title), List.of(),
            List.of(Templates.choice("member", this.lang.get(TeamsMessages.PICK_LABEL), options, options.getFirst().id())),
            this.lang.get(label),
            submission -> {
                UUID target;
                try {
                    target = UUID.fromString(submission.values().choice("member"));
                } catch (IllegalArgumentException e) {
                    showMain(submission.player(), null);
                    return;
                }
                String name = this.actions.name(target);
                Player actorPlayer = submission.player();
                switch (pick) {
                    case KICK -> showMain(actorPlayer, error(this.actions.kick(actorPlayer, target), name));
                    case PROMOTE -> showMain(actorPlayer, error(this.actions.promote(actorPlayer, target), name));
                    case DEMOTE -> showMain(actorPlayer, error(this.actions.demote(actorPlayer, target), name));
                    case TRANSFER -> submission.show(confirmTransfer(actorPlayer, target));
                }
            },
            submission -> showMain(submission.player(), null));
    }

    private static boolean eligible(Pick pick, TeamRole actor, TeamRole target) {
        return switch (pick) {
            case KICK -> TeamRules.kick(actor, target) == null;
            case PROMOTE -> TeamRules.promote(actor, target) == null;
            case DEMOTE -> TeamRules.demote(actor, target) == null;
            case TRANSFER -> TeamRules.transfer(actor, target, false) == null;
        };
    }

    // ------------------------------------------------------------------ confirmations

    View confirmTransfer(Player player, UUID target) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        String name = this.actions.name(target);
        return this.templates.confirm(this.lang.get(TeamsMessages.TRANSFER_TITLE),
            this.lang.lines(TeamsMessages.TRANSFER_BODY, Arg.text("name", name), Arg.text("team", team.name())),
            this.lang.get(TeamsMessages.TRANSFER_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.transfer(s.player(), target), name)),
            s -> showMain(s.player(), null));
    }

    View confirmDisband(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        return this.templates.confirm(this.lang.get(TeamsMessages.DISBAND_TITLE),
            this.lang.lines(TeamsMessages.DISBAND_BODY, Arg.text("team", team.name())),
            this.lang.get(TeamsMessages.DISBAND_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.disband(s.player()), null)),
            s -> showMain(s.player(), null));
    }

    View confirmLeave(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        return this.templates.confirm(this.lang.get(TeamsMessages.LEAVE_TITLE),
            this.lang.lines(TeamsMessages.LEAVE_BODY, Arg.text("team", team.name())),
            this.lang.get(TeamsMessages.LEAVE_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.leave(s.player()), null)),
            s -> showMain(s.player(), null));
    }

    // ------------------------------------------------------------------ info, list, leaderboard

    /**
     * Stats of a team: dialog lines (with icons) for a player, plain chat lines for the console. The home is shown
     * only to its members and staff.
     */
    List<Component> infoLines(CommandSender viewer, Team team) {
        UUID viewerId = viewer instanceof Player player ? player.getUniqueId() : null;
        boolean dialog = viewerId != null;
        long kills = TeamTop.total(team, member -> this.stats.get(member, StatsRecorder.Stat.KILLS));
        long deaths = TeamTop.total(team, member -> this.stats.get(member, StatsRecorder.Stat.DEATHS));
        long money = TeamTop.total(team, member -> this.services.ledger().balance(member, Currency.MONEY));
        List<Component> lines = new ArrayList<>(this.lang.lines(dialog ? TeamsMessages.INFO_BODY : TeamsMessages.INFO_TEXT,
            Arg.text("owner", this.actions.name(team.owner())),
            Arg.number("members", team.size()),
            this.feedback.limit("limit", team),
            Arg.number("online", this.presence.online(team, viewerId)),
            Arg.number("kills", kills),
            Arg.number("deaths", deaths),
            Arg.money("money", money),
            Arg.time("age", Duration.ofMillis(Math.max(0, System.currentTimeMillis() - team.created())))));
        int killsRank = this.top.rank(TeamTop.Board.KILLS, team.id());
        int moneyRank = this.top.rank(TeamTop.Board.MONEY, team.id());
        lines.add(this.lang.get(TeamsMessages.INFO_PLACES,
            killsRank > 0 ? Arg.number("kills", killsRank) : Arg.text("kills", "-"),
            moneyRank > 0 ? Arg.number("money", moneyRank) : Arg.text("money", "-")));
        lines.add(this.lang.get(team.friendlyFire() ? TeamsMessages.MENU_FRIENDLY_FIRE_ON : TeamsMessages.MENU_FRIENDLY_FIRE_OFF));
        boolean insider = viewer.hasPermission(ADMIN_PERMISSION) || viewerId != null && team.isMember(viewerId);
        if (insider) {
            lines.add(dialog ? homeLine(team) : homeText(team));
        }
        return lines;
    }

    private Component homeText(Team team) {
        TeamHome home = team.home();
        if (home == null) {
            return this.lang.get(TeamsMessages.INFO_NO_HOME_TEXT);
        }
        return this.lang.get(TeamsMessages.INFO_HOME_TEXT, Arg.number("x", home.blockX()), Arg.number("y", home.blockY()),
            Arg.number("z", home.blockZ()), Arg.text("world", home.world()));
    }

    /**
     * Shows a team's stats and members once it is known whose last-seen time the viewer may see. Call on the viewer's
     * thread.
     */
    void showInfo(Player viewer, Team team, Button.Handler back) {
        withRoster(viewer, team, visible -> infoView(viewer, team, back, visible));
    }

    private View infoView(Player viewer, Team team, Button.Handler back, Set<UUID> visible) {
        List<Component> lines = new ArrayList<>(infoLines(viewer, team));
        lines.add(Component.empty());
        lines.add(this.lang.get(TeamsMessages.MENU_MEMBERS));
        lines.addAll(memberLines(team, viewer.getUniqueId(), visible));
        return this.templates.list(this.lang.get(TeamsMessages.MENU_TEAM_TITLE, Arg.text("name", team.name())), lines,
            List.of(), 1, back);
    }

    /** Teams by size, then name. */
    List<Team> sortedTeams() {
        List<Team> teams = this.registry.all();
        teams.sort(Comparator.comparingInt(Team::size).reversed().thenComparing(Team::name, String.CASE_INSENSITIVE_ORDER));
        return teams;
    }

    View listView(Player player, int page, Button.Handler back) {
        int pageSize = this.settings.get().pageSize();
        List<Team> teams = sortedTeams();
        int pages = Math.max(1, (teams.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        List<Team> slice = teams.subList(Math.min(teams.size(), (current - 1) * pageSize), Math.min(teams.size(), current * pageSize));
        List<Component> lines = new ArrayList<>();
        if (teams.isEmpty()) {
            lines.add(this.lang.get(TeamsMessages.LIST_EMPTY));
        } else {
            lines.add(this.lang.get(TeamsMessages.LIST_PAGE, Arg.number("page", current), Arg.number("pages", pages),
                Arg.number("count", teams.size())));
        }
        List<Button> buttons = new ArrayList<>();
        Button.Handler self = s -> s.show(listView(s.player(), current, back));
        for (Team team : slice) {
            long id = team.id();
            buttons.add(Button.of(Component.text(team.name()),
                this.lang.get(TeamsMessages.LIST_BUTTON_TOOLTIP, Arg.number("members", team.size()), Arg.number("online", this.presence.online(team, player.getUniqueId()))),
                s -> this.registry.get(id).ifPresentOrElse(fresh -> showInfo(s.player(), fresh, self),
                    () -> s.show(listView(s.player(), current, back))))
                .width(150));
        }
        if (current > 1) {
            buttons.add(Button.of(this.lang.get(TeamsMessages.PAGE_PREVIOUS), s -> s.show(listView(s.player(), current - 1, back))).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(this.lang.get(TeamsMessages.PAGE_NEXT), s -> s.show(listView(s.player(), current + 1, back))).width(150));
        }
        return this.templates.list(this.lang.get(TeamsMessages.LIST_TITLE), lines, buttons, 2, back);
    }

    View topView(Player player, TeamTop.Board board, Button.Handler back) {
        List<Component> lines = topLines(board);
        Team mine = this.registry.of(player.getUniqueId()).orElse(null);
        if (mine != null) {
            this.top.entry(board, mine.id()).ifPresent(entry -> {
                lines.add(Component.empty());
                lines.add(board == TeamTop.Board.KILLS
                    ? this.lang.get(TeamsMessages.TOP_KILLS_YOU, Arg.number("rank", entry.rank()), Arg.number("value", entry.value()))
                    : this.lang.get(TeamsMessages.TOP_MONEY_YOU, Arg.number("rank", entry.rank()), Arg.money("value", entry.value())));
            });
        }
        TeamTop.Board other = board == TeamTop.Board.KILLS ? TeamTop.Board.MONEY : TeamTop.Board.KILLS;
        Button switchBoard = Button.of(this.lang.get(other == TeamTop.Board.KILLS ? TeamsMessages.TOP_BY_KILLS : TeamsMessages.TOP_BY_MONEY),
            s -> s.show(topView(s.player(), other, back))).width(Templates.WIDE);
        return this.templates.list(this.lang.get(board == TeamTop.Board.KILLS ? TeamsMessages.TOP_KILLS_TITLE : TeamsMessages.TOP_MONEY_TITLE),
            lines, List.of(switchBoard), 1, back);
    }

    /** The board as lines (shared by the dialog and the console). */
    List<Component> topLines(TeamTop.Board board) {
        List<Component> lines = new ArrayList<>();
        List<TeamTop.Entry> entries = this.top.top(board);
        if (entries.isEmpty()) {
            lines.add(this.lang.get(TeamsMessages.TOP_EMPTY));
        }
        for (TeamTop.Entry entry : entries) {
            lines.add(board == TeamTop.Board.KILLS
                ? this.lang.get(TeamsMessages.TOP_KILLS_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                    Arg.number("value", entry.value()))
                : this.lang.get(TeamsMessages.TOP_MONEY_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                    Arg.money("value", entry.value())));
        }
        return lines;
    }
}
