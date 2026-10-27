package net.siftvanilla.siftcore.core.player.options;

import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Whose plain teleport request ({@code /tpa}, never {@code /tpahere}) a player accepts without being asked: the
 * values of the shared {@code friends-tpa} setting. The ids are the ones the friends feature always stored.
 */
public enum AutoAccept {
    NOBODY("nobody", OptionTexts.AUTO_NOBODY),
    /** Favourite friends only (offered while the friends feature has favourites). */
    FAVOURITES("favourites", OptionTexts.AUTO_FAVOURITES),
    /** Every friend. */
    ALL("all", OptionTexts.AUTO_ALL),
    /** Every friend and every teammate. */
    FRIENDS_TEAM("friends-team", OptionTexts.AUTO_FRIENDS_TEAM);

    private final String id;
    private final MessageKey label;

    AutoAccept(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return this.id;
    }

    public MessageKey label() {
        return this.label;
    }
}
