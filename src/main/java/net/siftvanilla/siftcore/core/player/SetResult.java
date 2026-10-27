package net.siftvanilla.siftcore.core.player;

/** What happened to a requested setting change. */
public enum SetResult {
    /** Stored (or the row deleted because the value is the default again). */
    CHANGED,
    /** The value already was that; nothing was written and no event fired. */
    UNCHANGED,
    /** Not a value of the setting (an unknown option, off the number's range or step). */
    INVALID,
    /** The player may not use the setting or that option, or the server does not offer it now. */
    NOT_ALLOWED,
    /** The server locked the setting to a value in {@code features/settings.yml}. */
    LOCKED,
    /** A {@code SettingChangeEvent} listener cancelled it. */
    CANCELLED,
    /** No setting has that id. */
    UNKNOWN;

    /** Whether the value now is what was asked for. */
    public boolean succeeded() {
        return this == CHANGED || this == UNCHANGED;
    }
}
