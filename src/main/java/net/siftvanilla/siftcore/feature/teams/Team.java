package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable snapshot of one team. Every change builds a new snapshot, so readers on any thread (chat, damage
 * events, placeholders) always see a consistent team without locking. The constructor enforces the invariants:
 * the owner is a member with the owner role, and nobody else has that role.
 *
 * @param id             stable id
 * @param name           display name (letters, digits and underscores)
 * @param owner          the owner's UUID
 * @param created        creation time (epoch millis)
 * @param friendlyFire   whether members can hurt each other
 * @param home           the team home, or null
 * @param members        every member including the owner
 * @param ownerRankLimit the member limit granted by the owner's rank when last seen (0 = none known)
 */
public record Team(long id, String name, UUID owner, long created, boolean friendlyFire, TeamHome home,
                   Map<UUID, TeamMember> members, int ownerRankLimit) {

    private static final Comparator<TeamMember> ORDER = Comparator
        .comparing((TeamMember m) -> m.role().ordinal(), Comparator.reverseOrder())
        .thenComparingLong(TeamMember::joined)
        .thenComparing(m -> m.uuid().toString());

    public Team {
        Objects.requireNonNull(name);
        Objects.requireNonNull(owner);
        members = Map.copyOf(members);
        TeamMember ownerMember = members.get(owner);
        if (ownerMember == null || ownerMember.role() != TeamRole.OWNER) {
            throw new IllegalArgumentException("Team " + id + ": the owner must be a member with the owner role");
        }
        for (TeamMember member : members.values()) {
            if (!member.uuid().equals(owner) && member.role() == TeamRole.OWNER) {
                throw new IllegalArgumentException("Team " + id + " has more than one owner");
            }
        }
        ownerRankLimit = Math.max(0, ownerRankLimit);
    }

    /** A new team with only its owner. */
    public static Team create(long id, String name, UUID owner, long now, boolean friendlyFire) {
        return new Team(id, name, owner, now, friendlyFire, null,
            Map.of(owner, new TeamMember(owner, TeamRole.OWNER, now)), 0);
    }

    /** The player's role, or null when they are not a member. */
    public TeamRole role(UUID player) {
        TeamMember member = this.members.get(player);
        return member == null ? null : member.role();
    }

    public boolean isMember(UUID player) {
        return this.members.containsKey(player);
    }

    public int size() {
        return this.members.size();
    }

    public Set<UUID> memberIds() {
        return this.members.keySet();
    }

    /** Members ordered owner first, then admins, then members, each by join time. */
    public List<TeamMember> sortedMembers() {
        List<TeamMember> list = new ArrayList<>(this.members.values());
        list.sort(ORDER);
        return list;
    }

    /** Adds a member or admin (never an owner; use {@link #withOwner}). */
    public Team withMember(TeamMember member) {
        if (member.role() == TeamRole.OWNER) {
            throw new IllegalArgumentException("Use withOwner to change the owner");
        }
        Map<UUID, TeamMember> next = new HashMap<>(this.members);
        next.put(member.uuid(), member);
        return copy(this.name, this.owner, this.friendlyFire, this.home, next, this.ownerRankLimit);
    }

    /** Removes a member (never the owner). */
    public Team withoutMember(UUID player) {
        if (player.equals(this.owner)) {
            throw new IllegalArgumentException("The owner cannot be removed");
        }
        Map<UUID, TeamMember> next = new HashMap<>(this.members);
        next.remove(player);
        return copy(this.name, this.owner, this.friendlyFire, this.home, next, this.ownerRankLimit);
    }

    /** Changes a non-owner's role to member or admin. */
    public Team withRole(UUID player, TeamRole role) {
        TeamMember member = this.members.get(player);
        if (member == null || member.role() == TeamRole.OWNER || role == TeamRole.OWNER) {
            throw new IllegalArgumentException("Only non-owners can be promoted or demoted");
        }
        Map<UUID, TeamMember> next = new HashMap<>(this.members);
        next.put(player, member.withRole(role));
        return copy(this.name, this.owner, this.friendlyFire, this.home, next, this.ownerRankLimit);
    }

    /**
     * Hands the team to another member. The old owner becomes an admin; the rank limit is cleared until the new
     * owner's rank is known.
     */
    public Team withOwner(UUID newOwner, int newOwnerRankLimit) {
        TeamMember next = this.members.get(newOwner);
        if (next == null || newOwner.equals(this.owner)) {
            throw new IllegalArgumentException("The new owner must be another member");
        }
        Map<UUID, TeamMember> members = new HashMap<>(this.members);
        members.put(this.owner, this.members.get(this.owner).withRole(TeamRole.ADMIN));
        members.put(newOwner, next.withRole(TeamRole.OWNER));
        return copy(this.name, newOwner, this.friendlyFire, this.home, members, newOwnerRankLimit);
    }

    public Team withHome(TeamHome newHome) {
        return copy(this.name, this.owner, this.friendlyFire, newHome, this.members, this.ownerRankLimit);
    }

    public Team withFriendlyFire(boolean on) {
        return copy(this.name, this.owner, on, this.home, this.members, this.ownerRankLimit);
    }

    public Team withName(String newName) {
        return copy(newName, this.owner, this.friendlyFire, this.home, this.members, this.ownerRankLimit);
    }

    public Team withOwnerRankLimit(int limit) {
        return copy(this.name, this.owner, this.friendlyFire, this.home, this.members, limit);
    }

    private Team copy(String newName, UUID newOwner, boolean ff, TeamHome newHome, Map<UUID, TeamMember> newMembers, int limit) {
        return new Team(this.id, newName, newOwner, this.created, ff, newHome, newMembers, limit);
    }
}
