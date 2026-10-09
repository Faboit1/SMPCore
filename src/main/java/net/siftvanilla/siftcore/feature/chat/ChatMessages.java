package net.siftvanilla.siftcore.feature.chat;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the chat feature ({@code lang/chat.yml}). */
public final class ChatMessages {

    public static final MessageKey FORMAT = MessageKey.ui("chat.format", "rank", "name", "message");
    public static final MessageKey FORMAT_UNRANKED = MessageKey.ui("chat.format-unranked", "name", "message");
    public static final MessageKey TAGGED_NAME = MessageKey.ui("chat.tagged-name", "tag", "name");

    public static final MessageKey CARD_NAME = MessageKey.ui("chat.card.name", "name");
    public static final MessageKey CARD_NAME_STYLED = MessageKey.ui("chat.card.nickname", "name");
    public static final MessageKey CARD_REAL_NAME = MessageKey.ui("chat.card.real-name", "name");
    public static final MessageKey CARD_RANK = MessageKey.ui("chat.card.rank", "rank");
    public static final MessageKey CARD_TEAM = MessageKey.ui("chat.card.team", "team");
    public static final MessageKey CARD_NO_TEAM = MessageKey.ui("chat.card.no-team");
    public static final MessageKey CARD_BALANCE = MessageKey.ui("chat.card.balance", "balance");
    public static final MessageKey CARD_KILLS = MessageKey.ui("chat.card.kills", "kills");
    public static final MessageKey CARD_PLAYTIME = MessageKey.ui("chat.card.playtime", "playtime");
    public static final MessageKey CARD_CLICK = MessageKey.ui("chat.card.click", "name");

    public static final MessageKey ITEM = MessageKey.ui("chat.item.single", "item");
    public static final MessageKey ITEM_STACK = MessageKey.ui("chat.item.stack", "item", "amount");

    public static final MessageKey TOO_LONG = MessageKey.error("chat.blocked.too-long", "max");
    public static final MessageKey TOO_FAST = MessageKey.error("chat.blocked.too-fast", "time");
    public static final MessageKey RATE_LIMITED = MessageKey.error("chat.blocked.rate", "time");
    public static final MessageKey REPEATED = MessageKey.error("chat.blocked.repeat");
    public static final MessageKey CAPS = MessageKey.error("chat.blocked.caps");
    public static final MessageKey FILTERED = MessageKey.error("chat.blocked.filtered");
    public static final MessageKey LINK = MessageKey.error("chat.blocked.link");
    public static final MessageKey LOCKED = MessageKey.error("chat.blocked.locked");
    public static final MessageKey SLOW = MessageKey.error("chat.blocked.slow", "time");

    public static final MessageKey MENTIONED = MessageKey.info("chat.mention.notice", "name").withFeedback(Feedback.NOTIFY);
    public static final MessageKey MENTION_AFK = MessageKey.info("chat.mention.afk", "name");

    public static final MessageKey PM_TO = MessageKey.chat("chat.private.to", "name", "message");
    public static final MessageKey PM_FROM = MessageKey.notify("chat.private.from", "name", "message");
    public static final MessageKey PM_SPY = MessageKey.chat("chat.private.spy", "from", "to", "message");
    public static final MessageKey PM_NAME_HOVER = MessageKey.ui("chat.private.name-hover", "name");
    public static final MessageKey PM_CONSOLE = MessageKey.ui("chat.private.console");
    public static final MessageKey PM_EMPTY = MessageKey.error("chat.private.empty");
    public static final MessageKey PM_NO_REPLY = MessageKey.error("chat.private.no-reply");
    public static final MessageKey PM_SELF = MessageKey.error("chat.private.self");
    public static final MessageKey PM_BLOCKED = MessageKey.error("chat.private.blocked", "name");
    public static final MessageKey PM_IGNORING = MessageKey.error("chat.private.ignoring", "name");
    public static final MessageKey PM_DISABLED = MessageKey.error("chat.private.disabled", "name");
    public static final MessageKey PM_AFK = MessageKey.info("chat.private.afk", "name");
    public static final MessageKey PM_MUTED = MessageKey.error("chat.private.muted", "time", "reason");
    public static final MessageKey PM_MUTED_PERMANENT = MessageKey.error("chat.private.muted-permanent", "reason");
    public static final MessageKey PM_NO_REASON = MessageKey.ui("chat.private.no-reason");
    public static final MessageKey PM_TOGGLED_ON = MessageKey.success("chat.private.toggled-on");
    public static final MessageKey PM_TOGGLED_OFF = MessageKey.success("chat.private.toggled-off");

    public static final MessageKey SPY_ON = MessageKey.success("chat.spy.on");
    public static final MessageKey SPY_OFF = MessageKey.success("chat.spy.off");

