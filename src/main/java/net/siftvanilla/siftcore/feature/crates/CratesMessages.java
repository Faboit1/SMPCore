package net.siftvanilla.siftcore.feature.crates;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the crates feature ({@code lang/crates.yml}). */
public final class CratesMessages {

    // ------------------------------------------------------------------ shared pieces (ui)

    /** {@code 1 Basic key}. */
    public static final MessageKey KEYS_ONE = MessageKey.ui("crates.keys.one", "name");
    /** {@code 3 Basic keys}. */
    public static final MessageKey KEYS_MANY = MessageKey.ui("crates.keys.many", "count", "name");
    /** A key count without the crate: no keys, 1 key, 3 keys. */
    public static final MessageKey COUNT_NONE = MessageKey.ui("crates.count.none");
    public static final MessageKey COUNT_ONE = MessageKey.ui("crates.count.one");
    public static final MessageKey COUNT_MANY = MessageKey.ui("crates.count.many", "count");

    // ------------------------------------------------------------------ opening

    public static final MessageKey NO_KEYS = MessageKey.error("crates.open.no-keys", "name");
    public static final MessageKey UNKNOWN_CRATE = MessageKey.error("crates.open.unknown", "input");
    public static final MessageKey NOTHING_TO_WIN = MessageKey.error("crates.open.nothing", "name");
    public static final MessageKey OPEN_CANCELLED = MessageKey.info("crates.open.cancelled", "name");
    public static final MessageKey OPEN_FAILED = MessageKey.error("crates.open.failed");
    public static final MessageKey BALANCE_FULL = MessageKey.error("crates.open.balance-full");
    public static final MessageKey KEY_LIMIT = MessageKey.error("crates.open.key-limit", "name");
    public static final MessageKey OPENING = MessageKey.error("crates.open.busy");
    public static final MessageKey IN_COMBAT = MessageKey.error("crates.open.in-combat", "time");
    public static final MessageKey WON = MessageKey.chat("crates.open.won", "reward", "name").withFeedback(Feedback.SUCCESS);
    public static final MessageKey WON_CLAIM_BOX = MessageKey.chat("crates.open.won-claim-box", "reward", "name")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey ANNOUNCE = MessageKey.chat("crates.open.announce", "player", "reward", "name");
    /** The receipt of several openings in a row: a line per reward won, with how often. */
    public static final MessageKey BATCH_WON = MessageKey.chat("crates.open.batch", "count", "name", "rewards")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey BATCH_WON_CLAIM_BOX = MessageKey.chat("crates.open.batch-claim-box", "count", "name", "rewards")
        .withFeedback(Feedback.SUCCESS);
    /** The short receipt of several openings in a row, for players who want receipts above the hotbar. */
    public static final MessageKey BATCH_WON_SHORT = MessageKey.chat("crates.open.batch-short", "count", "name", "reward")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey BATCH_LINE = MessageKey.ui("crates.open.batch-line", "reward", "times");
    public static final MessageKey BATCH_LINE_ONCE = MessageKey.ui("crates.open.batch-line-once", "reward");
    public static final MessageKey BULK_LIMIT = MessageKey.error("crates.open.bulk-limit", "max");
    public static final MessageKey KEYS_RECEIVED = MessageKey.notify("crates.keys.received", "keys");
    public static final MessageKey REMINDER = MessageKey.chat("crates.keys.reminder", "keys");

    // ------------------------------------------------------------------ dialogs

    public static final MessageKey LIST_TITLE = MessageKey.ui("crates.list.title");
    public static final MessageKey LIST_ENTRY = MessageKey.ui("crates.list.entry", "name", "keys");
    public static final MessageKey LIST_EMPTY = MessageKey.ui("crates.list.empty");
    public static final MessageKey LIST_KEYALL = MessageKey.ui("crates.list.keyall", "time", "keys");
    public static final MessageKey LIST_OPEN = MessageKey.ui("crates.list.open", "name");
    public static final MessageKey LIST_PREVIEW = MessageKey.ui("crates.list.preview", "name");

    public static final MessageKey VIEW_TITLE = MessageKey.ui("crates.view.title", "name");
    public static final MessageKey VIEW_BODY = MessageKey.ui("crates.view.body", "keys", "rewards");
    public static final MessageKey VIEW_OPEN = MessageKey.ui("crates.view.open");
    public static final MessageKey VIEW_PREVIEW = MessageKey.ui("crates.view.preview");

