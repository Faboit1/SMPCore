package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a private message ({@code /msg}, {@code /r}) is delivered, after every built-in check (mutes, ignore
 * lists, the receiver's setting, anti-spam and the word filter) passed. Cancelling stops the message; the sender is
 * told they can't message that player. The text is the final text the receiver would read.
 */
public final class PrivateMessageEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sender;
    private final UUID receiver;
    private final String message;

    /**
     * @param sender   the sending player, or null for the console
     * @param receiver the receiving player, or null for the console
     */
    public PrivateMessageEvent(UUID sender, UUID receiver, String message) {
        this.sender = sender;
        this.receiver = receiver;
        this.message = message;
    }

    /** The sending player, or null when the console sends. */
    public UUID sender() {
        return this.sender;
    }

    /** The receiving player, or null when the message goes to the console. */
    public UUID receiver() {
        return this.receiver;
    }

    /** The message as the receiver would read it (plain text). */
    public String message() {
        return this.message;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
