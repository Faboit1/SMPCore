package net.siftvanilla.siftcore.feature.economy;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the economy feature ({@code lang/economy.yml}). */
public final class EconomyMessages {

    public static final MessageKey BALANCE_SELF = MessageKey.chat("economy.balance.self", "amount", "shards");
    public static final MessageKey BALANCE_OTHER = MessageKey.chat("economy.balance.other", "name", "amount", "shards");

    public static final MessageKey PAY_SENT = MessageKey.chat("economy.pay.sent", "name", "amount");
    public static final MessageKey PAY_RECEIVED = MessageKey.notify("economy.pay.received", "name", "amount");
    public static final MessageKey PAY_MINIMUM = MessageKey.error("economy.pay.minimum", "amount");
    public static final MessageKey PAY_LIMIT = MessageKey.error("economy.pay.limit", "left", "limit");
    public static final MessageKey PAY_OFFLINE = MessageKey.error("economy.pay.offline", "name");
    public static final MessageKey PAY_TARGET_FULL = MessageKey.error("economy.pay.target-full", "name");
    public static final MessageKey PAY_CANCELLED = MessageKey.info("economy.pay.cancelled");
    public static final MessageKey PAY_CONFIRM_TITLE = MessageKey.ui("economy.pay.confirm-title");
    public static final MessageKey PAY_CONFIRM_BODY = MessageKey.ui("economy.pay.confirm-body", "name", "amount", "left");
    /** The confirmation for a payer without a daily limit (no "you can send ... more today" line). */
    public static final MessageKey PAY_CONFIRM_BODY_UNLIMITED = MessageKey.ui("economy.pay.confirm-body-unlimited", "name", "amount");
    public static final MessageKey PAY_CONFIRM_BUTTON = MessageKey.ui("economy.pay.confirm-button");

    public static final MessageKey TOP_TITLE = MessageKey.ui("economy.top.title");
    public static final MessageKey TOP_HEADER = MessageKey.chat("economy.top.header", "page", "pages");
    public static final MessageKey TOP_LINE = MessageKey.chat("economy.top.line", "rank", "name", "amount");
    public static final MessageKey TOP_YOU = MessageKey.chat("economy.top.you", "rank", "amount");
    public static final MessageKey TOP_EMPTY = MessageKey.chat("economy.top.empty");
    public static final MessageKey TOP_PAGE = MessageKey.ui("economy.top.page", "page", "pages");
    public static final MessageKey TOP_NEXT = MessageKey.ui("economy.top.next");
    public static final MessageKey TOP_PREVIOUS = MessageKey.ui("economy.top.previous");

    public static final MessageKey ECO_GIVEN = MessageKey.chat("economy.admin.given", "name", "amount", "balance");
    public static final MessageKey ECO_TAKEN = MessageKey.chat("economy.admin.taken", "name", "amount", "balance");
    public static final MessageKey ECO_SET = MessageKey.chat("economy.admin.set", "name", "amount");
    public static final MessageKey ECO_GIVEN_SHARDS = MessageKey.chat("economy.admin.given-shards", "name", "amount", "balance");
    public static final MessageKey ECO_TAKEN_SHARDS = MessageKey.chat("economy.admin.taken-shards", "name", "amount", "balance");
    public static final MessageKey ECO_SET_SHARDS = MessageKey.chat("economy.admin.set-shards", "name", "amount");
    public static final MessageKey ECO_FAILED = MessageKey.chat("economy.admin.failed", "reason");
    public static final MessageKey ECO_HISTORY_HEADER = MessageKey.chat("economy.admin.history-header", "name", "page");
    public static final MessageKey ECO_HISTORY_LINE = MessageKey.chat("economy.admin.history-line", "id", "sign", "amount", "kind", "ago", "balance");
    public static final MessageKey ECO_HISTORY_EMPTY = MessageKey.chat("economy.admin.history-empty");
    public static final MessageKey ECO_RESUMED = MessageKey.chat("economy.admin.resumed");

    public static final MessageKey HUB_LABEL = MessageKey.ui("economy.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("economy.hub.description");
    public static final MessageKey HUB_TITLE = MessageKey.ui("economy.hub.title");
    public static final MessageKey HUB_BODY = MessageKey.ui("economy.hub.body", "amount", "shards", "rank", "left");
    /** The money page without the daily limit line (none for this player, or it couldn't be loaded). */
    public static final MessageKey HUB_BODY_UNLIMITED = MessageKey.ui("economy.hub.body-unlimited", "amount", "shards", "rank");
    /** The red line under the money page when today's pay total couldn't be loaded. */
    public static final MessageKey HUB_LIMIT_FAILED = MessageKey.error("economy.hub.limit-failed");
    public static final MessageKey HUB_PAY = MessageKey.ui("economy.hub.pay");
    public static final MessageKey HUB_TOP = MessageKey.ui("economy.hub.top");
    public static final MessageKey PAY_FORM_TITLE = MessageKey.ui("economy.pay.form-title");
    public static final MessageKey PAY_FORM_PLAYER = MessageKey.ui("economy.pay.form-player");
    public static final MessageKey PAY_FORM_AMOUNT = MessageKey.ui("economy.pay.form-amount");

    public static final MessageKey SETTING_NOTIFICATIONS = MessageKey.ui("economy.settings.notifications");
    public static final MessageKey SETTING_NOTIFICATIONS_DESCRIPTION = MessageKey.ui("economy.settings.notifications-description");

    private EconomyMessages() {
    }
}
