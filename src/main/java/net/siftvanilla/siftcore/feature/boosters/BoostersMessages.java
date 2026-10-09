package net.siftvanilla.siftcore.feature.boosters;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the sell boosters ({@code lang/boosters.yml}). */
public final class BoostersMessages {

    // ------------------------------------------------------------------ announcements to everyone
    public static final MessageKey ANNOUNCE_STARTED = MessageKey.notify("boosters.announce.started", "name", "percent", "time");
    public static final MessageKey ANNOUNCE_STARTED_STAFF = MessageKey.notify("boosters.announce.started-staff", "name", "percent", "time");
    public static final MessageKey ANNOUNCE_STARTED_SERVER = MessageKey.notify("boosters.announce.started-server", "percent", "time");
    public static final MessageKey ANNOUNCE_REASON = MessageKey.chat("boosters.announce.reason", "reason");
    public static final MessageKey ANNOUNCE_QUEUED = MessageKey.chat("boosters.announce.queued", "name", "percent", "time", "position");
    public static final MessageKey ANNOUNCE_QUEUED_OTHER = MessageKey.chat("boosters.announce.queued-other", "percent", "time", "position");
    public static final MessageKey ANNOUNCE_ENDED = MessageKey.chat("boosters.announce.ended", "percent");
    public static final MessageKey ANNOUNCE_ENDED_EARLY = MessageKey.chat("boosters.announce.ended-early", "percent");

    // ------------------------------------------------------------------ names and lengths
    public static final MessageKey SOMEONE = MessageKey.ui("boosters.someone");
    public static final MessageKey SERVER = MessageKey.ui("boosters.server");
    public static final MessageKey DAY_ONE = MessageKey.ui("boosters.time.day-one", "count");
    public static final MessageKey DAY_MANY = MessageKey.ui("boosters.time.day-many", "count");
    public static final MessageKey HOUR_ONE = MessageKey.ui("boosters.time.hour-one", "count");
    public static final MessageKey HOUR_MANY = MessageKey.ui("boosters.time.hour-many", "count");
    public static final MessageKey MINUTE_ONE = MessageKey.ui("boosters.time.minute-one", "count");
    public static final MessageKey MINUTE_MANY = MessageKey.ui("boosters.time.minute-many", "count");
    public static final MessageKey SECOND_ONE = MessageKey.ui("boosters.time.second-one", "count");
    public static final MessageKey SECOND_MANY = MessageKey.ui("boosters.time.second-many", "count");

    // ------------------------------------------------------------------ the boss bar
    public static final MessageKey BAR = MessageKey.ui("boosters.bar.title", "percent", "time", "name");
    public static final MessageKey BAR_SERVER = MessageKey.ui("boosters.bar.title-server", "percent", "time");

    // ------------------------------------------------------------------ /booster
    public static final MessageKey TITLE = MessageKey.ui("boosters.dialog.title");
    public static final MessageKey ACTIVE = MessageKey.ui("boosters.dialog.active", "percent");
    public static final MessageKey LEFT = MessageKey.ui("boosters.dialog.left", "time", "length");
    public static final MessageKey FROM = MessageKey.ui("boosters.dialog.from", "name");
    public static final MessageKey FROM_SERVER = MessageKey.ui("boosters.dialog.from-server");
    public static final MessageKey NONE = MessageKey.ui("boosters.dialog.none");
    public static final MessageKey EXPLAIN = MessageKey.ui("boosters.dialog.explain");
    public static final MessageKey QUEUE_HEADER = MessageKey.ui("boosters.dialog.queue-header");
    public static final MessageKey QUEUE_LINE = MessageKey.ui("boosters.dialog.queue-line", "position", "percent", "time", "name");
    public static final MessageKey QUEUE_LINE_SERVER = MessageKey.ui("boosters.dialog.queue-line-server", "position", "percent", "time");
    public static final MessageKey QUEUE_MORE = MessageKey.ui("boosters.dialog.queue-more", "count");
    public static final MessageKey QUEUE_EMPTY = MessageKey.ui("boosters.dialog.queue-empty");
    public static final MessageKey HIDE_BAR = MessageKey.ui("boosters.dialog.hide-bar");
    public static final MessageKey SHOW_BAR = MessageKey.ui("boosters.dialog.show-bar");
    public static final MessageKey BAR_HIDDEN = MessageKey.success("boosters.dialog.bar-hidden");
    public static final MessageKey BAR_SHOWN = MessageKey.success("boosters.dialog.bar-shown");

