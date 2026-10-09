package net.siftvanilla.siftcore.feature.teams;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the teams feature ({@code lang/teams.yml}). */
public final class TeamsMessages {

    // ------------------------------------------------------------------ refusals (action bar)

    public static final MessageKey NOT_IN_TEAM = MessageKey.error("teams.error.not-in-team");
    public static final MessageKey ALREADY_IN_TEAM = MessageKey.error("teams.error.already-in-team");
    public static final MessageKey OWNER_ONLY = MessageKey.error("teams.error.owner-only");
    public static final MessageKey ADMINS_ONLY = MessageKey.error("teams.error.admins-only");
    public static final MessageKey TARGET_NOT_MEMBER = MessageKey.error("teams.error.target-not-member", "name");
    public static final MessageKey TARGET_IN_TEAM = MessageKey.error("teams.error.target-in-team", "name");
    public static final MessageKey TARGET_ALREADY_MEMBER = MessageKey.error("teams.error.target-already-member", "name");
    public static final MessageKey RANK_TOO_HIGH = MessageKey.error("teams.error.rank-too-high", "name");
    public static final MessageKey ALREADY_ADMIN = MessageKey.error("teams.error.already-admin", "name");
    public static final MessageKey NOT_ADMIN = MessageKey.error("teams.error.not-admin", "name");
    public static final MessageKey OWNER_CANT_LEAVE = MessageKey.error("teams.error.owner-cant-leave");
    public static final MessageKey TEAM_FULL = MessageKey.error("teams.error.team-full", "members", "limit");
    public static final MessageKey OTHER_TEAM_FULL = MessageKey.error("teams.error.other-team-full", "team");
    public static final MessageKey TEAM_GONE = MessageKey.error("teams.error.team-gone");
    public static final MessageKey TEAM_NOT_FOUND = MessageKey.error("teams.error.team-not-found", "name");
    public static final MessageKey NO_INVITE = MessageKey.error("teams.error.no-invite", "team");
    public static final MessageKey INVITE_EXPIRED = MessageKey.error("teams.error.invite-expired");
    public static final MessageKey ALREADY_INVITED = MessageKey.error("teams.error.already-invited", "name");
    /** The target doesn't take invites from the inviter: their {@code team-invites} setting, or they ignore them. */
    public static final MessageKey INVITES_CLOSED = MessageKey.error("teams.error.invites-closed", "name");
    public static final MessageKey TOO_MANY_INVITES = MessageKey.error("teams.error.too-many-invites");
    public static final MessageKey NO_HOME = MessageKey.error("teams.error.no-home");
    public static final MessageKey HOME_WORLD_MISSING = MessageKey.error("teams.error.home-world-missing");
    public static final MessageKey HOME_WORLD_DISABLED = MessageKey.error("teams.error.home-world-disabled");
    public static final MessageKey HOME_IN_SPAWN = MessageKey.error("teams.error.home-in-spawn");
    public static final MessageKey HOME_AT_SPAWN = MessageKey.error("teams.error.home-at-spawn");
    public static final MessageKey NAME_LENGTH = MessageKey.error("teams.error.name-length", "min", "max");
    public static final MessageKey NAME_CHARACTERS = MessageKey.error("teams.error.name-characters");
    public static final MessageKey NAME_BLOCKED = MessageKey.error("teams.error.name-blocked");
    public static final MessageKey NAME_TAKEN = MessageKey.error("teams.error.name-taken", "name");
    public static final MessageKey NAME_UNCHANGED = MessageKey.error("teams.error.name-unchanged");
    public static final MessageKey COST_CHANGED = MessageKey.error("teams.error.cost-changed");
    public static final MessageKey CANCELLED = MessageKey.error("teams.error.cancelled");
    public static final MessageKey CHAT_NO_TEAM = MessageKey.error("teams.error.chat-no-team");
    public static final MessageKey CHAT_MUTED = MessageKey.error("teams.error.chat-muted", "time");
    public static final MessageKey CHAT_MUTED_PERMANENT = MessageKey.error("teams.error.chat-muted-permanent");

    // ------------------------------------------------------------------ results for the actor

