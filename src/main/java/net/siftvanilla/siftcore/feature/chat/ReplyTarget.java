package net.siftvanilla.siftcore.feature.chat;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Who {@code /r} answers: the values of the {@code reply-target} setting. */
public enum ReplyTarget {
    /** Whoever the player last talked with, in either direction (the classic {@code /r}). */
    LAST_CONVERSATION("last-conversation", ChatMessages.REPLY_LAST_CONVERSATION),
    /** Whoever last wrote to the player, even after the player wrote to someone else. */
    LAST_RECEIVED("last-received", ChatMessages.REPLY_LAST_RECEIVED);

    private final String id;
    private final MessageKey label;

    ReplyTarget(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    MessageKey label() {
        return this.label;
    }
}
