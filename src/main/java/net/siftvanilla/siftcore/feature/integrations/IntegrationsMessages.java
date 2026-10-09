package net.siftvanilla.siftcore.feature.integrations;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Text of the integrations and the admin tools under /sift ({@code lang/integrations.yml}). Failures that go to chat
 * (command output worth keeping) carry the error feedback, so they render in the error colours with the error sound.
 */
public final class IntegrationsMessages {

    // ------------------------------------------------------------------ /sift integrations
    public static final MessageKey STATUS_HEADER = MessageKey.chat("integrations.status.header", "version");
    public static final MessageKey STATUS_LINE = MessageKey.chat("integrations.status.line", "name", "state");
    public static final MessageKey STATE_ACTIVE = MessageKey.ui("integrations.status.active");
    public static final MessageKey STATE_ACTIVE_DETAIL = MessageKey.ui("integrations.status.active-detail", "detail");
    public static final MessageKey STATE_MISSING = MessageKey.ui("integrations.status.missing");
    public static final MessageKey STATE_OFF = MessageKey.ui("integrations.status.off");
    public static final MessageKey STATE_FAILED = MessageKey.ui("integrations.status.failed");
    public static final MessageKey STATE_OVERRIDDEN = MessageKey.ui("integrations.status.overridden");

    // ------------------------------------------------------------------ /sift backup
    public static final MessageKey BACKUP_STARTED = MessageKey.chat("integrations.backup.started");
    public static final MessageKey BACKUP_DONE = MessageKey.chat("integrations.backup.done", "file", "size", "time");
    public static final MessageKey BACKUP_PRUNED = MessageKey.chat("integrations.backup.pruned", "count", "keep");
    public static final MessageKey BACKUP_FAILED = MessageKey.chat("integrations.backup.failed", "detail").withFeedback(Feedback.ERROR);
    public static final MessageKey BACKUP_RUNNING = MessageKey.chat("integrations.backup.running").withFeedback(Feedback.ERROR);
    public static final MessageKey BACKUP_MYSQL = MessageKey.chat("integrations.backup.mysql", "database");
    public static final MessageKey BACKUP_LIST_HEADER = MessageKey.chat("integrations.backup.list-header", "count", "folder");
    public static final MessageKey BACKUP_LIST_LINE = MessageKey.chat("integrations.backup.list-line", "file", "size", "age");
    public static final MessageKey BACKUP_LIST_EMPTY = MessageKey.chat("integrations.backup.list-empty");
    public static final MessageKey BACKUP_NEXT = MessageKey.chat("integrations.backup.next", "time", "keep");
    public static final MessageKey BACKUP_AUTOMATIC_OFF = MessageKey.chat("integrations.backup.automatic-off");

    // ------------------------------------------------------------------ /sift export
    public static final MessageKey EXPORT_STARTED = MessageKey.chat("integrations.export.started");
    public static final MessageKey EXPORT_DONE = MessageKey.chat("integrations.export.done", "balances", "rows", "folder", "time");
    public static final MessageKey EXPORT_FAILED = MessageKey.chat("integrations.export.failed", "detail").withFeedback(Feedback.ERROR);
    public static final MessageKey EXPORT_RUNNING = MessageKey.chat("integrations.export.running").withFeedback(Feedback.ERROR);

    // ------------------------------------------------------------------ /sift audit
    public static final MessageKey AUDIT_HEADER = MessageKey.chat("integrations.audit.header", "action", "player", "page");
    public static final MessageKey AUDIT_LINE = MessageKey.chat("integrations.audit.line", "time", "actor", "action", "target", "details");
    public static final MessageKey AUDIT_LINE_NO_TARGET = MessageKey.chat("integrations.audit.line-no-target", "time", "actor", "action", "details");
    public static final MessageKey AUDIT_EMPTY = MessageKey.chat("integrations.audit.empty");
    public static final MessageKey AUDIT_MORE = MessageKey.chat("integrations.audit.more", "command");
    public static final MessageKey AUDIT_FAILED = MessageKey.chat("integrations.audit.failed", "detail").withFeedback(Feedback.ERROR);
    public static final MessageKey AUDIT_ANY = MessageKey.ui("integrations.audit.any");
    public static final MessageKey AUDIT_AGO = MessageKey.ui("integrations.audit.ago", "time");