    public static final MessageKey CREATED = MessageKey.chat("teams.created", "name", "cost");
    public static final MessageKey CREATED_FREE = MessageKey.chat("teams.created-free", "name");
    public static final MessageKey JOINED = MessageKey.chat("teams.joined", "team");
    public static final MessageKey LEFT = MessageKey.chat("teams.left", "team");
    public static final MessageKey KICKED = MessageKey.notify("teams.kicked", "team");
    public static final MessageKey INVITE_SENT = MessageKey.success("teams.invite.sent", "name", "time");
    public static final MessageKey INVITE_RECEIVED = MessageKey.notify("teams.invite.received", "inviter", "team");
    public static final MessageKey INVITE_HOVER = MessageKey.ui("teams.invite.hover");
    public static final MessageKey INVITE_DECLINED = MessageKey.info("teams.invite.declined", "team");
    public static final MessageKey INVITE_DECLINED_INVITER = MessageKey.info("teams.invite.declined-inviter", "name");
    public static final MessageKey FRIENDLY_FIRE_ALREADY_ON = MessageKey.info("teams.friendly-fire.already-on");
    public static final MessageKey FRIENDLY_FIRE_ALREADY_OFF = MessageKey.info("teams.friendly-fire.already-off");
    public static final MessageKey FRIENDLY_FIRE_BLOCKED = MessageKey.info("teams.friendly-fire.blocked");
    public static final MessageKey CHAT_ON = MessageKey.info("teams.chat.on");
    public static final MessageKey CHAT_OFF = MessageKey.info("teams.chat.off");
    public static final MessageKey CHAT_FORMAT = MessageKey.ui("teams.chat.format", "name", "message");
    public static final MessageKey CHAT_SPY_FORMAT = MessageKey.ui("teams.chat.spy-format", "team", "name", "message");
    public static final MessageKey SPY_ON = MessageKey.info("teams.spy.on");
    public static final MessageKey SPY_OFF = MessageKey.info("teams.spy.off");
    /** Team chat mode came back on at login ({@code team-chat-sticky}); a chat line, so it is noticed. */
    public static final MessageKey CHAT_RESTORED = MessageKey.chat("teams.chat.restored");
    /** A teammate came online / went offline ({@code team-member-alerts}). */
    public static final MessageKey MEMBER_ONLINE = MessageKey.chat("teams.member.online", "name");
    public static final MessageKey MEMBER_OFFLINE = MessageKey.chat("teams.member.offline", "name");

    // ------------------------------------------------------------------ messages to the whole team (chat)

    public static final MessageKey TEAM_JOINED = MessageKey.chat("teams.broadcast.joined", "name");
    public static final MessageKey TEAM_LEFT = MessageKey.chat("teams.broadcast.left", "name");
    public static final MessageKey TEAM_KICKED = MessageKey.chat("teams.broadcast.kicked", "name", "actor");
    public static final MessageKey TEAM_KICKED_STAFF = MessageKey.chat("teams.broadcast.kicked-staff", "name");
    public static final MessageKey TEAM_PROMOTED = MessageKey.chat("teams.broadcast.promoted", "name", "actor");
    public static final MessageKey TEAM_DEMOTED = MessageKey.chat("teams.broadcast.demoted", "name", "actor");
    public static final MessageKey TEAM_TRANSFERRED = MessageKey.chat("teams.broadcast.transferred", "name", "actor");
    public static final MessageKey TEAM_TRANSFERRED_STAFF = MessageKey.chat("teams.broadcast.transferred-staff", "name");
    public static final MessageKey TEAM_DISBANDED = MessageKey.notify("teams.broadcast.disbanded", "team", "actor");
    public static final MessageKey TEAM_DISBANDED_STAFF = MessageKey.notify("teams.broadcast.disbanded-staff", "team");
    public static final MessageKey TEAM_HOME_SET = MessageKey.chat("teams.broadcast.home-set", "actor");
    public static final MessageKey TEAM_HOME_REMOVED = MessageKey.chat("teams.broadcast.home-removed");
    public static final MessageKey TEAM_FRIENDLY_FIRE_ON = MessageKey.chat("teams.broadcast.friendly-fire-on", "actor");
    public static final MessageKey TEAM_FRIENDLY_FIRE_OFF = MessageKey.chat("teams.broadcast.friendly-fire-off", "actor");
    public static final MessageKey TEAM_RENAMED = MessageKey.chat("teams.broadcast.renamed", "name");

    // ------------------------------------------------------------------ the /team dialogs

