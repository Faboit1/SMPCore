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
import net.siftvanilla.siftcore.core.WorldNames;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The team dialogs, in the dialog style: buttons whose tooltips say what they do, a few status lines at most, nothing
 * paged (the dialogs scroll).
 * <ul>
 *   <li>{@code /team} without a team: Start a team, the open invites, All teams and Top teams;</li>
 *   <li>{@code /team} in a team: the owner and member count, then Team home, the Team chat and Friendly fire switches
 *       ("Friendly fire: OFF", flipped at once; greyed for members, who can't change it), Members, Invite a player and
 *       Set home here (admins), Team stats, Top teams, All teams, Settings and Leave or Disband;</li>
 *   <li>the members: one button per member ("Alex: owner, online"); clicking one opens that member, where the owner
 *       and admins find Make admin, Make member, Remove from the team and Hand over the team, as their role allows;</li>
 *   <li>team stats, All teams (the biggest {@code list-size}) and Top teams (a "Ranked by" choice).</li>
 * </ul>
 * Lasting decisions ask first. Every handler re-checks through the service: a dialog only shows state, it never proves
 * it. After an action the dialog it came from is shown again, fresh, with the reason in red if something was refused.
 * The members list first reads whose last-seen time the viewer may see ({@code seen-privacy}, {@link TeamSeen}); the
 * dialog that was clicked stays on screen meanwhile.
 */
final class TeamMenus {

    static final String CREATE_PERMISSION = "siftcore.teams.create";
    static final String ADMIN_PERMISSION = "siftcore.admin.teams";

    /** What a member's dialog offers its viewer for that member. */
    record MemberActions(boolean promote, boolean demote, boolean kick, boolean transfer) {

        static final MemberActions NONE = new MemberActions(false, false, false, false);

        boolean any() {
            return this.promote || this.demote || this.kick || this.transfer;
        }
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

    /** Shows the main dialog, with an error line in red when {@code error} is not null. Call on the player's thread. */
    void showMain(Player player, Component error) {
        show(player, withError(main(player), error));
    }

    /** The main dialog as it is now: the team's, or the one for players without a team. */
    private View main(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        return team == null ? noTeam(player) : teamView(player, team);
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

    /** The no-team main dialog with the reason a team action was refused (the player turned out to have no team). */
    private View noTeamAfter(Player player, TeamProblem problem) {
        return withError(noTeam(player), error(problem));
    }

    /**
     * Builds a screen with the team's members once it is known whose last-seen time the viewer may see, on the
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

    private Component ui(MessageKey key, Arg... args) {
        return this.lang.get(key, args);
    }

    /** A number as text for a value the lang line colours itself ({@code <accent>}). */
    private static Arg count(String name, long value) {
        return Arg.text(name, Lang.number(value));
    }

    /** A time as text for a value the lang line colours itself ({@code <accent>}). */
    private static Arg time(String name, Duration value) {
        return Arg.text(name, Durations.format(value));
    }

    private Button button(MessageKey label, MessageKey tooltip, Button.Handler handler) {
        return Button.of(ui(label), tooltip == null ? null : ui(tooltip), handler);
    }

    // ------------------------------------------------------------------ main: no team

    private View noTeam(Player player) {
        List<Button> buttons = new ArrayList<>();
        if (player.hasPermission(CREATE_PERMISSION)) {
            buttons.add(Button.of(ui(TeamsMessages.BUTTON_CREATE), createTooltip(TeamsMessages.BUTTON_CREATE_TOOLTIP, null,
                TeamsMessages.BUTTON_CREATE_COST_TOOLTIP), s -> s.show(createForm(s.player(), ""))));
        }
        long now = this.service.now();
        for (Invites.Invite invite : this.service.invites().pendingFor(player.getUniqueId(), now)) {
            Team team = this.registry.get(invite.team()).orElse(null);
            if (team == null) {
                continue;
            }
            String inviter = this.actions.name(invite.inviter());
            buttons.add(Button.of(ui(TeamsMessages.BUTTON_ANSWER_INVITE, Arg.text("team", team.name())),
                ui(TeamsMessages.BUTTON_ANSWER_INVITE_TOOLTIP, Arg.text("inviter", inviter),
                    time("time", Duration.ofMillis(invite.remainingMillis(now)))),
                s -> s.show(this.actions.inviteView(s.player(), team, inviter))));
        }
        buttons.add(button(TeamsMessages.BUTTON_LIST, TeamsMessages.BUTTON_LIST_TOOLTIP, s -> s.show(listView(s.player(), toMain()))));
        buttons.add(button(TeamsMessages.BUTTON_TOP, TeamsMessages.BUTTON_TOP_TOOLTIP,
            s -> s.show(topView(s.player(), TeamTop.Board.KILLS, toMain()))));
        return this.templates.grid(ui(TeamsMessages.MENU_TITLE), List.of(ui(TeamsMessages.MENU_NONE)), buttons, toHub());
    }

    /**
     * What starting a team does, plus its cost while it has one (none is named while starting a team is free).
     *
     * @param args the main line's arguments, or null
     */
    private Component createTooltip(MessageKey main, Arg[] args, MessageKey costLine) {
        long cost = this.settings.get().createCost();
        Component first = args == null ? ui(main) : ui(main, args);
        return cost > 0 ? Templates.lines(List.of(first, ui(costLine, Arg.money("cost", cost)))) : first;
    }

    // ------------------------------------------------------------------ main: in a team

    private View teamView(Player player, Team team) {
        UUID self = player.getUniqueId();
        TeamRole role = team.role(self);
        boolean admin = role != null && role.atLeast(TeamRole.ADMIN);
        List<Component> lines = this.lang.lines(TeamsMessages.MENU_SUMMARY,
            Arg.text("owner", this.actions.name(team.owner())),
            count("members", team.size()),
            this.feedback.limitText("limit", team),
            count("online", this.presence.online(team, self)));

        List<Button> buttons = new ArrayList<>();
        TeamHome home = team.home();
        if (home != null) {
            buttons.add(Button.of(ui(TeamsMessages.BUTTON_HOME), ui(TeamsMessages.BUTTON_HOME_TOOLTIP, count("x", home.blockX()),
                count("y", home.blockY()), count("z", home.blockZ()), Arg.text("world", WorldNames.of(this.lang, home.world())),
                time("time", this.settings.get().homeWarmup())), s -> {
                    TeamProblem problem = this.actions.home(s.player());
                    if (problem == null) {
                        s.close();
                    } else {
                        showMain(s.player(), error(problem));
                    }
                }).closes());
        }
        buttons.add(this.templates.switchButton(ui(TeamsMessages.BUTTON_CHAT), this.actions.inChatMode(player),
            ui(TeamsMessages.BUTTON_CHAT_TOOLTIP), s -> showMain(s.player(), error(this.actions.toggleChat(s.player(), false)))));
        boolean friendlyFire = team.friendlyFire();
        Button.Handler flipFire = s -> showMain(s.player(), error(this.actions.friendlyFire(s.player(), !friendlyFire, true), null));
        buttons.add(admin
            ? this.templates.switchButton(ui(TeamsMessages.BUTTON_FRIENDLY_FIRE), friendlyFire, ui(TeamsMessages.BUTTON_FRIENDLY_FIRE_TOOLTIP),
                flipFire)
            : Button.of(ui(TeamsMessages.BUTTON_FRIENDLY_FIRE_LOCKED, Arg.component("value", this.templates.state(friendlyFire))),
                ui(TeamsMessages.BUTTON_FRIENDLY_FIRE_LOCKED_TOOLTIP), flipFire));
        long teamId = team.id();
        buttons.add(this.templates.choiceButton(ui(TeamsMessages.BUTTON_MEMBERS),
            Component.text(Lang.number(team.size()) + "/" + limitShort(team)),
            ui(admin ? TeamsMessages.BUTTON_MEMBERS_MANAGE_TOOLTIP : TeamsMessages.BUTTON_MEMBERS_TOOLTIP),
            s -> this.registry.get(teamId).ifPresentOrElse(current -> showMembers(s.player(), current, toMain()),
                () -> showMain(s.player(), null))));
        if (admin) {
            buttons.add(Button.of(ui(TeamsMessages.BUTTON_INVITE),
                ui(TeamsMessages.BUTTON_INVITE_TOOLTIP, time("time", this.settings.get().inviteExpiry())),
                s -> s.show(inviteForm(s.player(), ""))));
            buttons.add(button(TeamsMessages.BUTTON_SET_HOME, TeamsMessages.BUTTON_SET_HOME_TOOLTIP,
                s -> showMain(s.player(), error(this.actions.setHome(s.player()), null))));
        }
        buttons.add(button(TeamsMessages.BUTTON_STATS, TeamsMessages.BUTTON_STATS_TOOLTIP, s -> this.registry.get(teamId).ifPresentOrElse(
            current -> showInfo(s.player(), current, toMain()),
            () -> showMain(s.player(), null))));
        buttons.add(button(TeamsMessages.BUTTON_TOP, TeamsMessages.BUTTON_TOP_TOOLTIP,
            s -> s.show(topView(s.player(), TeamTop.Board.KILLS, toMain()))));
        buttons.add(button(TeamsMessages.BUTTON_LIST, TeamsMessages.BUTTON_LIST_TOOLTIP, s -> s.show(listView(s.player(), toMain()))));
        buttons.add(button(TeamsMessages.BUTTON_SETTINGS, TeamsMessages.BUTTON_SETTINGS_TOOLTIP, s -> {
            if (!this.services.settings().screens().open(s.player(), SettingCategories.SOCIAL.id(), back -> showMain(back, null))) {
                showMain(s.player(), null);
            }
        }));
        if (role == TeamRole.OWNER) {
            buttons.add(button(TeamsMessages.BUTTON_DISBAND, TeamsMessages.BUTTON_DISBAND_TOOLTIP, s -> s.show(confirmDisband(s.player()))));
        } else {
            buttons.add(button(TeamsMessages.BUTTON_LEAVE, TeamsMessages.BUTTON_LEAVE_TOOLTIP, s -> s.show(confirmLeave(s.player()))));
        }
        return this.templates.grid(ui(TeamsMessages.MENU_TEAM_TITLE, Arg.text("name", team.name())), lines, buttons, toHub());
    }

    /** The member limit for a button value ("5", or "unlimited"). */
    private String limitShort(Team team) {
        int limit = this.service.memberLimit(team);
        return limit == TeamRules.UNLIMITED ? this.lang.plain(TeamsMessages.UNLIMITED) : Lang.number(limit);
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

    // ------------------------------------------------------------------ members

    /**
     * The members of a team, one button each, once it is known whose last-seen time the viewer may see. Call on the
     * viewer's thread.
     */
    void showMembers(Player viewer, Team team, Button.Handler back) {
        withRoster(viewer, team, visible -> membersView(viewer, team.id(), visible, back, null));
    }

    /**
     * The members dialog as the team is now (it may have changed while the last-seen times were read: members not in
     * {@code visible} show as offline without a time), or the main dialog when the team is gone.
     */
    private View membersView(Player viewer, long teamId, Set<UUID> visible, Button.Handler back, Component error) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return withError(main(viewer), error(TeamProblem.TEAM_GONE));
        }
        UUID self = viewer.getUniqueId();
        long now = System.currentTimeMillis();
        List<Button> buttons = new ArrayList<>(team.size());
        for (TeamMember member : team.sortedMembers()) {
            UUID id = member.uuid();
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ui(TeamsMessages.MEMBERS_TOOLTIP, time("time", Duration.ofMillis(Math.max(0, now - member.joined())))));
            if (id.equals(self)) {
                tooltip.add(ui(TeamsMessages.MEMBERS_TOOLTIP_YOU));
            } else if (actionsFor(team.role(self), member.role()).any()) {
                tooltip.add(ui(TeamsMessages.MEMBERS_TOOLTIP_MANAGE));
            }
            buttons.add(Button.of(memberLabel(member, self, visible, now), Templates.lines(tooltip),
                s -> s.show(memberView(s.player(), teamId, id, visible, back, null))));
        }
        View view = this.templates.column(ui(TeamsMessages.MEMBERS_TITLE, Arg.text("team", team.name())), buttons, back);
        return withError(view, error);
    }

    /**
     * A member's button label: name, role and whether they are online (or when they were last seen), as {@code viewer}
     * may see it: vanished members show as offline, and an offline member's last-seen time only shows when they are in
     * {@code visible} (their {@code seen-privacy} lets the viewer see it, see {@link TeamSeen}).
     */
    Component memberLabel(TeamMember member, UUID viewer, Set<UUID> visible, long now) {
        Arg name = Arg.text("name", this.actions.name(member.uuid()));
        Arg role = Arg.text("role", this.lang.plain(roleKey(member.role())));
        if (this.presence.online(member.uuid(), viewer)) {
            return ui(TeamsMessages.MEMBERS_ONLINE, name, role);
        }
        if (visible.contains(member.uuid())) {
            return ui(TeamsMessages.MEMBERS_OFFLINE, name, role, time("time", Duration.ofMillis(Math.max(0, now - lastSeen(member)))));
        }
        return ui(TeamsMessages.MEMBERS_OFFLINE_HIDDEN, name, role);
    }

    private long lastSeen(TeamMember member) {
        return this.services.directory().get(member.uuid()).map(known -> known.lastSeen()).orElse(member.joined());
    }

    /** What an actor of role {@code actor} may do to a member of role {@code target} from their dialog. */
    static MemberActions actionsFor(TeamRole actor, TeamRole target) {
        if (actor == null || target == null) {
            return MemberActions.NONE;
        }
        return new MemberActions(TeamRules.promote(actor, target) == null, TeamRules.demote(actor, target) == null,
            TeamRules.kick(actor, target) == null, TeamRules.transfer(actor, target, false) == null);
    }

    /** One member: role, online or last seen, how long in the team, and what the viewer may do to them. */
    private View memberView(Player viewer, long teamId, UUID memberId, Set<UUID> visible, Button.Handler back, Component error) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return withError(main(viewer), error(TeamProblem.TEAM_GONE));
        }
        String name = this.actions.name(memberId);
        TeamMember member = team.sortedMembers().stream().filter(m -> m.uuid().equals(memberId)).findFirst().orElse(null);
        if (member == null) {
            return membersView(viewer, teamId, visible, back, ui(TeamsMessages.MEMBER_GONE, Arg.text("name", name)));
        }
        UUID self = viewer.getUniqueId();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        lines.add(ui(TeamsMessages.MEMBER_ROLE, Arg.text("role", this.lang.plain(roleKey(member.role())))));
        if (this.presence.online(memberId, self)) {
            lines.add(ui(TeamsMessages.MEMBER_VIEW_ONLINE));
        } else if (visible.contains(memberId)) {
            lines.add(ui(TeamsMessages.MEMBER_SEEN, time("time", Duration.ofMillis(Math.max(0, now - lastSeen(member))))));
        } else {
            lines.add(ui(TeamsMessages.MEMBER_VIEW_OFFLINE));
        }
        lines.add(ui(TeamsMessages.MEMBER_JOINED, time("time", Duration.ofMillis(Math.max(0, now - member.joined())))));
        MemberActions can = memberId.equals(self) ? MemberActions.NONE : actionsFor(team.role(self), member.role());
        Button.Handler again = s -> s.show(memberView(s.player(), teamId, memberId, visible, back, null));
        List<Button> buttons = new ArrayList<>();
        if (can.promote()) {
            buttons.add(button(TeamsMessages.MEMBER_PROMOTE, TeamsMessages.MEMBER_PROMOTE_TOOLTIP, s -> s.show(memberView(s.player(), teamId,
                memberId, visible, back, error(this.actions.promote(s.player(), memberId), name)))));
        }
        if (can.demote()) {
            buttons.add(button(TeamsMessages.MEMBER_DEMOTE, TeamsMessages.MEMBER_DEMOTE_TOOLTIP, s -> s.show(memberView(s.player(), teamId,
                memberId, visible, back, error(this.actions.demote(s.player(), memberId), name)))));
        }
        if (can.kick()) {
            buttons.add(button(TeamsMessages.MEMBER_KICK, TeamsMessages.MEMBER_KICK_TOOLTIP,
                s -> s.show(confirmKick(team, memberId, visible, back))));
        }
        if (can.transfer()) {
            buttons.add(button(TeamsMessages.MEMBER_TRANSFER, TeamsMessages.MEMBER_TRANSFER_TOOLTIP,
                s -> s.show(confirmTransfer(s.player(), memberId, again))));
        }
        View view = this.templates.column(Component.text(name), lines, buttons, members(teamId, visible, back));
        return withError(view, error);
    }

    /** Back to the members dialog (with the last-seen times already read). */
    private Button.Handler members(long teamId, Set<UUID> visible, Button.Handler back) {
        return s -> s.show(membersView(s.player(), teamId, visible, back, null));
    }

    /**
     * "Remove Alex from Alpha?": Remove shows the members again; a refusal shows the member's dialog with the reason in
     * red. Cancel returns to the member.
     */
    private View confirmKick(Team team, UUID target, Set<UUID> visible, Button.Handler back) {
        String name = this.actions.name(target);
        long teamId = team.id();
        return this.templates.confirm(ui(TeamsMessages.MEMBER_KICK_TITLE),
            List.of(ui(TeamsMessages.MEMBER_KICK_BODY, Arg.text("name", name), Arg.text("team", team.name()))),
            ui(TeamsMessages.MEMBER_KICK_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> {
                Component refused = error(this.actions.kick(s.player(), target), name);
                s.show(refused == null ? membersView(s.player(), teamId, visible, back, null)
                    : memberView(s.player(), teamId, target, visible, back, refused));
            },
            s -> s.show(memberView(s.player(), teamId, target, visible, back, null)));
    }

    // ------------------------------------------------------------------ create

    View createForm(Player player, String initial) {
        TeamsSettings s = this.settings.get();
        View form = this.templates.form(ui(TeamsMessages.CREATE_FORM_TITLE), List.of(),
            List.of(Templates.text("name", ui(TeamsMessages.CREATE_FORM_NAME), initial, TeamNames.MAX_LENGTH)),
            ui(TeamsMessages.CREATE_FORM_BUTTON),
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
        return tooltips(form, createTooltip(TeamsMessages.CREATE_FORM_BUTTON_TOOLTIP,
            new Arg[] {count("min", s.nameMinLength()), count("max", s.nameMaxLength())}, TeamsMessages.CREATE_FORM_COST_TOOLTIP));
    }

    /**
     * Asks to confirm the cost of starting a team (only used while starting one costs money). The handler charges
     * exactly the cost shown here; if a reload changed it in the meantime nothing is charged and the new cost is
     * shown instead.
     */
    View confirmCreate(Player player, String name, Component error) {
        long cost = this.settings.get().createCost();
        View view = this.templates.confirm(ui(TeamsMessages.CREATE_CONFIRM_TITLE),
            this.lang.lines(TeamsMessages.CREATE_CONFIRM_BODY, Arg.text("name", name), Arg.money("cost", cost)),
            ui(TeamsMessages.CREATE_CONFIRM_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
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
        View form = this.templates.form(ui(TeamsMessages.INVITE_FORM_TITLE), List.of(),
            List.of(Templates.text("player", ui(TeamsMessages.INVITE_FORM_PLAYER), initial, 16)),
            ui(TeamsMessages.INVITE_FORM_BUTTON),
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
        return tooltips(form, ui(TeamsMessages.INVITE_FORM_BUTTON_TOOLTIP, time("time", this.settings.get().inviteExpiry())));
    }

    /** A copy of a dialog whose first buttons get these tooltips, in order (null keeps a button as it is). */
    static View tooltips(View view, Component... tooltips) {
        List<Button> buttons = new ArrayList<>(view.buttons());
        for (int i = 0; i < tooltips.length && i < buttons.size(); i++) {
            if (tooltips[i] != null) {
                buttons.set(i, buttons.get(i).tooltip(tooltips[i]));
            }
        }
        return new View(view.kind(), view.title(), view.body(), view.inputs(), buttons, view.exit(), view.columns(), view.escapable());
    }

    // ------------------------------------------------------------------ confirmations

    /** "Make Alex the owner?" from {@code /team transfer}: Cancel returns to the main dialog. */
    View confirmTransfer(Player player, UUID target) {
        return confirmTransfer(player, target, s -> showMain(s.player(), null));
    }

    private View confirmTransfer(Player player, UUID target, Button.Handler no) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        String name = this.actions.name(target);
        return this.templates.confirm(ui(TeamsMessages.TRANSFER_TITLE),
            this.lang.lines(TeamsMessages.TRANSFER_BODY, Arg.text("name", name), Arg.text("team", team.name())),
            ui(TeamsMessages.TRANSFER_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.transfer(s.player(), target), name)),
            no);
    }

    View confirmDisband(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        MessageKey body = this.settings.get().createCost() > 0 ? TeamsMessages.DISBAND_BODY : TeamsMessages.DISBAND_BODY_FREE;
        return this.templates.confirm(ui(TeamsMessages.DISBAND_TITLE),
            this.lang.lines(body, Arg.text("team", team.name())),
            ui(TeamsMessages.DISBAND_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.disband(s.player()), null)),
            s -> showMain(s.player(), null));
    }

    View confirmLeave(Player player) {
        Team team = this.registry.of(player.getUniqueId()).orElse(null);
        if (team == null) {
            return noTeamAfter(player, TeamProblem.NOT_IN_TEAM);
        }
        return this.templates.confirm(ui(TeamsMessages.LEAVE_TITLE),
            this.lang.lines(TeamsMessages.LEAVE_BODY, Arg.text("team", team.name())),
            ui(TeamsMessages.LEAVE_BUTTON), this.lang.get(CoreMessages.UI_CANCEL),
            s -> showMain(s.player(), error(this.actions.leave(s.player()), null)),
            s -> showMain(s.player(), null));
    }

    // ------------------------------------------------------------------ info, list, leaderboard

    /**
     * Stats of a team: dialog lines (with icons, values coloured) for a player, plain chat lines for the console. The
     * home is shown only to its members and staff.
     */
    List<Component> infoLines(CommandSender viewer, Team team) {
        UUID viewerId = viewer instanceof Player player ? player.getUniqueId() : null;
        boolean dialog = viewerId != null;
        long kills = TeamTop.total(team, member -> this.stats.get(member, StatsRecorder.Stat.KILLS));
        long deaths = TeamTop.total(team, member -> this.stats.get(member, StatsRecorder.Stat.DEATHS));
        long money = TeamTop.total(team, member -> this.services.ledger().balance(member, Currency.MONEY));
        Duration age = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - team.created()));
        int online = this.presence.online(team, viewerId);
        List<Component> lines = new ArrayList<>(dialog
            ? this.lang.lines(TeamsMessages.INFO_BODY, Arg.text("owner", this.actions.name(team.owner())), count("members", team.size()),
                this.feedback.limitText("limit", team), count("online", online), count("kills", kills), count("deaths", deaths),
                Arg.money("money", money), time("age", age))
            : this.lang.lines(TeamsMessages.INFO_TEXT, Arg.text("owner", this.actions.name(team.owner())),
                Arg.number("members", team.size()), this.feedback.limit("limit", team), Arg.number("online", online),
                Arg.number("kills", kills), Arg.number("deaths", deaths), Arg.money("money", money), Arg.time("age", age)));
        int killsRank = this.top.rank(TeamTop.Board.KILLS, team.id());
        int moneyRank = this.top.rank(TeamTop.Board.MONEY, team.id());
        lines.add(ui(TeamsMessages.INFO_PLACES,
            Arg.text("kills", killsRank > 0 ? Lang.number(killsRank) : "-"),
            Arg.text("money", moneyRank > 0 ? Lang.number(moneyRank) : "-")));
        lines.add(ui(TeamsMessages.INFO_FRIENDLY_FIRE, Arg.component("value", this.templates.state(team.friendlyFire()))));
        boolean insider = viewer.hasPermission(ADMIN_PERMISSION) || viewerId != null && team.isMember(viewerId);
        if (insider) {
            lines.add(dialog ? homeLine(team) : homeText(team));
        }
        return lines;
    }

    private Component homeLine(Team team) {
        TeamHome home = team.home();
        if (home == null) {
            return ui(TeamsMessages.INFO_NO_HOME);
        }
        return ui(TeamsMessages.INFO_HOME, count("x", home.blockX()), count("y", home.blockY()), count("z", home.blockZ()),
            Arg.text("world", WorldNames.of(this.lang, home.world())));
    }

    private Component homeText(Team team) {
        TeamHome home = team.home();
        if (home == null) {
            return ui(TeamsMessages.INFO_NO_HOME_TEXT);
        }
        return ui(TeamsMessages.INFO_HOME_TEXT, Arg.number("x", home.blockX()), Arg.number("y", home.blockY()),
            Arg.number("z", home.blockZ()), Arg.text("world", WorldNames.of(this.lang, home.world())));
    }

    /** Shows a team's stats, with a Members button that lists them. Call on the viewer's thread. */
    void showInfo(Player viewer, Team team, Button.Handler back) {
        show(viewer, infoView(viewer, team, back));
    }

    private View infoView(Player viewer, Team team, Button.Handler back) {
        long teamId = team.id();
        Button.Handler again = s -> this.registry.get(teamId).ifPresentOrElse(fresh -> showInfo(s.player(), fresh, back),
            () -> showMain(s.player(), error(TeamProblem.TEAM_GONE)));
        Button members = this.templates.choiceButton(ui(TeamsMessages.INFO_MEMBERS), Component.text(Lang.number(team.size())),
            ui(TeamsMessages.INFO_MEMBERS_TOOLTIP), s -> this.registry.get(teamId).ifPresentOrElse(
                fresh -> showMembers(s.player(), fresh, again), () -> showMain(s.player(), error(TeamProblem.TEAM_GONE))));
        return this.templates.column(ui(TeamsMessages.MENU_TEAM_TITLE, Arg.text("name", team.name())), infoLines(viewer, team),
            List.of(members), back);
    }

    /** Teams by size, then name. */
    List<Team> sortedTeams() {
        List<Team> teams = this.registry.all();
        teams.sort(Comparator.comparingInt(Team::size).reversed().thenComparing(Team::name, String.CASE_INSENSITIVE_ORDER));
        return teams;
    }

    /** Every team (the biggest {@code list-size}), biggest first, one button each that opens its stats. */
    View listView(Player player, Button.Handler back) {
        List<Team> teams = sortedTeams();
        int cap = this.settings.get().listSize();
        List<Team> shown = teams.subList(0, Math.min(cap, teams.size()));
        List<Component> lines = new ArrayList<>();
        if (teams.isEmpty()) {
            lines.add(ui(TeamsMessages.LIST_EMPTY));
        } else if (teams.size() > shown.size()) {
            lines.add(ui(TeamsMessages.LIST_CAPPED, count("count", shown.size())));
        }
        List<Button> buttons = new ArrayList<>(shown.size());
        Button.Handler self = s -> s.show(listView(s.player(), back));
        for (Team team : shown) {
            long id = team.id();
            Component label = team.size() == 1 ? ui(TeamsMessages.LIST_BUTTON_ONE, Arg.text("name", team.name()))
                : ui(TeamsMessages.LIST_BUTTON, Arg.text("name", team.name()), count("members", team.size()));
            buttons.add(Button.of(label,
                ui(TeamsMessages.LIST_BUTTON_TOOLTIP, count("online", this.presence.online(team, player.getUniqueId()))),
                s -> this.registry.get(id).ifPresentOrElse(fresh -> showInfo(s.player(), fresh, self),
                    () -> s.show(listView(s.player(), back)))));
        }
        return this.templates.column(ui(TeamsMessages.LIST_TITLE), lines, buttons, back);
    }

    View topView(Player player, TeamTop.Board board, Button.Handler back) {
        List<Component> lines = topLines(board);
        Team mine = this.registry.of(player.getUniqueId()).orElse(null);
        if (mine != null) {
            this.top.entry(board, mine.id()).ifPresent(entry -> lines.add(board == TeamTop.Board.KILLS
                ? ui(TeamsMessages.TOP_KILLS_YOU, count("rank", entry.rank()), count("value", entry.value()))
                : ui(TeamsMessages.TOP_MONEY_YOU, count("rank", entry.rank()), Arg.money("value", entry.value()))));
        }
        TeamTop.Board other = board == TeamTop.Board.KILLS ? TeamTop.Board.MONEY : TeamTop.Board.KILLS;
        Button rankedBy = this.templates.choiceButton(ui(TeamsMessages.TOP_RANKED_BY),
            ui(board == TeamTop.Board.KILLS ? TeamsMessages.TOP_BY_KILLS : TeamsMessages.TOP_BY_MONEY),
            ui(TeamsMessages.TOP_RANKED_BY_TOOLTIP), s -> s.show(topView(s.player(), other, back)));
        return this.templates.column(ui(board == TeamTop.Board.KILLS ? TeamsMessages.TOP_KILLS_TITLE : TeamsMessages.TOP_MONEY_TITLE),
            lines, List.of(rankedBy), back);
    }

    /** The board as lines (shared by the dialog and the console). */
    List<Component> topLines(TeamTop.Board board) {
        List<Component> lines = new ArrayList<>();
        List<TeamTop.Entry> entries = this.top.top(board);
        if (entries.isEmpty()) {
            lines.add(ui(TeamsMessages.TOP_EMPTY));
        }
        for (TeamTop.Entry entry : entries) {
            lines.add(board == TeamTop.Board.KILLS
                ? ui(TeamsMessages.TOP_KILLS_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                    count("value", entry.value()))
                : ui(TeamsMessages.TOP_MONEY_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                    Arg.money("value", entry.value())));
        }
        return lines;
    }
}
