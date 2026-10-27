package net.siftvanilla.siftcore.core.player;

import java.util.Optional;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A per-player setting a feature contributes: an on/off {@link Toggle}, one option out of a list ({@link Choice}) or a
 * whole number in a range ({@link NumberSetting}). Values are stored as short strings in the {@code settings} table
 * ({@link #encode}/{@link #decode}); reading never touches the database (see {@link PlayerSettings}).
 * <p>
 * Named {@code PlayerSetting} on purpose: {@code core.config.Setting} is the config holder every feature imports.
 *
 * @param <T> the value type: {@code Boolean}, the option value type, or {@code Long}
 */
public sealed interface PlayerSetting<T> permits Toggle, Choice, NumberSetting {

    /** The kind of input a setting is shown with. */
    enum Kind {
        TOGGLE,
        CHOICE,
        NUMBER
    }

    /** Setting ids: lowercase letters, digits, {@code -} and {@code _}, at most 32 characters (the column width). */
    Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Encoded values fit the {@code value} column. */
    int MAX_ENCODED = 64;

    /** Stable id stored in the database, e.g. {@code sound-volume}. */
    String id();

    /** Short label (a {@code ui} key without placeholders). */
    MessageKey label();

    /** One line saying what the setting does (a {@code ui} key without placeholders). */
    MessageKey description();

    /** Permission needed to see and use it, or null for everyone. */
    String permission();

    /** The value for players who never changed it (before server config defaults). */
    T defaultValue();

    Kind kind();

    /** The stored form of a value. */
    String encode(T value);

    /**
     * Reads a stored or typed value leniently (surrounding spaces and letter case are ignored). Empty when the text
     * is not a value of this setting, so the caller falls back to the default.
     */
    Optional<T> decode(String stored);

    /** Whether this setting can hold the value (an option of a choice, in range and on step for a number). */
    boolean valid(T value);

    /** A value as plain text for players ("on", "Everyone", "30 %"). */
    String display(Lang lang, T value);

    /** The value if it has this setting's type, otherwise null (used where values travel untyped). */
    T cast(Object value);

    /** The decoded value of a stored string, or null when it is not a value of this setting. */
    default T decodeOrNull(String stored) {
        return decode(stored).orElse(null);
    }

    /** Whether two values encode to the same stored string. */
    default boolean same(T a, T b) {
        return a == null ? b == null : b != null && encode(a).equals(encode(b));
    }
}
