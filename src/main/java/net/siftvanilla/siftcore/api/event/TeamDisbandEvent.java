package net.siftvanilla.siftcore.api.event;

import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired before a team is disbanded. Cancelling keeps the team. Nothing is refunded when it goes ahead. */
public final class TeamDisbandEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long team;
    private final String teamName;
    private final UUID owner;
    private final Set<UUID> members;
    private final UUID actor;

    public TeamDisbandEvent(long team, String teamName, UUID owner, Set<UUID> members, UUID actor) {
        this.team = team;
        this.teamName = teamName;
        this.owner = owner;
        this.members = Set.copyOf(members);
        this.actor = actor;
    }

    public long team() {
        return this.team;
    }

    public String teamName() {
        return this.teamName;
    }

    public UUID owner() {
        return this.owner;
    }

    /** Every member at the time, the owner included. */
    public Set<UUID> members() {
        return this.members;
    }

    /** The owner when they disband it themselves, or null for staff and the console. */
    public UUID actor() {
        return this.actor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
