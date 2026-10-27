package net.siftvanilla.siftcore.core.player;

import java.util.Objects;

/**
 * Why a setting changes and who changed it, carried into {@code SettingChangeEvent}.
 *
 * @param cause what made the change
 * @param actor the player or staff member's name, the plugin, or "feature" for code
 */
public record Change(Cause cause, String actor) {

    /** What made a change. */
    public enum Cause {
        /** The player saved the settings dialog. */
        DIALOG,
        /**
         * The player used a settings command such as {@code /settings}. Feature commands that flip one switch with
         * {@code set(uuid, toggle, on)} ({@code /msgtoggle}, {@code /scoreboard}) report {@link #FEATURE}.
         */
        COMMAND,
        /** A feature changed it on its own. */
        FEATURE,
        /** Staff changed it for the player. */
        ADMIN,
        /** Another plugin, through the public API. */
        API,
        /** It went back to the default because the player or staff reset it (every reset reports this cause). */
        RESET;

        /**
         * Whether a listener may stop a change with this cause. Feature, staff and reset changes are only reported:
         * features print their own result and staff tools must not silently do nothing.
         */
        public boolean cancellable() {
            return this == DIALOG || this == COMMAND || this == API;
        }
    }

    public Change {
        Objects.requireNonNull(cause);
        actor = actor == null ? "" : actor;
    }

    /** A change made by a feature's own code (the classic {@code set(uuid, toggle, on)}). */
    public static Change feature() {
        return new Change(Cause.FEATURE, "feature");
    }

    public static Change dialog(String player) {
        return new Change(Cause.DIALOG, player);
    }

    public static Change command(String player) {
        return new Change(Cause.COMMAND, player);
    }

    public static Change admin(String staff) {
        return new Change(Cause.ADMIN, staff);
    }

    public static Change api(String plugin) {
        return new Change(Cause.API, plugin);
    }

    public static Change reset(String actor) {
        return new Change(Cause.RESET, actor);
    }
}