    public static final MessageKey MENU_TITLE = MessageKey.ui("teams.menu.title");
    public static final MessageKey MENU_TEAM_TITLE = MessageKey.ui("teams.menu.team-title", "name");
    public static final MessageKey MENU_NONE = MessageKey.ui("teams.menu.none");
    public static final MessageKey MENU_SUMMARY = MessageKey.ui("teams.menu.summary", "owner", "members", "limit", "online");
    public static final MessageKey ROLE_OWNER = MessageKey.ui("teams.roles.owner");
    public static final MessageKey ROLE_ADMIN = MessageKey.ui("teams.roles.admin");
    public static final MessageKey ROLE_MEMBER = MessageKey.ui("teams.roles.member");
    public static final MessageKey UNLIMITED = MessageKey.ui("teams.unlimited");

    public static final MessageKey BUTTON_CREATE = MessageKey.ui("teams.button.create");
    public static final MessageKey BUTTON_CREATE_TOOLTIP = MessageKey.ui("teams.button.create-tooltip");
    /** The cost line of the Start a team tooltip, only while starting a team costs money. */
    public static final MessageKey BUTTON_CREATE_COST_TOOLTIP = MessageKey.ui("teams.button.create-cost-tooltip", "cost");
    public static final MessageKey BUTTON_ANSWER_INVITE = MessageKey.ui("teams.button.answer-invite", "team");
    public static final MessageKey BUTTON_ANSWER_INVITE_TOOLTIP = MessageKey.ui("teams.button.answer-invite-tooltip", "inviter", "time");
    public static final MessageKey BUTTON_HOME = MessageKey.ui("teams.button.home");
    public static final MessageKey BUTTON_HOME_TOOLTIP = MessageKey.ui("teams.button.home-tooltip", "x", "y", "z", "world", "time");
    public static final MessageKey BUTTON_CHAT = MessageKey.ui("teams.button.chat");
    public static final MessageKey BUTTON_CHAT_TOOLTIP = MessageKey.ui("teams.button.chat-tooltip");
    public static final MessageKey BUTTON_FRIENDLY_FIRE = MessageKey.ui("teams.button.friendly-fire");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_TOOLTIP = MessageKey.ui("teams.button.friendly-fire-tooltip");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_LOCKED = MessageKey.ui("teams.button.friendly-fire-locked", "value");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_LOCKED_TOOLTIP = MessageKey.ui("teams.button.friendly-fire-locked-tooltip");
    public static final MessageKey BUTTON_MEMBERS = MessageKey.ui("teams.button.members");
    public static final MessageKey BUTTON_MEMBERS_TOOLTIP = MessageKey.ui("teams.button.members-tooltip");
    public static final MessageKey BUTTON_MEMBERS_MANAGE_TOOLTIP = MessageKey.ui("teams.button.members-manage-tooltip");
    public static final MessageKey BUTTON_INVITE = MessageKey.ui("teams.button.invite");
    public static final MessageKey BUTTON_INVITE_TOOLTIP = MessageKey.ui("teams.button.invite-tooltip", "time");
    public static final MessageKey BUTTON_SET_HOME = MessageKey.ui("teams.button.set-home");
    public static final MessageKey BUTTON_SET_HOME_TOOLTIP = MessageKey.ui("teams.button.set-home-tooltip");
    public static final MessageKey BUTTON_STATS = MessageKey.ui("teams.button.stats");
    public static final MessageKey BUTTON_STATS_TOOLTIP = MessageKey.ui("teams.button.stats-tooltip");
    public static final MessageKey BUTTON_TOP = MessageKey.ui("teams.button.top");
    public static final MessageKey BUTTON_TOP_TOOLTIP = MessageKey.ui("teams.button.top-tooltip");
    public static final MessageKey BUTTON_LIST = MessageKey.ui("teams.button.list");
    public static final MessageKey BUTTON_LIST_TOOLTIP = MessageKey.ui("teams.button.list-tooltip");
    public static final MessageKey BUTTON_SETTINGS = MessageKey.ui("teams.button.settings");
    public static final MessageKey BUTTON_SETTINGS_TOOLTIP = MessageKey.ui("teams.button.settings-tooltip");
    public static final MessageKey BUTTON_DISBAND = MessageKey.ui("teams.button.disband");
    public static final MessageKey BUTTON_DISBAND_TOOLTIP = MessageKey.ui("teams.button.disband-tooltip");
    public static final MessageKey BUTTON_LEAVE = MessageKey.ui("teams.button.leave");
    public static final MessageKey BUTTON_LEAVE_TOOLTIP = MessageKey.ui("teams.button.leave-tooltip");

