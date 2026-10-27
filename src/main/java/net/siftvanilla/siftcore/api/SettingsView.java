package net.siftvanilla.siftcore.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Players' SiftCore settings: what settings exist (with their groups, values and defaults), each player's values,
 * and changing or resetting them. Values travel in their stored form: {@code true}/{@code false} for a switch, the
 * option id for a choice, the number for a slider.
 * <p>
 * Reads are memory reads for online players (players who are not online read the defaults; {@link #stored} reads the
 * database). Changes are written at once and fire {@code SettingChangeEvent} with the cause {@code API}, which a
 * listener may cancel; changes and resets are written to SiftCore's audit log ({@code settings.set} and
 * {@code settings.reset}, actor {@code api:<actor>}, or {@code api} without one). Everything here is thread-safe and non-blocking. Get it with
 * {@link SiftCoreApi#settings()}.
 */
public interface SettingsView {

    /** How a setting is shown and what values it takes. */
    enum Type {
        /** On or off: {@code true} or {@code false}. */
        TOGGLE,
        /** One option id out of {@link SettingInfo#options()}. */
        CHOICE,
        /** A whole number from {@link SettingInfo#min()} to {@link SettingInfo#max()} in steps of {@link SettingInfo#step()}. */
        NUMBER
    }

    /** What happened to a change. */
    enum Result {
        /** Stored (or the stored value removed because it is the default again). */
        CHANGED,
        /** The player already had that value; nothing was written and no event fired. */
        UNCHANGED,
        /** Not a value of the setting. */
        INVALID,
        /** The server hides the setting ({@code features/settings.yml}): its value is the server's. */
        NOT_ALLOWED,
        /** The server locked the setting to a value. */
        LOCKED,
        /** A {@code SettingChangeEvent} listener cancelled it. */
        CANCELLED,
        /** No setting has that id. */
        UNKNOWN
    }

    /**
     * A group of settings in the settings dialog.
     *
     * @param id          the group id, e.g. {@code sound}
     * @param order       its place in the dialog (lower first; the server's {@code categories} order when it set one)
     * @param label       its name as plain text
     * @param description what is in it, as plain text
     * @param icon        its {@code icons.yml} name (the server's when it set one), or null
     */
    record CategoryInfo(String id, int order, String label, String description, String icon) {
    }

    /**
     * A setting.
     *
     * @param id           the setting id, e.g. {@code sound-volume}
     * @param type         its kind
     * @param category     its group id
     * @param label        its name as plain text
     * @param description  what it does, as plain text
     * @param defaultValue what players who never changed it read now (the server's lock or default, else the built-in
     *                     default), stored form
     * @param options      a choice's option ids in order (empty for the other kinds)
     * @param min          a number's minimum (0 for the other kinds)
     * @param max          a number's maximum (0 for the other kinds)
     * @param step         a number's step (0 for the other kinds)
     * @param unit         a number's unit as plain text, e.g. {@code %} (empty when none)
     * @param permission   the permission a player needs to see and change it, or null
     * @param locked       whether the server locked it to {@code defaultValue}
     * @param hidden       whether the server hides it (everyone reads {@code defaultValue})
     */
    record SettingInfo(String id, Type type, String category, String label, String description, String defaultValue,
                       List<String> options, long min, long max, long step, String unit, String permission, boolean locked,
                       boolean hidden) {
        public SettingInfo {
            options = List.copyOf(options);
        }
    }

    /** Every group that holds settings, in dialog order (the server's order, groups it did not move in built-in order). */
    List<CategoryInfo> categories();

    /** Every setting, group by group in dialog order. */
    List<SettingInfo> settings();

    /** One setting by id. */
    Optional<SettingInfo> setting(String id);

    /**
     * A player's value of a setting in stored form, or null when no setting has that id. Online players read their
     * own value; players who are not online read the default (use {@link #stored} for their saved values).
     */
    String value(UUID player, String id);

    /** The values a player saved (setting id to stored value), read from the database; works for offline players. */
    CompletableFuture<Map<String, String>> stored(UUID player);

    /**
     * Changes a player's setting (online or not) to a value in stored form or a choice's option label. No permission
     * is checked. Fires {@code SettingChangeEvent} with the cause {@code API} and {@code actor} as its actor.
     */
    Result set(UUID player, String id, String value, String actor);

    /** {@link #set(UUID, String, String, String)} with the actor {@code api}. */
    default Result set(UUID player, String id, String value) {
        return set(player, id, value, "api");
    }

    /**
     * Puts a player's setting back to the default (removes the saved value), reported to {@code SettingChangeEvent}
     * listeners with the cause {@code RESET}. For an online player the result says whether the value changed; for a
     * player who is not online the saved value is removed and {@link Result#CHANGED} is reported either way.
     */
    Result reset(UUID player, String id);
}
