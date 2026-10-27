package net.siftvanilla.siftcore.feature.friends;

import java.util.Objects;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;

/**
 * How one player setting reads from a stored row, resolved the way {@link PlayerSettings} does it: a value the server
 * forces (its lock, or for a setting the server hides, its default) wins, else the stored value (read leniently, old
 * values included), else the server's default. Used where the value has to come straight from the table: inside the
 * friend request unit, which reads the target's {@code friends-requests} row in the same transaction, and for reading
 * the {@code seen-privacy} of many offline friends at once. Pure and immutable; take a fresh one for each read so a
 * {@code /sift reload} of the server's settings applies.
 *
 * @param forced   the value everyone reads whatever is stored, or null
 * @param fallback the value without a (readable) row: the server's default, else the code default
 * @param decode   a stored text to a value, null when it is not one
 */
record StoredSetting<T>(T forced, T fallback, Function<String, T> decode) {

    StoredSetting {
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(decode);
    }

    /** The rule now in force for {@code setting} (the server's locks, hidden list and defaults right now). */
    static <T> StoredSetting<T> of(PlayerSettings settings, PlayerSetting<T> setting) {
        T value = settings.defaultValue(setting);
        boolean forced = settings.locked(setting) || settings.hidden(setting);
        return new StoredSetting<>(forced ? value : null, value, setting::decodeOrNull);
    }

    /** The code default of a setting, nothing forced (no settings registry, as in unit tests). */
    static <T> StoredSetting<T> codeDefault(PlayerSetting<T> setting) {
        return new StoredSetting<>(null, setting.defaultValue(), setting::decodeOrNull);
    }

    /** The value a stored row (null for no row) reads as. */
    T resolve(String stored) {
        if (this.forced != null) {
            return this.forced;
        }
        T value = stored == null ? null : this.decode.apply(stored);
        return value != null ? value : this.fallback;
    }
}