    public static final MessageKey MEMBERS_TITLE = MessageKey.ui("teams.members.title", "team");
    public static final MessageKey MEMBERS_ONLINE = MessageKey.ui("teams.members.online", "name", "role");
    public static final MessageKey MEMBERS_OFFLINE = MessageKey.ui("teams.members.offline", "name", "role", "time");
    /** An offline member whose last-seen time the viewer may not see (seen-privacy). */
    public static final MessageKey MEMBERS_OFFLINE_HIDDEN = MessageKey.ui("teams.members.offline-hidden", "name", "role");
    public static final MessageKey MEMBERS_TOOLTIP = MessageKey.ui("teams.members.tooltip", "time");
    public static final MessageKey MEMBERS_TOOLTIP_MANAGE = MessageKey.ui("teams.members.tooltip-manage");
    public static final MessageKey MEMBERS_TOOLTIP_YOU = MessageKey.ui("teams.members.tooltip-you");

    public static final MessageKey MEMBER_ROLE = MessageKey.ui("teams.member-view.role", "role");
    public static final MessageKey MEMBER_VIEW_ONLINE = MessageKey.ui("teams.member-view.online");
    public static final MessageKey MEMBER_SEEN = MessageKey.ui("teams.member-view.seen", "time");
    public static final MessageKey MEMBER_VIEW_OFFLINE = MessageKey.ui("teams.member-view.offline");
    public static final MessageKey MEMBER_JOINED = MessageKey.ui("teams.member-view.joined", "time");
    public static final MessageKey MEMBER_PROMOTE = MessageKey.ui("teams.member-view.promote");
    public static final MessageKey MEMBER_PROMOTE_TOOLTIP = MessageKey.ui("teams.member-view.promote-tooltip");
    public static final MessageKey MEMBER_DEMOTE = MessageKey.ui("teams.member-view.demote");
    public static final MessageKey MEMBER_DEMOTE_TOOLTIP = MessageKey.ui("teams.member-view.demote-tooltip");
    public static final MessageKey MEMBER_KICK = MessageKey.ui("teams.member-view.kick");
    public static final MessageKey MEMBER_KICK_TOOLTIP = MessageKey.ui("teams.member-view.kick-tooltip");
    public static final MessageKey MEMBER_TRANSFER = MessageKey.ui("teams.member-view.transfer");
    public static final MessageKey MEMBER_TRANSFER_TOOLTIP = MessageKey.ui("teams.member-view.transfer-tooltip");
    public static final MessageKey MEMBER_KICK_TITLE = MessageKey.ui("teams.member-view.kick-title");
    public static final MessageKey MEMBER_KICK_BODY = MessageKey.ui("teams.member-view.kick-body", "name", "team");
    public static final MessageKey MEMBER_KICK_BUTTON = MessageKey.ui("teams.member-view.kick-button");
    public static final MessageKey MEMBER_GONE = MessageKey.ui("teams.member-view.gone", "name");

    // ------------------------------------------------------------------ forms and confirmations

    public static final MessageKey CREATE_FORM_TITLE = MessageKey.ui("teams.create.form-title");
    public static final MessageKey CREATE_FORM_NAME = MessageKey.ui("teams.create.form-name");
    public static final MessageKey CREATE_FORM_BUTTON = MessageKey.ui("teams.create.form-button");
    public static final MessageKey CREATE_FORM_BUTTON_TOOLTIP = MessageKey.ui("teams.create.form-button-tooltip", "min", "max");
    /** The cost line of the Continue tooltip, only while starting a team costs money. */
    public static final MessageKey CREATE_FORM_COST_TOOLTIP = MessageKey.ui("teams.create.form-cost-tooltip", "cost");
    public static final MessageKey CREATE_CONFIRM_TITLE = MessageKey.ui("teams.create.confirm-title");
    public static final MessageKey CREATE_CONFIRM_BODY = MessageKey.ui("teams.create.confirm-body", "name", "cost");
    public static final MessageKey CREATE_CONFIRM_BUTTON = MessageKey.ui("teams.create.confirm-button");

    public static final MessageKey INVITE_FORM_TITLE = MessageKey.ui("teams.invite.form-title");
    public static final MessageKey INVITE_FORM_PLAYER = MessageKey.ui("teams.invite.form-player");
    public static final MessageKey INVITE_FORM_BUTTON = MessageKey.ui("teams.invite.form-button");
    public static final MessageKey INVITE_FORM_BUTTON_TOOLTIP = MessageKey.ui("teams.invite.form-button-tooltip", "time");
    public static final MessageKey INVITE_TITLE = MessageKey.ui("teams.invite.title");
    public static final MessageKey INVITE_BODY = MessageKey.ui("teams.invite.body", "inviter", "team", "members", "limit", "time");
    public static final MessageKey INVITE_JOIN = MessageKey.ui("teams.invite.join");
    public static final MessageKey INVITE_JOIN_TOOLTIP = MessageKey.ui("teams.invite.join-tooltip", "team");
    public static final MessageKey INVITE_DECLINE = MessageKey.ui("teams.invite.decline");
    public static final MessageKey INVITE_DECLINE_TOOLTIP = MessageKey.ui("teams.invite.decline-tooltip", "inviter");

