package net.siftvanilla.siftcore.feature.combat;

import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Which combat alerts a staff member with {@code siftcore.admin.combat} gets: the options of {@code staff-combat-alerts}. */
public enum StaffAlerts {
    /** Players who log out in combat. */
    COMBAT_LOGS("combat-logs", CombatMessages.OPTION_STAFF_LOGS),
    /** Combat logs and kills that didn't count (signs of kill farming). */
    LOGS_AND_FARMING("combat-logs-and-farming", CombatMessages.OPTION_STAFF_LOGS_FARMING),
    /** None. */
    OFF("off", OptionTexts.ALERT_OFF);

    private final String id;
    private final MessageKey label;

    StaffAlerts(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored option id. */
    public String id() {
        return this.id;
    }

    public MessageKey label() {
        return this.label;
    }

    /** Whether combat logs are reported. */
    public boolean combatLogs() {
        return this != OFF;
    }

    /** Whether kills that didn't count are reported. */
    public boolean farming() {
        return this == LOGS_AND_FARMING;
    }
}
