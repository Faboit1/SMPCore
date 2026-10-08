package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open team invites, kept in memory only (they last a couple of minutes). A player can hold invites from several
 * teams at once, but only one per team. Expired invites are treated as absent and swept on a timer, so memory stays
 * bounded by the invites of the last few minutes. Thread-safe; times are passed in so tests control the clock.
 */
public final class Invites {

    /** One invite. */
    public record Invite(long team, UUID invitee, UUID inviter, long created, long expires) {
        public boolean expired(long now) {
            return now >= this.expires;
        }

        public long remainingMillis(long now) {
            return Math.max(0, this.expires - now);
        }
    }

    /** What {@link #state} found. */
    public enum State {
        NONE,
        EXPIRED,
        VALID
    }

    private final Map<UUID, Map<Long, Invite>> byInvitee = new ConcurrentHashMap<>();

    /**
     * Records an invite valid for {@code ttlMillis}. Returns false (and changes nothing) when the player already has
     * a valid invite from that team.
     */
    public boolean add(long team, UUID invitee, UUID inviter, long now, long ttlMillis) {
        Invite invite = new Invite(team, invitee, inviter, now, now + Math.max(1, ttlMillis));
        boolean[] added = {false};
        this.byInvitee.compute(invitee, (key, map) -> {
            Map<Long, Invite> invites = map == null ? new ConcurrentHashMap<>() : map;
            Invite existing = invites.get(team);
            if (existing == null || existing.expired(now)) {
                invites.put(team, invite);
                added[0] = true;
            }
            return invites;
        });
        return added[0];
    }

    public State state(UUID invitee, long team, long now) {
        Map<Long, Invite> invites = this.byInvitee.get(invitee);
        Invite invite = invites == null ? null : invites.get(team);
        if (invite == null) {
            return State.NONE;
        }
        return invite.expired(now) ? State.EXPIRED : State.VALID;
    }

    /** The valid invite from {@code team}, if any. */
    public Invite get(UUID invitee, long team, long now) {
        Map<Long, Invite> invites = this.byInvitee.get(invitee);
        Invite invite = invites == null ? null : invites.get(team);
        return invite == null || invite.expired(now) ? null : invite;
    }

    /**
     * Removes and returns the invite from {@code team} if it is still valid; an expired one is removed too and null
     * is returned. Atomic, so one invite can be used (accepted or declined) only once.
     */
    public Invite take(UUID invitee, long team, long now) {
        Invite[] taken = {null};
        this.byInvitee.computeIfPresent(invitee, (key, invites) -> {
            Invite invite = invites.remove(team);
            if (invite != null && !invite.expired(now)) {
                taken[0] = invite;
            }
            return invites.isEmpty() ? null : invites;
        });
        return taken[0];
    }

    /** Valid invites held by a player, oldest first. */
    public List<Invite> pendingFor(UUID invitee, long now) {
        Map<Long, Invite> invites = this.byInvitee.get(invitee);
        if (invites == null) {
            return List.of();
        }
        List<Invite> list = new ArrayList<>();
        for (Invite invite : invites.values()) {
            if (!invite.expired(now)) {
                list.add(invite);
            }
        }
        list.sort(Comparator.comparingLong(Invite::created).thenComparingLong(Invite::team));
        return list;
    }

    /** Number of valid invites a team has out. */
    public int pendingFromTeam(long team, long now) {
        int count = 0;
        for (Map<Long, Invite> invites : this.byInvitee.values()) {
            Invite invite = invites.get(team);
            if (invite != null && !invite.expired(now)) {
                count++;
            }
        }
        return count;
    }

    /** Forgets every invite a player holds (they joined a team). */
    public void clearInvitee(UUID invitee) {
        this.byInvitee.remove(invitee);
    }

    /** Forgets every invite from a team (it was disbanded). */
    public void clearTeam(long team) {
        for (UUID invitee : List.copyOf(this.byInvitee.keySet())) {
            this.byInvitee.computeIfPresent(invitee, (key, invites) -> {
                invites.remove(team);
                return invites.isEmpty() ? null : invites;
            });
        }
    }

    /** Drops expired invites. */
    public void sweep(long now) {
        for (UUID invitee : List.copyOf(this.byInvitee.keySet())) {
            this.byInvitee.computeIfPresent(invitee, (key, invites) -> {
                invites.values().removeIf(invite -> invite.expired(now));
                return invites.isEmpty() ? null : invites;
            });
        }
    }

    /** Number of invites held in memory, valid or not yet swept (for tests and metrics). */
    public int size() {
        int count = 0;
        for (Map<Long, Invite> invites : this.byInvitee.values()) {
            count += invites.size();
        }
        return count;
    }
}
