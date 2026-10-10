package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player is put in combat (or has their combat timer started again) because of a hit between two
 * players. Each hit fires it once for the player who was hit and once for the attacker. Cancelling it leaves that
 * player's combat state unchanged; the hit itself still happens and still counts for kill credit.
 * <p>
 * Fired on the thread of the player who was hit. The attacker may be owned by another region (a shot from far
 * away), so only read from {@link #opponent()} and schedule work on its own thread.
 */
public final class CombatTagEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Player opponent;
    private final boolean attacker;
    private final boolean refresh;

    public CombatTagEvent(Player player, Player opponent, boolean attacker, boolean refresh) {
        this.player = player;
        this.opponent = opponent;
        this.attacker = attacker;
        this.refresh = refresh;
    }

    /** The player who is about to be tagged. */
    public Player player() {
        return this.player;
    }

    /** The other player of the hit. */
    public Player opponent() {
        return this.opponent;
    }

    /** True when {@link #player()} dealt the hit, false when they took it. */
    public boolean attacker() {
        return this.attacker;
    }

    /** True when the player is already in combat and this only starts the timer again. */
    public boolean refresh() {
        return this.refresh;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
