package net.siftvanilla.siftcore.core.link;

import java.util.Objects;
import java.util.UUID;
import net.siftvanilla.siftcore.core.player.options.Audience;

/**
 * How two players are related (friends, teammates, ignored), for every "who can" setting. The friends, teams and chat
 * features are built after many of the features that ask, so the lookups are bound late by the composition root;
 * until then (and on servers without a feature) the relation reads as absent. Thread-safe and cheap.
 */
public final class Relations {

    private volatile FriendLookup friends = FriendLookup.NONE;
    private volatile TeamLookup teams = TeamLookup.NONE;
    private volatile IgnoreLookup ignores = IgnoreLookup.NONE;

    /** Binds the features' lookups (the composition root does this once every feature is built). */
    public void bind(FriendLookup friends, TeamLookup teams, IgnoreLookup ignores) {
        this.friends = Objects.requireNonNull(friends);
        this.teams = Objects.requireNonNull(teams);
        this.ignores = Objects.requireNonNull(ignores);
    }

    public FriendLookup friends() {
        return this.friends;
    }

    public TeamLookup teams() {
        return this.teams;
    }

    public IgnoreLookup ignores() {
        return this.ignores;
    }

    /** Whether the server has a friends system (otherwise friend-based options are not offered). */
    public boolean friendsAvailable() {
        return this.friends != FriendLookup.NONE;
    }

    /** Whether friends can be marked as favourites on this server. */
    public boolean favouritesAvailable() {
        return friendsAvailable() && this.friends.favouritesEnabled();
    }

    public boolean areFriends(UUID a, UUID b) {
        return this.friends.friends(a, b);
    }

    public boolean sameTeam(UUID a, UUID b) {
        return this.teams.sameTeam(a, b);
    }

    /** Whether {@code player} ignores {@code other}. */
    public boolean ignores(UUID player, UUID other) {
        return this.ignores.ignores(player, other);
    }

    /**
     * Whether {@code other} belongs to the audience {@code owner} chose (a player always belongs to their own).
     * Ignoring is not part of this: features that refuse ignored players check {@link #ignores} as well.
     */
    public boolean allows(Audience audience, UUID owner, UUID other) {
        if (owner.equals(other)) {
            return true;
        }
        return switch (audience) {
            case EVERYONE -> true;
            case NOBODY -> false;
            case FRIENDS -> areFriends(owner, other);
            case FRIENDS_TEAM -> areFriends(owner, other) || sameTeam(owner, other);
        };
    }

    /** The audience rule on its own, for tests and callers that already know the relations. */
    public static boolean allows(Audience audience, boolean friends, boolean sameTeam) {
        return switch (audience) {
            case EVERYONE -> true;
            case NOBODY -> false;
            case FRIENDS -> friends;
            case FRIENDS_TEAM -> friends || sameTeam;
        };
    }
}