    // ------------------------------------------------------------------ /sift permissions, placeholders, docs
    public static final MessageKey PERMISSIONS_HEADER = MessageKey.chat("integrations.registry.permissions-header", "count", "page", "pages");
    public static final MessageKey PERMISSION_LINE = MessageKey.chat("integrations.registry.permission-line", "node", "description", "default");
    public static final MessageKey PLACEHOLDERS_HEADER = MessageKey.chat("integrations.registry.placeholders-header", "count", "page", "pages");
    public static final MessageKey PLACEHOLDER_LINE = MessageKey.chat("integrations.registry.placeholder-line", "name", "description", "value");
    public static final MessageKey REGISTRY_EMPTY = MessageKey.chat("integrations.registry.empty", "filter");
    public static final MessageKey REGISTRY_MORE = MessageKey.chat("integrations.registry.more", "command");
    public static final MessageKey DOCS_DONE = MessageKey.chat("integrations.registry.docs-done", "folder", "permissions", "placeholders");
    public static final MessageKey DOCS_FAILED = MessageKey.chat("integrations.registry.docs-failed", "detail").withFeedback(Feedback.ERROR);

    // ------------------------------------------------------------------ /sift store (staff and console)
    public static final MessageKey STORE_DELIVERED = MessageKey.chat("integrations.store.delivered", "what", "name", "ref");
    public static final MessageKey STORE_ALREADY = MessageKey.chat("integrations.store.already", "what", "name", "ref", "time");
    public static final MessageKey STORE_FAILED = MessageKey.chat("integrations.store.failed", "reason").withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_RANK_PENDING = MessageKey.chat("integrations.store.rank-pending", "ref", "detail").withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_UNKNOWN_PLAYER = MessageKey.chat("integrations.store.unknown-player", "name").withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_BAD_DURATION_INPUT = MessageKey.chat("integrations.store.bad-duration-input", "input").withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_BAD_BOOSTER_LENGTH = MessageKey.chat("integrations.store.bad-booster-length", "input")
        .withFeedback(Feedback.ERROR);
    /** After a booster bought for more than {@code sell.max-percent} was delivered: what it pays meanwhile. */
    public static final MessageKey STORE_BOOSTER_CAPPED = MessageKey.chat("integrations.store.booster-capped", "percent", "paid");
    public static final MessageKey STORE_CHECK = MessageKey.chat("integrations.store.check", "ref", "what", "name", "time", "state", "actor");
    public static final MessageKey STORE_CHECK_NONE = MessageKey.chat("integrations.store.check-none", "ref");
    public static final MessageKey STORE_HISTORY_HEADER = MessageKey.chat("integrations.store.history-header", "name", "count");
    public static final MessageKey STORE_HISTORY_LINE = MessageKey.chat("integrations.store.history-line", "time", "what", "ref", "state");
    public static final MessageKey STORE_HISTORY_EMPTY = MessageKey.chat("integrations.store.history-empty", "name");
    public static final MessageKey STORE_RESUMED = MessageKey.chat("integrations.store.resumed", "ref", "name", "what");
    public static final MessageKey STORE_WAS_REVOKED = MessageKey.chat("integrations.store.was-revoked", "ref")
        .withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_REVOKED = MessageKey.chat("integrations.store.revoked", "ref", "what", "name", "detail");
    public static final MessageKey STORE_ALREADY_REVOKED = MessageKey.chat("integrations.store.already-revoked", "ref");
    public static final MessageKey STORE_UNKNOWN_REF = MessageKey.chat("integrations.store.unknown-ref", "ref").withFeedback(Feedback.ERROR);
    public static final MessageKey STORE_REVOKE_PENDING = MessageKey.chat("integrations.store.revoke-pending", "ref", "detail")
        .withFeedback(Feedback.ERROR);
    public static final MessageKey REVOKE_TOOK = MessageKey.ui("integrations.store.revoke.took", "taken");
    public static final MessageKey REVOKE_TOOK_PART = MessageKey.ui("integrations.store.revoke.took-part", "taken", "total");
    public static final MessageKey REVOKE_KEYS = MessageKey.ui("integrations.store.revoke.keys", "command");
    public static final MessageKey REVOKE_RANK_CUT = MessageKey.ui("integrations.store.revoke.rank-cut", "rank", "time");
    public static final MessageKey REVOKE_RANK_REMOVED = MessageKey.ui("integrations.store.revoke.rank-removed", "rank");
    public static final MessageKey REVOKE_RANK_KEPT = MessageKey.ui("integrations.store.revoke.rank-kept", "rank");
    public static final MessageKey REVOKE_BOOSTER_ENDED = MessageKey.ui("integrations.store.revoke.booster-ended");
    public static final MessageKey REVOKE_BOOSTER_REMOVED = MessageKey.ui("integrations.store.revoke.booster-removed");
    public static final MessageKey REVOKE_BOOSTER_OVER = MessageKey.ui("integrations.store.revoke.booster-over");
    public static final MessageKey NOTIFY_REVOKED = MessageKey.chat("integrations.store.notify.revoked", "what");