    public static final MessageKey IGNORE_ADDED = MessageKey.success("chat.ignore.added", "name");
    public static final MessageKey IGNORE_REMOVED = MessageKey.success("chat.ignore.removed", "name");
    public static final MessageKey IGNORE_STAFF = MessageKey.error("chat.ignore.staff", "name");
    public static final MessageKey IGNORE_FULL = MessageKey.error("chat.ignore.full", "max");
    public static final MessageKey IGNORE_NOT_IGNORED = MessageKey.error("chat.ignore.not-ignored", "name");
    public static final MessageKey IGNORE_LIST_TITLE = MessageKey.ui("chat.ignore.list.title");
    public static final MessageKey IGNORE_LIST_EMPTY = MessageKey.ui("chat.ignore.list.empty");
    public static final MessageKey IGNORE_LIST_ONE = MessageKey.ui("chat.ignore.list.one");
    public static final MessageKey IGNORE_LIST_MANY = MessageKey.ui("chat.ignore.list.many", "count");
    public static final MessageKey IGNORE_LIST_PAGE = MessageKey.ui("chat.ignore.list.page", "page", "pages");
    public static final MessageKey IGNORE_LIST_ADD = MessageKey.ui("chat.ignore.list.add");
    public static final MessageKey IGNORE_LIST_NEXT = MessageKey.ui("chat.ignore.list.next");
    public static final MessageKey IGNORE_LIST_PREVIOUS = MessageKey.ui("chat.ignore.list.previous");
    public static final MessageKey IGNORE_FORM_TITLE = MessageKey.ui("chat.ignore.form.title");
    public static final MessageKey IGNORE_FORM_BODY = MessageKey.ui("chat.ignore.form.body");
    public static final MessageKey IGNORE_FORM_NAME = MessageKey.ui("chat.ignore.form.name");
    public static final MessageKey IGNORE_FORM_SUBMIT = MessageKey.ui("chat.ignore.form.submit");
    public static final MessageKey IGNORE_CONFIRM_TITLE = MessageKey.ui("chat.ignore.confirm.title");
    public static final MessageKey IGNORE_CONFIRM_BODY = MessageKey.ui("chat.ignore.confirm.body", "name");
    public static final MessageKey IGNORE_CONFIRM_BUTTON = MessageKey.ui("chat.ignore.confirm.button");

    public static final MessageKey ADMIN_LOCKED = MessageKey.notify("chat.admin.locked");
    public static final MessageKey ADMIN_UNLOCKED = MessageKey.notify("chat.admin.unlocked");
    public static final MessageKey ADMIN_ALREADY_LOCKED = MessageKey.chat("chat.admin.already-locked");
    public static final MessageKey ADMIN_ALREADY_UNLOCKED = MessageKey.chat("chat.admin.already-unlocked");
    public static final MessageKey ADMIN_SLOW_ON = MessageKey.notify("chat.admin.slow-on", "time");
    public static final MessageKey ADMIN_SLOW_OFF = MessageKey.notify("chat.admin.slow-off");
    public static final MessageKey ADMIN_SLOW_INVALID = MessageKey.chat("chat.admin.slow-invalid", "input");
    public static final MessageKey ADMIN_SLOW_TOO_LONG = MessageKey.chat("chat.admin.slow-too-long", "max");
    public static final MessageKey ADMIN_STATUS = MessageKey.chat("chat.admin.status", "lock", "slow", "words", "links", "ignores", "rate", "window", "cooldown");
    public static final MessageKey ADMIN_STATUS_LINKS_BLOCK = MessageKey.ui("chat.admin.status-links-block");
    public static final MessageKey ADMIN_STATUS_LINKS_REPLACE = MessageKey.ui("chat.admin.status-links-replace");
    public static final MessageKey ADMIN_STATUS_LINKS_OFF = MessageKey.ui("chat.admin.status-links-off");
    public static final MessageKey ADMIN_STATUS_LOCKED = MessageKey.ui("chat.admin.status-locked");
    public static final MessageKey ADMIN_STATUS_OPEN = MessageKey.ui("chat.admin.status-open");
    public static final MessageKey ADMIN_STATUS_SLOW_OFF = MessageKey.ui("chat.admin.status-slow-off");
    public static final MessageKey ADMIN_TEST_CLEAN = MessageKey.chat("chat.admin.test-clean");
    public static final MessageKey ADMIN_TEST_REPLACED = MessageKey.chat("chat.admin.test-replaced", "text", "words");
    public static final MessageKey ADMIN_TEST_BLOCKED = MessageKey.chat("chat.admin.test-blocked", "words");
    public static final MessageKey ADMIN_TEST_CAPS_LOWERED = MessageKey.chat("chat.admin.test-caps-lowered");
    public static final MessageKey ADMIN_TEST_CAPS_BLOCKED = MessageKey.chat("chat.admin.test-caps-blocked");
    public static final MessageKey ADMIN_TEST_TOO_LONG = MessageKey.chat("chat.admin.test-too-long", "max");
    public static final MessageKey ADMIN_TEST_LINK_BLOCKED = MessageKey.chat("chat.admin.test-link-blocked", "links");
    public static final MessageKey ADMIN_TEST_LINK_REPLACED = MessageKey.chat("chat.admin.test-link-replaced", "text", "links");
    public static final MessageKey ADMIN_IGNORES_HEADER = MessageKey.chat("chat.admin.ignores-header", "name", "count");
    public static final MessageKey ADMIN_IGNORES_LINE = MessageKey.chat("chat.admin.ignores-line", "name");
    public static final MessageKey ADMIN_IGNORES_NONE = MessageKey.chat("chat.admin.ignores-none", "name");

    public static final MessageKey SETTING_MENTIONS = MessageKey.ui("chat.settings.mentions");
    public static final MessageKey SETTING_MENTIONS_DESCRIPTION = MessageKey.ui("chat.settings.mentions-description");
    public static final MessageKey SETTING_PRIVATE = MessageKey.ui("chat.settings.private-messages");
    public static final MessageKey SETTING_PRIVATE_DESCRIPTION = MessageKey.ui("chat.settings.private-messages-description");
    public static final MessageKey SETTING_SPY = MessageKey.ui("chat.settings.social-spy");
    public static final MessageKey SETTING_SPY_DESCRIPTION = MessageKey.ui("chat.settings.social-spy-description");

    private ChatMessages() {
    }
}
