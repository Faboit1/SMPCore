package net.siftvanilla.siftcore.feature.staff;

import java.util.Locale;

/** The kinds of punishment staff can give. Bans and mutes last; kicks and warnings are one-off records. */
public enum PunishmentType {
    BAN(true),
    MUTE(true),
    KICK(false),
    WARN(false);

    private final boolean lasting;

    PunishmentType(boolean lasting) {
        this.lasting = lasting;
    }

    /** True for punishments that stay in force until they expire or are lifted. */
    public boolean lasting() {
        return this.lasting;
    }

    /** The value stored in the database. */
    public String id() {
        return name();
    }

    /** Parses a stored value; unknown values return null. */
    public static PunishmentType parse(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
