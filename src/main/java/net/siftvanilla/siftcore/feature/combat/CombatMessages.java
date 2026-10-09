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

    public static final MessageKey SETTING_DEATH_MESSAGES = MessageKey.ui("combat.settings.death-messages");
    public static final MessageKey SETTING_DEATH_MESSAGES_DESCRIPTION = MessageKey.ui("combat.settings.death-messages-description");

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