    public static final MessageKey WHAT_MONEY = MessageKey.ui("integrations.store.what.money", "amount");
    public static final MessageKey WHAT_SHARDS = MessageKey.ui("integrations.store.what.shards", "amount");
    /** {@code <keys>} reads like the crates' own text ("1 Basic key"); {@code <amount>} and {@code <crate>} stay for older files. */
    public static final MessageKey WHAT_KEYS = MessageKey.ui("integrations.store.what.keys", "keys", "amount", "crate");
    public static final MessageKey WHAT_RANK = MessageKey.ui("integrations.store.what.rank", "rank", "time");
    public static final MessageKey WHAT_RANK_PERMANENT = MessageKey.ui("integrations.store.what.rank-permanent", "rank");
    public static final MessageKey WHAT_BOOSTER = MessageKey.ui("integrations.store.what.booster", "percent", "time");
    /** Who a booster from the server itself (the console) went to. */
    public static final MessageKey WHO_SERVER = MessageKey.ui("integrations.store.who-server");
    public static final MessageKey STATE_DONE = MessageKey.ui("integrations.store.state.done");
    public static final MessageKey STATE_PENDING = MessageKey.ui("integrations.store.state.pending");
    public static final MessageKey STATE_REVOKING = MessageKey.ui("integrations.store.state.revoking");
    public static final MessageKey STATE_REVOKED = MessageKey.ui("integrations.store.state.revoked", "reason");

    public static final MessageKey REASON_BAD_REF = MessageKey.ui("integrations.store.reason.bad-ref", "max");
    public static final MessageKey REASON_BAD_AMOUNT = MessageKey.ui("integrations.store.reason.bad-amount");
    public static final MessageKey REASON_TOO_MUCH = MessageKey.ui("integrations.store.reason.too-much", "max");
    public static final MessageKey REASON_UNKNOWN_CRATE = MessageKey.ui("integrations.store.reason.unknown-crate", "crates");
    public static final MessageKey REASON_NO_LUCKPERMS = MessageKey.ui("integrations.store.reason.no-luckperms");
    public static final MessageKey REASON_LUCKPERMS_OFF = MessageKey.ui("integrations.store.reason.luckperms-off");
    public static final MessageKey REASON_GROUP_NOT_ALLOWED = MessageKey.ui("integrations.store.reason.group-not-allowed", "groups");
    public static final MessageKey REASON_UNKNOWN_GROUP = MessageKey.ui("integrations.store.reason.unknown-group");
    public static final MessageKey REASON_BAD_DURATION = MessageKey.ui("integrations.store.reason.bad-duration", "min", "max");
    public static final MessageKey REASON_CANCELLED = MessageKey.ui("integrations.store.reason.cancelled");
    public static final MessageKey REASON_BALANCE_LIMIT = MessageKey.ui("integrations.store.reason.balance-limit");
    public static final MessageKey REASON_UNAVAILABLE = MessageKey.ui("integrations.store.reason.unavailable");
    public static final MessageKey REASON_STORAGE = MessageKey.ui("integrations.store.reason.storage");
    public static final MessageKey REASON_OTHER = MessageKey.ui("integrations.store.reason.other", "reason");
    public static final MessageKey REASON_UNKNOWN_BOOSTER = MessageKey.ui("integrations.store.reason.unknown-booster");
    public static final MessageKey REASON_BAD_BOOSTER_PERCENT = MessageKey.ui("integrations.store.reason.bad-booster-percent", "max");
    public static final MessageKey REASON_BAD_BOOSTER_DURATION = MessageKey.ui("integrations.store.reason.bad-booster-duration", "min", "max");
    public static final MessageKey REASON_NO_BOOSTERS = MessageKey.ui("integrations.store.reason.no-boosters");

