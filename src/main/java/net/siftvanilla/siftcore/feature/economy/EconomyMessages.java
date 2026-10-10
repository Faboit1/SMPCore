package net.siftvanilla.siftcore.feature.economy;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the economy feature ({@code lang/economy.yml}). */
public final class EconomyMessages {

    /** {@code <shard-count>} is the shard amount (purple), so {@code <shards>} stays the shards colour for the word. */
    public static final MessageKey BALANCE_SELF = MessageKey.chat("economy.balance.self", "amount", "shard-count");
    public static final MessageKey BALANCE_OTHER = MessageKey.chat("economy.balance.other", "name", "amount", "shard-count");

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
    public static final MessageKey PAY_CONFIRM_TOOLTIP = MessageKey.ui("economy.pay.confirm-button-tooltip");

    public static final MessageKey TOP_TITLE = MessageKey.ui("economy.top.title");
    public static final MessageKey TOP_HEADER = MessageKey.chat("economy.top.header", "page", "pages");
    public static final MessageKey TOP_LINE = MessageKey.chat("economy.top.line", "rank", "name", "amount");
    public static final MessageKey TOP_YOU = MessageKey.chat("economy.top.you", "rank", "amount");
    public static final MessageKey TOP_EMPTY = MessageKey.chat("economy.top.empty");
    /** Under a full leaderboard: how many places it lists (baltop.size). */
    public static final MessageKey TOP_CAP = MessageKey.ui("economy.top.cap", "count");

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
    public static final MessageKey HUB_BODY = MessageKey.ui("economy.hub.body", "amount", "shard-count");
    /** The red line under the money page when today's pay total couldn't be loaded. */
    public static final MessageKey HUB_LIMIT_FAILED = MessageKey.error("economy.hub.limit-failed");
    public static final MessageKey HUB_PAY = MessageKey.ui("economy.hub.pay");
    public static final MessageKey HUB_PAY_TOOLTIP = MessageKey.ui("economy.hub.pay-tooltip");
    /** The Pay button's tooltip line for players with a daily limit. */
    public static final MessageKey HUB_PAY_LEFT = MessageKey.ui("economy.hub.pay-left", "left");
    public static final MessageKey HUB_TOP = MessageKey.ui("economy.hub.top");
    public static final MessageKey HUB_TOP_TOOLTIP = MessageKey.ui("economy.hub.top-tooltip", "count");
    /** The Richest players button's tooltip line for a player on the leaderboard. */
    public static final MessageKey HUB_TOP_RANK = MessageKey.ui("economy.hub.top-rank", "rank");
    public static final MessageKey PAY_FORM_TITLE = MessageKey.ui("economy.pay.form-title");
    public static final MessageKey PAY_FORM_PLAYER = MessageKey.ui("economy.pay.form-player");
    public static final MessageKey PAY_FORM_AMOUNT = MessageKey.ui("economy.pay.form-amount");
    public static final MessageKey PAY_FORM_SUBMIT_TOOLTIP = MessageKey.ui("economy.pay.form-submit-tooltip");

    /** A payment the receiver doesn't accept from this payer ({@code pay-accept-from}, or they ignore the payer). */
    public static final MessageKey PAY_NOT_ACCEPTED = MessageKey.error("economy.pay.not-accepted", "name");
    /** {@code /balance <name>} of a player whose {@code balance-privacy} leaves the viewer out. */
    public static final MessageKey BALANCE_PRIVATE = MessageKey.error("economy.balance.private", "name");

    /** The summary of payments received while offline ({@code pay-join-summary}). */
    public static final MessageKey AWAY_HEADER = MessageKey.ui("economy.away.header", "total");
    public static final MessageKey AWAY_LINE = MessageKey.ui("economy.away.line", "name", "amount");
    public static final MessageKey AWAY_LINE_MANY = MessageKey.ui("economy.away.line-many", "name", "amount", "count");
    public static final MessageKey AWAY_MORE = MessageKey.ui("economy.away.more", "count");

    public static final MessageKey SETTING_NOTIFICATIONS = MessageKey.ui("economy.settings.notifications");
    public static final MessageKey SETTING_NOTIFICATIONS_DESCRIPTION = MessageKey.ui("economy.settings.notifications-description");
    public static final MessageKey SETTING_CONFIRM_ABOVE = MessageKey.ui("economy.settings.confirm-above");
    public static final MessageKey SETTING_CONFIRM_ABOVE_DESCRIPTION = MessageKey.ui("economy.settings.confirm-above-description");
    public static final MessageKey SETTING_ACCEPT_FROM = MessageKey.ui("economy.settings.accept-from");
    public static final MessageKey SETTING_ACCEPT_FROM_DESCRIPTION = MessageKey.ui("economy.settings.accept-from-description");
    public static final MessageKey SETTING_JOIN_SUMMARY = MessageKey.ui("economy.settings.join-summary");
    public static final MessageKey SETTING_JOIN_SUMMARY_DESCRIPTION = MessageKey.ui("economy.settings.join-summary-description");
    public static final MessageKey SETTING_ALERT_MINIMUM = MessageKey.ui("economy.settings.alert-minimum");
    public static final MessageKey SETTING_ALERT_MINIMUM_DESCRIPTION = MessageKey.ui("economy.settings.alert-minimum-description");
    /** The {@code any} option of {@code pay-alert-minimum}. */
    public static final MessageKey SETTING_ALERT_MINIMUM_ANY = MessageKey.ui("economy.settings.alert-minimum-any");

    private EconomyMessages() {
    }
}