    // ------------------------------------------------------------------ menu and settings
    public static final MessageKey HUB_LABEL = MessageKey.ui("boosters.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("boosters.hub.description");
    public static final MessageKey TOGGLE_LABEL = MessageKey.ui("boosters.toggle.label");
    public static final MessageKey TOGGLE_DESCRIPTION = MessageKey.ui("boosters.toggle.description");
    public static final MessageKey SETTING_NEWS = MessageKey.ui("boosters.settings.announcements");
    public static final MessageKey SETTING_NEWS_DESCRIPTION = MessageKey.ui("boosters.settings.announcements-description");
    /** The "only new boosters" option of the announcement filter. */
    public static final MessageKey SETTING_NEWS_STARTS = MessageKey.ui("boosters.settings.announcements-starts");
    /** The bar button in /booster when the server fixed the booster bar setting meanwhile. */
    public static final MessageKey BAR_FIXED = MessageKey.error("boosters.dialog.bar-fixed");

    // ------------------------------------------------------------------ /sift booster (staff and console)
    public static final MessageKey ADMIN_STARTED = MessageKey.chat("boosters.admin.started", "id", "percent", "time");
    public static final MessageKey ADMIN_QUEUED = MessageKey.chat("boosters.admin.queued", "id", "percent", "time", "position");
    public static final MessageKey ADMIN_STOPPED = MessageKey.chat("boosters.admin.stopped", "id", "percent");
    public static final MessageKey ADMIN_REMOVED = MessageKey.chat("boosters.admin.removed", "id", "percent");
    public static final MessageKey ADMIN_NONE_RUNNING = MessageKey.chat("boosters.admin.none-running").withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_UNKNOWN = MessageKey.chat("boosters.admin.unknown", "id").withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_BAD_PERCENT = MessageKey.chat("boosters.admin.bad-percent", "max").withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_BAD_DURATION = MessageKey.chat("boosters.admin.bad-duration", "min", "max").withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_BAD_DURATION_INPUT = MessageKey.chat("boosters.admin.bad-duration-input", "input")
        .withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_QUEUE_FULL = MessageKey.chat("boosters.admin.queue-full", "count").withFeedback(Feedback.ERROR);
    public static final MessageKey ADMIN_FAILED = MessageKey.chat("boosters.admin.failed", "reason").withFeedback(Feedback.ERROR);
    public static final MessageKey LIST_HEADER = MessageKey.chat("boosters.admin.list-header", "count", "max");
    public static final MessageKey LIST_ACTIVE = MessageKey.chat("boosters.admin.list-active", "id", "percent", "left", "length", "name", "source",
        "capped");
    public static final MessageKey LIST_WAITING = MessageKey.chat("boosters.admin.list-waiting", "id", "percent", "position", "length", "name",
        "source", "capped");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("boosters.admin.list-empty");
    public static final MessageKey SOURCE_STORE = MessageKey.ui("boosters.admin.source-store", "ref");
    public static final MessageKey SOURCE_STAFF = MessageKey.ui("boosters.admin.source-staff");
    public static final MessageKey SOURCE_STAFF_REASON = MessageKey.ui("boosters.admin.source-staff-reason", "reason");
    /** After a booster bought for more than it pays now (sell.max-percent was lowered). */
    public static final MessageKey LIST_CAPPED = MessageKey.ui("boosters.admin.list-capped", "percent");

    private BoostersMessages() {
    }
}
