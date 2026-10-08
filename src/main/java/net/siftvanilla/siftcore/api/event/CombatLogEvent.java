package net.siftvanilla.siftcore.api.event;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player leaves the server while in combat, before anything happens to them. Cancelling it lets them
 * leave without punishment and without the announcement; listeners may also change the punishment.
 * Fired on the leaving player's thread, from the quit event.
 */
public final class CombatLogEvent extends SiftCancellableEvent {

    /** What happens to the player. */
    public enum Punishment {
        /** They die where they stand: their items drop and the last attacker gets the kill. */
        KILL,
        /** Nothing, apart from the announcement. */
        NONE
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID lastAttacker;
    private final Duration remaining;
    private Punishment punishment;

    public CombatLogEvent(Player player, UUID lastAttacker, Duration remaining, Punishment punishment) {
        this.player = player;
        this.lastAttacker = lastAttacker;
        this.remaining = remaining;
        this.punishment = Objects.requireNonNull(punishment);
    }

    /** The player who is leaving. */
    public Player player() {
        return this.player;
    }

    /** The last player who hit them while the tag was running, or null if they only attacked. */
    public UUID lastAttacker() {
        return this.lastAttacker;
    }

    /** How much of the combat timer was left. */
    public Duration remaining() {
        return this.remaining;
    }

    public Punishment punishment() {
        return this.punishment;
    }

    public void punishment(Punishment punishment) {
        this.punishment = Objects.requireNonNull(punishment);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
