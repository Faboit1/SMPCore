package net.siftvanilla.siftcore.feature.friends;

import java.util.Locale;

/**
 * Who may send a player friend requests ({@code friends-requests} setting). Privacy is a public choice, so a refusal
 * is reported honestly to the sender.
 */
public enum Privacy {
    /** Anyone. */
    EVERYONE,
    /** Friends of the player's friends, and teammates. */
    KNOWN,
    /** Nobody. */
    NOBODY;

    /** The stored value. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a stored value; anything unknown reads as the default ({@link #EVERYONE}). */
    public static Privacy parse(String value) {
        if (value != null) {
            for (Privacy privacy : values()) {
                if (privacy.id().equalsIgnoreCase(value.strip())) {
                    return privacy;
                }
            }
        }
        return EVERYONE;
    }

    /**
     * Whether a sender may send a request under this setting.
     *
     * @param known whether the sender is a friend of one of the target's friends or in the target's team
     */
    public boolean allows(boolean known) {
        return switch (this) {
            case EVERYONE -> true;
            case KNOWN -> known;
            case NOBODY -> false;
        };
    }
}