    public static final MessageKey RESULT_WON = MessageKey.ui("crates.result.won", "reward", "rarity");
    public static final MessageKey RESULT_LEFT = MessageKey.ui("crates.result.left", "keys");
    public static final MessageKey RESULT_LAST = MessageKey.ui("crates.result.last");
    public static final MessageKey RESULT_CLAIM_BOX = MessageKey.ui("crates.result.claim-box");
    public static final MessageKey RESULT_AGAIN = MessageKey.ui("crates.result.again");
    public static final MessageKey RESULT_MANY = MessageKey.ui("crates.result.many", "count");
    public static final MessageKey RESULT_BATCH = MessageKey.ui("crates.result.batch", "count");
    public static final MessageKey VIEW_OPEN_MANY = MessageKey.ui("crates.view.open-many", "count");

    // ------------------------------------------------------------------ preview

    public static final MessageKey PREVIEW_TITLE = MessageKey.ui("crates.preview.title", "name");
    public static final MessageKey PREVIEW_NAME = MessageKey.ui("crates.preview.name", "reward");
    public static final MessageKey PREVIEW_CHANCE = MessageKey.ui("crates.preview.chance", "chance");
    public static final MessageKey PREVIEW_RARITY = MessageKey.ui("crates.preview.rarity", "rarity");
    public static final MessageKey PREVIEW_WORTH = MessageKey.ui("crates.preview.worth", "worth");
    public static final MessageKey PREVIEW_OPENS = MessageKey.ui("crates.preview.opens", "name");
    public static final MessageKey PREVIEW_SORT_ORDER = MessageKey.ui("crates.preview.sort-order");
    public static final MessageKey PREVIEW_SORT_LIKELY = MessageKey.ui("crates.preview.sort-likely");
    public static final MessageKey PREVIEW_SORT_RAREST = MessageKey.ui("crates.preview.sort-rarest");
    public static final MessageKey PREVIEW_OPEN = MessageKey.ui("crates.preview.open");
    public static final MessageKey PREVIEW_OPEN_LORE = MessageKey.ui("crates.preview.open-lore", "keys");
    public static final MessageKey PREVIEW_OPEN_MANY_LORE = MessageKey.ui("crates.preview.open-many-lore", "count");
    public static final MessageKey PREVIEW_NO_KEYS = MessageKey.ui("crates.preview.no-keys");
    public static final MessageKey PREVIEW_NO_KEYS_LORE = MessageKey.ui("crates.preview.no-keys-lore");

    // ------------------------------------------------------------------ keyall

    public static final MessageKey KEYALL_COUNTDOWN = MessageKey.notify("crates.keyall.countdown", "time", "keys");
    public static final MessageKey KEYALL_ACTIONBAR = MessageKey.status("crates.keyall.action-bar", "time");
    public static final MessageKey KEYALL_DONE = MessageKey.notify("crates.keyall.done", "keys");
    public static final MessageKey KEYALL_MISSED_AFK = MessageKey.chat("crates.keyall.missed-afk", "keys");
    public static final MessageKey KEYALL_INFO = MessageKey.chat("crates.keyall.info", "time", "keys");
    public static final MessageKey KEYALL_OFF = MessageKey.chat("crates.keyall.off");
    public static final MessageKey KEYALL_RAN = MessageKey.chat("crates.keyall.ran", "count", "keys");
    public static final MessageKey KEYALL_STOPPED = MessageKey.chat("crates.keyall.stopped");
    public static final MessageKey KEYALL_NOBODY = MessageKey.chat("crates.keyall.nobody");
    public static final MessageKey KEYALL_SET = MessageKey.chat("crates.keyall.set", "time");
    public static final MessageKey KEYALL_BAD_TIME = MessageKey.error("crates.keyall.bad-time", "input");

    // ------------------------------------------------------------------ staff

    public static final MessageKey GIVEN = MessageKey.chat("crates.admin.given", "player", "keys", "total");
    public static final MessageKey TAKEN = MessageKey.chat("crates.admin.taken", "player", "keys", "total");
    public static final MessageKey NOT_ENOUGH = MessageKey.chat("crates.admin.not-enough", "player", "keys");
    public static final MessageKey DUPLICATE = MessageKey.chat("crates.admin.duplicate", "ref");
    public static final MessageKey FAILED = MessageKey.chat("crates.admin.failed", "reason");
    public static final MessageKey CHECK_HEADER = MessageKey.chat("crates.admin.check-header", "player");
    public static final MessageKey CHECK_LINE = MessageKey.chat("crates.admin.check-line", "name", "count");
    public static final MessageKey CHECK_EMPTY = MessageKey.chat("crates.admin.check-empty", "player");
    public static final MessageKey LOG_HEADER = MessageKey.chat("crates.admin.log-header", "player", "count");
    public static final MessageKey LOG_LINE = MessageKey.chat("crates.admin.log-line", "ago", "name", "reward");
    public static final MessageKey LOG_EMPTY = MessageKey.chat("crates.admin.log-empty", "player");
    public static final MessageKey INFO_HEADER = MessageKey.chat("crates.admin.info-header", "name", "count");
    public static final MessageKey INFO_LINE = MessageKey.chat("crates.admin.info-line", "chance", "reward", "rarity");
    public static final MessageKey INFO_VALUE = MessageKey.chat("crates.admin.info-value", "money", "shards", "items");
    public static final MessageKey INFO_KEYS = MessageKey.chat("crates.admin.info-keys", "amount", "name");
    public static final MessageKey INFO_COMMANDS = MessageKey.chat("crates.admin.info-commands", "chance");
    public static final MessageKey INFO_LEFT_OUT = MessageKey.chat("crates.admin.info-left-out", "count");
    public static final MessageKey CRATES_HEADER = MessageKey.chat("crates.admin.crates-header", "count");
    public static final MessageKey CRATES_LINE = MessageKey.chat("crates.admin.crates-line", "id", "name", "rewards", "keys");

