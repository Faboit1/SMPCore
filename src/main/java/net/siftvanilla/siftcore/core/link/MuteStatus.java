package net.siftvanilla.siftcore.core.link;

import java.util.Optional;
import java.util.UUID;

/** Chat mutes. Implemented by the staff feature; chat and private messages consult it. Thread-safe and cheap. */
public interface MuteStatus {

    MuteStatus NONE = player -> Optional.empty();

    /** The player's active mute, or empty when they may talk. Called from the async chat thread. */
    Optional<Mute> mute(UUID player);

    /**
     * An active mute.
     *
     * @param reason   the reason shown to the player (plain text, may be empty)
     * @param until    when it ends in epoch milliseconds, or {@code Long.MAX_VALUE} for a permanent mute
     * @param staff    who muted the player (a name, or "Console")
     */
    record Mute(String reason, long until, String staff) {

        public boolean permanent() {
            return this.until == Long.MAX_VALUE;
        }
    }
}