    // ------------------------------------------------------------------ players
    public static final MessageKey NOTIFY_MONEY = MessageKey.notify("integrations.store.notify.money", "amount");
    public static final MessageKey NOTIFY_SHARDS = MessageKey.notify("integrations.store.notify.shards", "amount");
    public static final MessageKey NOTIFY_KEYS = MessageKey.notify("integrations.store.notify.keys", "keys", "amount", "crate");
    public static final MessageKey NOTIFY_RANK = MessageKey.notify("integrations.store.notify.rank", "rank", "time");
    public static final MessageKey NOTIFY_RANK_PERMANENT = MessageKey.notify("integrations.store.notify.rank-permanent", "rank");
    public static final MessageKey NOTIFY_BOOSTER = MessageKey.notify("integrations.store.notify.booster", "percent", "time");
    public static final MessageKey NOTIFY_BOOSTER_QUEUED = MessageKey.notify("integrations.store.notify.booster-queued", "percent", "time",
        "position");
    public static final MessageKey ANNOUNCE = MessageKey.chat("integrations.store.announce", "name", "what");

    // ------------------------------------------------------------------ /purchases
    public static final MessageKey PURCHASES_TITLE = MessageKey.ui("integrations.purchases.title");
    public static final MessageKey PURCHASES_TITLE_OTHER = MessageKey.ui("integrations.purchases.title-other", "name");
    public static final MessageKey PURCHASES_PAGE = MessageKey.ui("integrations.purchases.page", "page", "pages", "count");
    public static final MessageKey PURCHASES_EMPTY = MessageKey.ui("integrations.purchases.empty");
    public static final MessageKey PURCHASES_EMPTY_OTHER = MessageKey.ui("integrations.purchases.empty-other", "name");
    public static final MessageKey PURCHASES_WHAT = MessageKey.ui("integrations.purchases.what", "what");
    public static final MessageKey PURCHASES_DETAIL = MessageKey.ui("integrations.purchases.detail", "date", "time", "state", "ref");
    public static final MessageKey PURCHASES_STATE_DELIVERED = MessageKey.ui("integrations.purchases.state.delivered");
    public static final MessageKey PURCHASES_STATE_PENDING = MessageKey.ui("integrations.purchases.state.pending");
    public static final MessageKey PURCHASES_STATE_REVOKING = MessageKey.ui("integrations.purchases.state.revoking");
    public static final MessageKey PURCHASES_STATE_REVOKED = MessageKey.ui("integrations.purchases.state.revoked", "reason");
    public static final MessageKey PURCHASES_STATE_RUNNING = MessageKey.ui("integrations.purchases.state.running", "time");
    public static final MessageKey PURCHASES_STATE_QUEUED = MessageKey.ui("integrations.purchases.state.queued", "position");
    public static final MessageKey PURCHASES_PREVIOUS = MessageKey.ui("integrations.purchases.previous");
    public static final MessageKey PURCHASES_NEXT = MessageKey.ui("integrations.purchases.next");
    public static final MessageKey PURCHASES_HEADER = MessageKey.chat("integrations.purchases.header", "name", "count");
    public static final MessageKey PURCHASES_HELP = MessageKey.ui("integrations.purchases.help");

    // ------------------------------------------------------------------ Bedrock forms
    public static final MessageKey FORM_ACTION = MessageKey.ui("integrations.forms.action");

    private IntegrationsMessages() {
    }
}