    public static final MessageKey BLOCK_LOOK = MessageKey.error("crates.block.look");
    public static final MessageKey BLOCK_ADDED = MessageKey.chat("crates.block.added", "position", "name");
    public static final MessageKey BLOCK_TAKEN = MessageKey.error("crates.block.taken", "name");
    public static final MessageKey BLOCK_REMOVED = MessageKey.chat("crates.block.removed", "position");
    public static final MessageKey BLOCK_NOT_CRATE = MessageKey.error("crates.block.not-crate");
    public static final MessageKey BLOCK_IN_FILE = MessageKey.chat("crates.block.in-file", "position");
    public static final MessageKey BLOCK_BUSY = MessageKey.error("crates.block.busy");
    public static final MessageKey BLOCK_FAILED = MessageKey.error("crates.block.failed");
    public static final MessageKey BLOCK_PROTECTED = MessageKey.info("crates.block.protected", "name");
    public static final MessageKey BLOCK_NO_WORLD = MessageKey.error("crates.block.no-world", "input");
    public static final MessageKey BLOCK_LIST_HEADER = MessageKey.chat("crates.block.list-header", "count");
    public static final MessageKey BLOCK_LIST_LINE = MessageKey.chat("crates.block.list-line", "name", "position", "source");
    public static final MessageKey BLOCK_LIST_EMPTY = MessageKey.chat("crates.block.list-empty");
    public static final MessageKey BLOCK_SOURCE_FILE = MessageKey.ui("crates.block.source-file");
    public static final MessageKey BLOCK_SOURCE_PLACED = MessageKey.ui("crates.block.source-placed");

    // ------------------------------------------------------------------ settings and hub

    public static final MessageKey SETTING_WINS = MessageKey.ui("crates.settings.wins");
    public static final MessageKey SETTING_WINS_DESCRIPTION = MessageKey.ui("crates.settings.wins-description");
    public static final MessageKey SETTING_RECEIPT = MessageKey.ui("crates.settings.receipt");
    public static final MessageKey SETTING_RECEIPT_DESCRIPTION = MessageKey.ui("crates.settings.receipt-description");
    public static final MessageKey SETTING_KEY_REMINDER = MessageKey.ui("crates.settings.key-reminder");
    public static final MessageKey SETTING_KEY_REMINDER_DESCRIPTION = MessageKey.ui("crates.settings.key-reminder-description");
    public static final MessageKey SETTING_KEYALL = MessageKey.ui("crates.settings.keyall");
    public static final MessageKey SETTING_KEYALL_DESCRIPTION = MessageKey.ui("crates.settings.keyall-description");
    public static final MessageKey SETTING_QUICK_OPEN = MessageKey.ui("crates.settings.quick-open");
    public static final MessageKey SETTING_QUICK_OPEN_DESCRIPTION = MessageKey.ui("crates.settings.quick-open-description");
    public static final MessageKey SETTING_BULK_AMOUNT = MessageKey.ui("crates.settings.bulk-amount");
    public static final MessageKey SETTING_BULK_AMOUNT_DESCRIPTION = MessageKey.ui("crates.settings.bulk-amount-description");
    /** The unit of the bulk amount slider, written right after the number. */
    public static final MessageKey UNIT_KEYS = MessageKey.ui("crates.settings.unit-keys");
    public static final MessageKey OPTION_RAREST = MessageKey.ui("crates.settings.options.rarest");
    public static final MessageKey OPTION_QUICK_ONE = MessageKey.ui("crates.settings.options.quick-one");
    public static final MessageKey OPTION_QUICK_BULK = MessageKey.ui("crates.settings.options.quick-bulk");
    public static final MessageKey OPTION_QUICK_OFF = MessageKey.ui("crates.settings.options.quick-off");

    public static final MessageKey HUB_LABEL = MessageKey.ui("crates.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("crates.hub.description");

    private CratesMessages() {
    }
}
