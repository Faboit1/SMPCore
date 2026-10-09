package net.siftvanilla.siftcore.feature.combat;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the combat feature ({@code lang/combat.yml}). */
public final class CombatMessages {

    public static final MessageKey TAG_ACTION_BAR = MessageKey.status("combat.tag.action-bar", "time");
    public static final MessageKey TAG_ENDED = MessageKey.success("combat.tag.ended");

    public static final MessageKey BLOCKED_COMMAND = MessageKey.error("combat.blocked.command", "time");
    public static final MessageKey BLOCKED_ENDER_PEARL = MessageKey.error("combat.blocked.ender-pearl", "time");
    public static final MessageKey BLOCKED_ELYTRA = MessageKey.error("combat.blocked.elytra", "time");
    /**
     * Sent each time a tagged player's step into spawn is undone (several times a second while they push on), so it
     * stays on the action bar whatever their feedback channel: in chat it would pile up.
     */
    public static final MessageKey BLOCKED_SPAWN = MessageKey.error("combat.blocked.spawn", "time").asStatus();

    public static final MessageKey LOGOUT_ANNOUNCE = MessageKey.chat("combat.logout.announce", "name");
    public static final MessageKey LOGOUT_ANNOUNCE_KILLED = MessageKey.chat("combat.logout.announce-killed", "name", "killer");

    public static final MessageKey DEATH_KILLED = MessageKey.chat("combat.death.killed", "victim", "killer");
    public static final MessageKey DEATH_KILLED_USING = MessageKey.chat("combat.death.killed-using", "victim", "killer", "item");

    public static final MessageKey STREAK_REACHED = MessageKey.chat("combat.streak.reached", "name", "count");
    public static final MessageKey STREAK_ENDED = MessageKey.chat("combat.streak.ended", "killer", "victim", "count");

    public static final MessageKey STATUS_TAGGED = MessageKey.status("combat.status.tagged", "time");
    public static final MessageKey STATUS_CLEAR = MessageKey.status("combat.status.clear");

    public static final MessageKey ADMIN_STATUS_TAGGED = MessageKey.chat("combat.admin.status-tagged", "name", "time", "attacker");
    public static final MessageKey ADMIN_STATUS_TAGGED_NO_HIT = MessageKey.chat("combat.admin.status-tagged-no-hit", "name", "time");
    public static final MessageKey ADMIN_STATUS_CLEAR = MessageKey.chat("combat.admin.status-clear", "name");
    public static final MessageKey ADMIN_TAGGED = MessageKey.chat("combat.admin.tagged", "name", "time");
    public static final MessageKey ADMIN_UNTAGGED = MessageKey.chat("combat.admin.untagged", "name");
    public static final MessageKey ADMIN_INVALID_TIME = MessageKey.chat("combat.admin.invalid-time", "input", "max");
    public static final MessageKey ADMIN_KILLS_HEADER = MessageKey.chat("combat.admin.kills-header", "name", "page");
    public static final MessageKey ADMIN_KILLS_COUNTED = MessageKey.chat("combat.admin.kills-counted", "ago", "killer", "victim");
    public static final MessageKey ADMIN_KILLS_NOT_COUNTED = MessageKey.chat("combat.admin.kills-not-counted", "ago", "killer", "victim", "reason");
    public static final MessageKey ADMIN_KILLS_EMPTY = MessageKey.chat("combat.admin.kills-empty");

    public static final MessageKey REASON_SAME_TEAM = MessageKey.ui("combat.reason.same-team");
    public static final MessageKey REASON_FRIENDS = MessageKey.ui("combat.reason.friends");
    public static final MessageKey REASON_SAME_IP = MessageKey.ui("combat.reason.same-ip");
    public static final MessageKey REASON_REPEATED_PAIR = MessageKey.ui("combat.reason.repeated-pair");
    public static final MessageKey REASON_CANCELLED = MessageKey.ui("combat.reason.cancelled");

    /** A new combat tag (not a refresh) from a hit: the {@code combat-tag-alert} setting picks where it shows. */
    public static final MessageKey TAG_STARTED = MessageKey.chat("combat.tag.started", "name", "time");
    public static final MessageKey TAG_STARTED_TITLE = MessageKey.chat("combat.tag.started-title", "name", "time");
    /** A new combat tag given by staff (no opponent). */
    public static final MessageKey TAG_STARTED_STAFF = MessageKey.chat("combat.tag.started-staff", "time");
    public static final MessageKey TAG_STARTED_STAFF_TITLE = MessageKey.chat("combat.tag.started-staff-title", "time");
    /** The combat timer on the boss bar ({@code combat-timer-display}). */
    public static final MessageKey TAG_BOSS_BAR = MessageKey.ui("combat.tag.boss-bar", "time");
    /** The end of combat as a title ({@code combat-end-notice}); the other styles use {@link #TAG_ENDED}. */
    public static final MessageKey TAG_ENDED_TITLE = MessageKey.chat("combat.tag.ended-title");

