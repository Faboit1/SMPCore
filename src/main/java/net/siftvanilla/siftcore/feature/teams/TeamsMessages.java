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
    public static final MessageKey TOO_MANY_INVITES = MessageKey.error("teams.error.too-many-invites");
    public static final MessageKey NO_HOME = MessageKey.error("teams.error.no-home");
    public static final MessageKey HOME_WORLD_MISSING = MessageKey.error("teams.error.home-world-missing");
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

    // ------------------------------------------------------------------ main menu

    public static final MessageKey MENU_TITLE = MessageKey.ui("teams.menu.title");
    public static final MessageKey MENU_TEAM_TITLE = MessageKey.ui("teams.menu.team-title", "name");
    public static final MessageKey MENU_NONE = MessageKey.ui("teams.menu.none", "cost");
    public static final MessageKey MENU_NONE_FREE = MessageKey.ui("teams.menu.none-free");
    public static final MessageKey MENU_INVITES = MessageKey.ui("teams.menu.invites");
    public static final MessageKey MENU_INVITE_LINE = MessageKey.ui("teams.menu.invite-line", "team", "inviter", "time");
    public static final MessageKey MENU_SUMMARY = MessageKey.ui("teams.menu.summary", "owner", "members", "limit", "online");
    public static final MessageKey MENU_HOME = MessageKey.ui("teams.menu.home", "x", "y", "z", "world");
    public static final MessageKey MENU_NO_HOME = MessageKey.ui("teams.menu.no-home");
    public static final MessageKey MENU_FRIENDLY_FIRE_ON = MessageKey.ui("teams.menu.friendly-fire-on");
    public static final MessageKey MENU_FRIENDLY_FIRE_OFF = MessageKey.ui("teams.menu.friendly-fire-off");
    public static final MessageKey MENU_CHAT_ON = MessageKey.ui("teams.menu.chat-on");
    public static final MessageKey MENU_MEMBERS = MessageKey.ui("teams.menu.members");
    public static final MessageKey MENU_MEMBER_ONLINE = MessageKey.ui("teams.menu.member-online", "name", "role");
    public static final MessageKey MENU_MEMBER_OFFLINE = MessageKey.ui("teams.menu.member-offline", "name", "role", "time");
    public static final MessageKey ROLE_OWNER = MessageKey.ui("teams.roles.owner");
    public static final MessageKey ROLE_ADMIN = MessageKey.ui("teams.roles.admin");
    public static final MessageKey ROLE_MEMBER = MessageKey.ui("teams.roles.member");
    public static final MessageKey UNLIMITED = MessageKey.ui("teams.unlimited");

    public static final MessageKey BUTTON_CREATE = MessageKey.ui("teams.button.create");
    public static final MessageKey BUTTON_CREATE_TOOLTIP = MessageKey.ui("teams.button.create-tooltip", "cost");
    public static final MessageKey BUTTON_ANSWER_INVITE = MessageKey.ui("teams.button.answer-invite", "team");
    public static final MessageKey BUTTON_HOME = MessageKey.ui("teams.button.home");
    public static final MessageKey BUTTON_HOME_TOOLTIP = MessageKey.ui("teams.button.home-tooltip", "time");
    public static final MessageKey BUTTON_CHAT_ON = MessageKey.ui("teams.button.chat-on");
    public static final MessageKey BUTTON_CHAT_OFF = MessageKey.ui("teams.button.chat-off");
    public static final MessageKey BUTTON_CHAT_TOOLTIP = MessageKey.ui("teams.button.chat-tooltip");
    public static final MessageKey BUTTON_INVITE = MessageKey.ui("teams.button.invite");
    public static final MessageKey BUTTON_KICK = MessageKey.ui("teams.button.kick");
    public static final MessageKey BUTTON_PROMOTE = MessageKey.ui("teams.button.promote");
    public static final MessageKey BUTTON_DEMOTE = MessageKey.ui("teams.button.demote");
    public static final MessageKey BUTTON_SET_HOME = MessageKey.ui("teams.button.set-home");
    public static final MessageKey BUTTON_SET_HOME_TOOLTIP = MessageKey.ui("teams.button.set-home-tooltip");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_ON = MessageKey.ui("teams.button.friendly-fire-on");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_OFF = MessageKey.ui("teams.button.friendly-fire-off");
    public static final MessageKey BUTTON_FRIENDLY_FIRE_TOOLTIP = MessageKey.ui("teams.button.friendly-fire-tooltip");
    public static final MessageKey BUTTON_TRANSFER = MessageKey.ui("teams.button.transfer");
    public static final MessageKey BUTTON_TRANSFER_TOOLTIP = MessageKey.ui("teams.button.transfer-tooltip");
    public static final MessageKey BUTTON_DISBAND = MessageKey.ui("teams.button.disband");
    public static final MessageKey BUTTON_LEAVE = MessageKey.ui("teams.button.leave");
    public static final MessageKey BUTTON_STATS = MessageKey.ui("teams.button.stats");
    public static final MessageKey BUTTON_TOP = MessageKey.ui("teams.button.top");
    public static final MessageKey BUTTON_LIST = MessageKey.ui("teams.button.list");

    // ------------------------------------------------------------------ forms and confirmations

    public static final MessageKey CREATE_FORM_TITLE = MessageKey.ui("teams.create.form-title");
    public static final MessageKey CREATE_FORM_BODY = MessageKey.ui("teams.create.form-body", "min", "max", "cost");
    public static final MessageKey CREATE_FORM_BODY_FREE = MessageKey.ui("teams.create.form-body-free", "min", "max");
    public static final MessageKey CREATE_FORM_NAME = MessageKey.ui("teams.create.form-name");
    public static final MessageKey CREATE_FORM_BUTTON = MessageKey.ui("teams.create.form-button");
    public static final MessageKey CREATE_CONFIRM_TITLE = MessageKey.ui("teams.create.confirm-title");
    public static final MessageKey CREATE_CONFIRM_BODY = MessageKey.ui("teams.create.confirm-body", "name", "cost");
    public static final MessageKey CREATE_CONFIRM_BUTTON = MessageKey.ui("teams.create.confirm-button");

    public static final MessageKey INVITE_FORM_TITLE = MessageKey.ui("teams.invite.form-title");
    public static final MessageKey INVITE_FORM_BODY = MessageKey.ui("teams.invite.form-body", "time");
    public static final MessageKey INVITE_FORM_PLAYER = MessageKey.ui("teams.invite.form-player");
    public static final MessageKey INVITE_FORM_BUTTON = MessageKey.ui("teams.invite.form-button");
    public static final MessageKey INVITE_TITLE = MessageKey.ui("teams.invite.title");
    public static final MessageKey INVITE_BODY = MessageKey.ui("teams.invite.body", "inviter", "team", "members", "limit", "time");
    public static final MessageKey INVITE_JOIN = MessageKey.ui("teams.invite.join");
    public static final MessageKey INVITE_DECLINE = MessageKey.ui("teams.invite.decline");

    public static final MessageKey PICK_KICK_TITLE = MessageKey.ui("teams.pick.kick-title");
    public static final MessageKey PICK_PROMOTE_TITLE = MessageKey.ui("teams.pick.promote-title");
    public static final MessageKey PICK_DEMOTE_TITLE = MessageKey.ui("teams.pick.demote-title");
    public static final MessageKey PICK_TRANSFER_TITLE = MessageKey.ui("teams.pick.transfer-title");
    public static final MessageKey PICK_LABEL = MessageKey.ui("teams.pick.label");
    public static final MessageKey PICK_OPTION = MessageKey.ui("teams.pick.option", "name", "role");
    public static final MessageKey PICK_KICK_BUTTON = MessageKey.ui("teams.pick.kick-button");
    public static final MessageKey PICK_PROMOTE_BUTTON = MessageKey.ui("teams.pick.promote-button");
    public static final MessageKey PICK_DEMOTE_BUTTON = MessageKey.ui("teams.pick.demote-button");
    public static final MessageKey PICK_TRANSFER_BUTTON = MessageKey.ui("teams.pick.transfer-button");
    public static final MessageKey PICK_NOBODY = MessageKey.ui("teams.pick.nobody");

    public static final MessageKey TRANSFER_TITLE = MessageKey.ui("teams.transfer.title");
    public static final MessageKey TRANSFER_BODY = MessageKey.ui("teams.transfer.body", "name", "team");
    public static final MessageKey TRANSFER_BUTTON = MessageKey.ui("teams.transfer.button");
    public static final MessageKey DISBAND_TITLE = MessageKey.ui("teams.disband.title");
    public static final MessageKey DISBAND_BODY = MessageKey.ui("teams.disband.body", "team");
    public static final MessageKey DISBAND_BUTTON = MessageKey.ui("teams.disband.button");
    public static final MessageKey LEAVE_TITLE = MessageKey.ui("teams.leave.title");
    public static final MessageKey LEAVE_BODY = MessageKey.ui("teams.leave.body", "team");
    public static final MessageKey LEAVE_BUTTON = MessageKey.ui("teams.leave.button");

    // ------------------------------------------------------------------ info, list, leaderboard

    public static final MessageKey INFO_BODY = MessageKey.ui("teams.info.body", "owner", "members", "limit", "online",
        "kills", "deaths", "money", "age");
    public static final MessageKey INFO_PLACES = MessageKey.ui("teams.info.places", "kills", "money");
    public static final MessageKey INFO_TEXT = MessageKey.chat("teams.info.text", "owner", "members", "limit", "online",
        "kills", "deaths", "money", "age");
    public static final MessageKey INFO_HOME_TEXT = MessageKey.chat("teams.info.home-text", "x", "y", "z", "world");
    public static final MessageKey INFO_NO_HOME_TEXT = MessageKey.chat("teams.info.no-home-text");
    public static final MessageKey INFO_HEADER = MessageKey.chat("teams.info.header", "name");
    public static final MessageKey INFO_MEMBER_NAMES = MessageKey.chat("teams.info.member-names", "names");

    public static final MessageKey LIST_TITLE = MessageKey.ui("teams.list.title");
    public static final MessageKey LIST_PAGE = MessageKey.ui("teams.list.page", "page", "pages", "count");
    public static final MessageKey LIST_HEADER = MessageKey.chat("teams.list.header", "page", "pages", "count");
    public static final MessageKey LIST_LINE = MessageKey.chat("teams.list.line", "name", "members", "online");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("teams.list.empty");
    public static final MessageKey LIST_BUTTON_TOOLTIP = MessageKey.ui("teams.list.button-tooltip", "members", "online");
    public static final MessageKey PAGE_NEXT = MessageKey.ui("teams.page.next");
    public static final MessageKey PAGE_PREVIOUS = MessageKey.ui("teams.page.previous");

    public static final MessageKey TOP_KILLS_TITLE = MessageKey.ui("teams.top.kills-title");
    public static final MessageKey TOP_MONEY_TITLE = MessageKey.ui("teams.top.money-title");
    public static final MessageKey TOP_KILLS_HEADER = MessageKey.chat("teams.top.kills-header");
    public static final MessageKey TOP_MONEY_HEADER = MessageKey.chat("teams.top.money-header");
    public static final MessageKey TOP_KILLS_LINE = MessageKey.chat("teams.top.kills-line", "rank", "name", "value");
    public static final MessageKey TOP_MONEY_LINE = MessageKey.chat("teams.top.money-line", "rank", "name", "value");
    public static final MessageKey TOP_KILLS_YOU = MessageKey.chat("teams.top.kills-you", "rank", "value");
    public static final MessageKey TOP_MONEY_YOU = MessageKey.chat("teams.top.money-you", "rank", "value");
    public static final MessageKey TOP_EMPTY = MessageKey.chat("teams.top.empty");
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

    private TeamsMessages() {
    }
}