    public static final MessageKey TRANSFER_TITLE = MessageKey.ui("teams.transfer.title");
    public static final MessageKey TRANSFER_BODY = MessageKey.ui("teams.transfer.body", "name", "team");
    public static final MessageKey TRANSFER_BUTTON = MessageKey.ui("teams.transfer.button");
    public static final MessageKey DISBAND_TITLE = MessageKey.ui("teams.disband.title");
    public static final MessageKey DISBAND_BODY = MessageKey.ui("teams.disband.body", "team");
    /** The disband question while starting a team is free (nothing to refund). */
    public static final MessageKey DISBAND_BODY_FREE = MessageKey.ui("teams.disband.body-free", "team");
    public static final MessageKey DISBAND_BUTTON = MessageKey.ui("teams.disband.button");
    public static final MessageKey LEAVE_TITLE = MessageKey.ui("teams.leave.title");
    public static final MessageKey LEAVE_BODY = MessageKey.ui("teams.leave.body", "team");
    public static final MessageKey LEAVE_BUTTON = MessageKey.ui("teams.leave.button");

    // ------------------------------------------------------------------ info, list, leaderboard

    public static final MessageKey INFO_BODY = MessageKey.ui("teams.info.body", "owner", "members", "limit", "online",
        "kills", "deaths", "money", "age");
    public static final MessageKey INFO_PLACES = MessageKey.ui("teams.info.places", "kills", "money");
    public static final MessageKey INFO_FRIENDLY_FIRE = MessageKey.ui("teams.info.friendly-fire", "value");
    public static final MessageKey INFO_HOME = MessageKey.ui("teams.info.home", "x", "y", "z", "world");
    public static final MessageKey INFO_NO_HOME = MessageKey.ui("teams.info.no-home");
    public static final MessageKey INFO_TEXT = MessageKey.chat("teams.info.text", "owner", "members", "limit", "online",
        "kills", "deaths", "money", "age");
    public static final MessageKey INFO_HOME_TEXT = MessageKey.chat("teams.info.home-text", "x", "y", "z", "world");
    public static final MessageKey INFO_NO_HOME_TEXT = MessageKey.chat("teams.info.no-home-text");
    public static final MessageKey INFO_HEADER = MessageKey.chat("teams.info.header", "name");
    public static final MessageKey INFO_MEMBER_NAMES = MessageKey.chat("teams.info.member-names", "names");
    public static final MessageKey INFO_MEMBERS = MessageKey.ui("teams.info.members");
    public static final MessageKey INFO_MEMBERS_TOOLTIP = MessageKey.ui("teams.info.members-tooltip");

    public static final MessageKey LIST_TITLE = MessageKey.ui("teams.list.title");
    /** The one line above a list that shows only the biggest teams ({@code list-size}). */
    public static final MessageKey LIST_CAPPED = MessageKey.ui("teams.list.capped", "count");
    public static final MessageKey LIST_HEADER = MessageKey.chat("teams.list.header", "page", "pages", "count");
    public static final MessageKey LIST_LINE = MessageKey.chat("teams.list.line", "name", "members", "online");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("teams.list.empty");
    public static final MessageKey LIST_BUTTON = MessageKey.ui("teams.list.button", "name", "members");
    public static final MessageKey LIST_BUTTON_ONE = MessageKey.ui("teams.list.button-one", "name");
    public static final MessageKey LIST_BUTTON_TOOLTIP = MessageKey.ui("teams.list.button-tooltip", "online");

