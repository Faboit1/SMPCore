package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player's setting changes, only when the value really changes, on the thread that changes it (the
 * player's thread for the dialog and commands, the global thread for the console, any thread for the API).
 * <p>
 * Cancelling is honoured only for changes the player or a plugin asked for ({@link Cause#DIALOG},
 * {@link Cause#COMMAND}, {@link Cause#API}; see {@link #cancellable()}): the player is told the setting couldn't be
 * changed. {@link Cause#FEATURE}, {@link Cause#ADMIN} and {@link Cause#RESET} changes are only reported, because the
 * feature or staff tool already acted on them. Listeners that react to committed changes listen at
 * {@code EventPriority.MONITOR} and skip cancelled cancellable events.
 */
public final class SettingChangeEvent extends SiftCancellableEvent {

    /** What made the change. */
    public enum Cause {
        /** The player saved the settings dialog. */
        DIALOG,
        /** The player used a command. */
        COMMAND,
        /** A SiftCore feature changed it on its own (for example {@code /msgtoggle}). */
        FEATURE,
        /** Staff changed it for the player. */
        ADMIN,
        /** A plugin changed it through the API. */
        API,
        /** It went back to the default because it was reset. */
        RESET
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final Player player;
    private final String setting;
    private final String category;
    private final String oldValue;
    private final String newValue;
    private final Cause cause;
    private final String actor;

    public SettingChangeEvent(UUID playerId, Player player, String setting, String category, String oldValue, String newValue,
                              Cause cause, String actor) {
        this.playerId = playerId;
        this.player = player;
        this.setting = setting;
        this.category = category;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.cause = cause;
        this.actor = actor;
    }

    /** The player whose setting changes. */
    public UUID playerId() {
        return this.playerId;
    }

    /** The player, or null when they are offline. */
    public Player player() {
        return this.player;
    }

    /** The setting id, e.g. {@code sound-volume}. */
    public String setting() {
        return this.setting;
    }

    /** The id of the setting's category, e.g. {@code sound}. */
    public String category() {
        return this.category;
    }

    /** The value before, as stored ({@code true}, an option id, a number), or null when the player is offline. */
    public String oldValue() {
        return this.oldValue;
    }

    /** The value after, as stored. */
    public String newValue() {
        return this.newValue;
    }

    public Cause cause() {
        return this.cause;
    }

    /** Who changed it: the player's or staff member's name, a plugin name, or {@code feature}. */
    public String actor() {
        return this.actor;
    }

    /** Whether cancelling this event stops the change. */
    public boolean cancellable() {
        return this.cause == Cause.DIALOG || this.cause == Cause.COMMAND || this.cause == Cause.API;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