    /** The killer's own result ({@code kill-feedback}). */
    public static final MessageKey KILL_COUNTED = MessageKey.chat("combat.kill.counted", "name", "streak");
    public static final MessageKey KILL_COUNTED_TITLE = MessageKey.chat("combat.kill.counted-title", "name", "streak");
    public static final MessageKey KILL_NOT_COUNTED = MessageKey.chat("combat.kill.not-counted", "name", "reason");
    public static final MessageKey KILL_NOT_COUNTED_PLAIN = MessageKey.chat("combat.kill.not-counted-plain", "name");
    public static final MessageKey KILL_NOT_COUNTED_TITLE = MessageKey.chat("combat.kill.not-counted-title", "name");

    /** The victim's private lines after a death ({@code death-coordinates}, {@code death-recap}). */
    public static final MessageKey DEATH_LOCATION = MessageKey.chat("combat.death.location", "world", "x", "y", "z");
    public static final MessageKey DEATH_LOCATION_HIDDEN = MessageKey.chat("combat.death.location-hidden", "world");
    public static final MessageKey DEATH_RECAP = MessageKey.chat("combat.death.recap", "killer", "hearts");
    public static final MessageKey DEATH_RECAP_USING = MessageKey.chat("combat.death.recap-using", "killer", "hearts", "item");

    /** Staff alerts ({@code staff-combat-alerts}). */
    public static final MessageKey STAFF_COMBAT_LOG = MessageKey.chat("combat.staff.combat-log", "name", "time", "attacker");
    public static final MessageKey STAFF_COMBAT_LOG_NO_HIT = MessageKey.chat("combat.staff.combat-log-no-hit", "name", "time");
    public static final MessageKey STAFF_NOT_COUNTED = MessageKey.chat("combat.staff.not-counted", "killer", "victim", "reason");

    public static final MessageKey SETTING_DEATH_MESSAGES = MessageKey.ui("combat.settings.death-messages");
    public static final MessageKey SETTING_DEATH_MESSAGES_DESCRIPTION = MessageKey.ui("combat.settings.death-messages-description");
    public static final MessageKey SETTING_STREAKS = MessageKey.ui("combat.settings.kill-streak-announcements");
    public static final MessageKey SETTING_STREAKS_DESCRIPTION = MessageKey.ui("combat.settings.kill-streak-announcements-description");
    public static final MessageKey SETTING_LOG_ANNOUNCEMENTS = MessageKey.ui("combat.settings.combat-log-announcements");
    public static final MessageKey SETTING_LOG_ANNOUNCEMENTS_DESCRIPTION = MessageKey.ui("combat.settings.combat-log-announcements-description");
    public static final MessageKey SETTING_TIMER = MessageKey.ui("combat.settings.combat-timer-display");
    public static final MessageKey SETTING_TIMER_DESCRIPTION = MessageKey.ui("combat.settings.combat-timer-display-description");
    public static final MessageKey SETTING_TAG_ALERT = MessageKey.ui("combat.settings.combat-tag-alert");
    public static final MessageKey SETTING_TAG_ALERT_DESCRIPTION = MessageKey.ui("combat.settings.combat-tag-alert-description");
    public static final MessageKey SETTING_KILL_FEEDBACK = MessageKey.ui("combat.settings.kill-feedback");
    public static final MessageKey SETTING_KILL_FEEDBACK_DESCRIPTION = MessageKey.ui("combat.settings.kill-feedback-description");
    public static final MessageKey SETTING_DEATH_COORDINATES = MessageKey.ui("combat.settings.death-coordinates");
    public static final MessageKey SETTING_DEATH_COORDINATES_DESCRIPTION = MessageKey.ui("combat.settings.death-coordinates-description");
    public static final MessageKey SETTING_END_NOTICE = MessageKey.ui("combat.settings.combat-end-notice");
    public static final MessageKey SETTING_END_NOTICE_DESCRIPTION = MessageKey.ui("combat.settings.combat-end-notice-description");
    public static final MessageKey SETTING_DEATH_RECAP = MessageKey.ui("combat.settings.death-recap");
    public static final MessageKey SETTING_DEATH_RECAP_DESCRIPTION = MessageKey.ui("combat.settings.death-recap-description");
    public static final MessageKey SETTING_STAFF_ALERTS = MessageKey.ui("combat.settings.staff-combat-alerts");
    public static final MessageKey SETTING_STAFF_ALERTS_DESCRIPTION = MessageKey.ui("combat.settings.staff-combat-alerts-description");

    public static final MessageKey OPTION_DEATHS_PVP = MessageKey.ui("combat.settings.options.pvp");
    public static final MessageKey OPTION_STAFF_LOGS = MessageKey.ui("combat.settings.options.combat-logs");
    public static final MessageKey OPTION_STAFF_LOGS_FARMING = MessageKey.ui("combat.settings.options.combat-logs-and-farming");

    private CombatMessages() {
    }

    /** The text of a not-counted reason. */
    static MessageKey reason(AntiFarm.Reason reason) {
        return switch (reason) {
            case SAME_TEAM -> REASON_SAME_TEAM;
            case FRIENDS -> REASON_FRIENDS;
            case SAME_IP -> REASON_SAME_IP;
            case REPEATED_PAIR -> REASON_REPEATED_PAIR;
            case CANCELLED -> REASON_CANCELLED;
        };
    }
}
