package net.siftvanilla.siftcore.core.player.options;

import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Who may do something to a player (message, pay, see their balance...): the shared vocabulary of every "who can"
 * setting. {@code core.link.Relations#allows} answers it.
 */
public enum Audience {
    EVERYONE("everyone", OptionTexts.AUDIENCE_EVERYONE),
    /** Friends and teammates. */
    FRIENDS_TEAM("friends-team", OptionTexts.AUDIENCE_FRIENDS_TEAM),
    FRIENDS("friends", OptionTexts.AUDIENCE_FRIENDS),
    NOBODY("nobody", OptionTexts.AUDIENCE_NOBODY);

    private final String id;
    private final MessageKey label;

    Audience(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    public MessageKey label() {
        return this.label;
    }

    /** Whether this audience needs the friends system to mean anything. */
    public boolean needsFriends() {
        return this == FRIENDS || this == FRIENDS_TEAM;
    }
}
