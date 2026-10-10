package net.siftvanilla.siftcore.feature.combat;

import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * What the killer is told about their kill ({@code kill-feedback}): it counted, it didn't count and why, or it didn't
 * count without a reason. Two reasons stay private: a shared IP address (it would tell the killer something about
 * the victim's connection) and a plugin cancelling the credit (nothing a player can act on). Pure.
 */
enum KillNotice {
    COUNTED,
    NOT_COUNTED,
    NOT_COUNTED_PLAIN;

    /** The notice for a credited kill's decision. */
    static KillNotice of(AntiFarm.Decision decision) {
        if (decision.counted()) {
            return COUNTED;
        }
        return switch (decision.reason()) {
            case SAME_IP, CANCELLED -> NOT_COUNTED_PLAIN;
            case SAME_TEAM, FRIENDS, REPEATED_PAIR -> NOT_COUNTED;
        };
    }

    /**
     * The line for this notice in the killer's {@code kill-feedback} style: the short text for a title (which never
     * gives the reason), the full line for chat and the action bar, and null when they turned it off.
     */
    MessageKey key(AlertStyle style) {
        if (style == AlertStyle.OFF) {
            return null;
        }
        boolean title = style == AlertStyle.TITLE;
        return switch (this) {
            case COUNTED -> title ? CombatMessages.KILL_COUNTED_TITLE : CombatMessages.KILL_COUNTED;
            case NOT_COUNTED -> title ? CombatMessages.KILL_NOT_COUNTED_TITLE : CombatMessages.KILL_NOT_COUNTED;
            case NOT_COUNTED_PLAIN -> title ? CombatMessages.KILL_NOT_COUNTED_TITLE : CombatMessages.KILL_NOT_COUNTED_PLAIN;
        };
    }
}
