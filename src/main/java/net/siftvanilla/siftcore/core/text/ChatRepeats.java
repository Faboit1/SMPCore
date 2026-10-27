package net.siftvanilla.siftcore.core.text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an error that a player's feedback channel moves into chat from piling up there. Some refusals repeat as long
 * as the player keeps trying (each click on a protected block, each swing while frozen): above the hotbar each line
 * just replaces the last, in chat every one would stay. So while a player keeps triggering the same error, chat shows
 * it once, and it shows again after they paused for {@link #WINDOW_MILLIS}. Thread-safe.
 */
final class ChatRepeats {

    /** How long a player must stop triggering an error before chat shows it again. */
    static final long WINDOW_MILLIS = 2_000;
    /** Past this many remembered players, entries older than the window are dropped. */
    private static final int PRUNE_AT = 256;

    /** The last error a player triggered and when. */
    private record Last(String path, long at) {
    }

    private final long window;
    private final Map<UUID, Last> last = new ConcurrentHashMap<>();

    ChatRepeats() {
        this(WINDOW_MILLIS);
    }

    ChatRepeats(long window) {
        this.window = window;
    }

    /**
     * Whether an error line may go to the player's chat now. It may unless the player's previous error had the same key
     * less than the window ago; every attempt counts, so a burst of the same refusal shows once however long it lasts.
     */
    boolean allow(UUID player, String path, long now) {
        Last previous = this.last.put(player, new Last(path, now));
        if (this.last.size() > PRUNE_AT) {
            this.last.values().removeIf(entry -> now - entry.at() >= this.window);
        }
        return previous == null || !previous.path().equals(path) || now - previous.at() >= this.window;
    }

    /** How many players are remembered (tests). */
    int size() {
        return this.last.size();
    }
}