    public static final MessageKey TOP_KILLS_TITLE = MessageKey.ui("teams.top.kills-title");
    public static final MessageKey TOP_MONEY_TITLE = MessageKey.ui("teams.top.money-title");
    public static final MessageKey TOP_KILLS_HEADER = MessageKey.chat("teams.top.kills-header");
    public static final MessageKey TOP_MONEY_HEADER = MessageKey.chat("teams.top.money-header");
    public static final MessageKey TOP_KILLS_LINE = MessageKey.chat("teams.top.kills-line", "rank", "name", "value");
    public static final MessageKey TOP_MONEY_LINE = MessageKey.chat("teams.top.money-line", "rank", "name", "value");
    public static final MessageKey TOP_KILLS_YOU = MessageKey.chat("teams.top.kills-you", "rank", "value");
    public static final MessageKey TOP_MONEY_YOU = MessageKey.chat("teams.top.money-you", "rank", "value");
    public static final MessageKey TOP_EMPTY = MessageKey.chat("teams.top.empty");
    public static final MessageKey TOP_RANKED_BY = MessageKey.ui("teams.top.ranked-by");
    public static final MessageKey TOP_RANKED_BY_TOOLTIP = MessageKey.ui("teams.top.ranked-by-tooltip");
    public static final MessageKey TOP_BY_KILLS = MessageKey.ui("teams.top.by-kills");
    public static final MessageKey TOP_BY_MONEY = MessageKey.ui("teams.top.by-money");

    public static final MessageKey HELP = MessageKey.chat("teams.help");
    public static final MessageKey HELP_ADMIN = MessageKey.chat("teams.help-admin");

    // ------------------------------------------------------------------ staff

    public static final MessageKey ADMIN_ADDED = MessageKey.chat("teams.admin.added", "name", "team");
    public static final MessageKey ADMIN_KICKED = MessageKey.chat("teams.admin.kicked", "name", "team");
    public static final MessageKey ADMIN_TRANSFERRED = MessageKey.chat("teams.admin.transferred", "name", "team");
    public static final MessageKey ADMIN_DISBANDED = MessageKey.chat("teams.admin.disbanded", "team");
    public static final MessageKey ADMIN_RENAMED = MessageKey.chat("teams.admin.renamed", "old", "name");
    public static final MessageKey ADMIN_HOME_REMOVED = MessageKey.chat("teams.admin.home-removed", "team");
    public static final MessageKey ADMIN_NOT_IN_TEAM = MessageKey.chat("teams.admin.not-in-team", "name");
    public static final MessageKey ADMIN_IS_OWNER = MessageKey.chat("teams.admin.is-owner", "name", "team");
    public static final MessageKey ADMIN_ALREADY_OWNER = MessageKey.chat("teams.admin.already-owner", "name");
    public static final MessageKey ADMIN_NOT_MEMBER = MessageKey.chat("teams.admin.not-member", "name", "team");

    // ------------------------------------------------------------------ hub and settings

    public static final MessageKey HUB_LABEL = MessageKey.ui("teams.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("teams.hub.description");
    public static final MessageKey SETTING_SPY = MessageKey.ui("teams.settings.spy");
    public static final MessageKey SETTING_SPY_DESCRIPTION = MessageKey.ui("teams.settings.spy-description");
    public static final MessageKey SETTING_NOTICES = MessageKey.ui("teams.settings.notices");
    public static final MessageKey SETTING_NOTICES_DESCRIPTION = MessageKey.ui("teams.settings.notices-description");
    public static final MessageKey SETTING_MEMBER_ALERTS = MessageKey.ui("teams.settings.member-alerts");
    public static final MessageKey SETTING_MEMBER_ALERTS_DESCRIPTION = MessageKey.ui("teams.settings.member-alerts-description");
    public static final MessageKey SETTING_MEMBER_ALERTS_BOTH = MessageKey.ui("teams.settings.member-alerts-joins-and-leaves");
    public static final MessageKey SETTING_MEMBER_ALERTS_JOINS = MessageKey.ui("teams.settings.member-alerts-joins");
    public static final MessageKey SETTING_INVITES = MessageKey.ui("teams.settings.invites");
    public static final MessageKey SETTING_INVITES_DESCRIPTION = MessageKey.ui("teams.settings.invites-description");
    public static final MessageKey SETTING_CHAT_STICKY = MessageKey.ui("teams.settings.chat-sticky");
    public static final MessageKey SETTING_CHAT_STICKY_DESCRIPTION = MessageKey.ui("teams.settings.chat-sticky-description");
    /** {@code /team spy} while the server fixed team chat spy for everyone. */
    public static final MessageKey SPY_LOCKED = MessageKey.error("teams.settings.spy-locked");
    /** {@code /team spy} while the server hides team chat spy. */
    public static final MessageKey SPY_UNAVAILABLE = MessageKey.error("teams.settings.spy-unavailable");

    private TeamsMessages() {
    }
}
