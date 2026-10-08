package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.core.link.TeamLookup;

/**
 * Every team in memory, indexed by id, member and lowercase name. Teams are immutable snapshots, so reads are
 * lock-free and safe from any thread (async chat, damage events on region threads, placeholders). Writes replace
 * whole snapshots and must be serialized by the caller: {@link TeamService} makes every change under the economy
 * lock, the same lock team creation runs under, so checks and changes are atomic across all teams.
 */
public final class TeamRegistry implements TeamLookup {

    private final Map<Long, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, Long> byMember = new ConcurrentHashMap<>();
    private final Map<String, Long> byName = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ reads (any thread)

    public Optional<Team> get(long id) {
        return Optional.ofNullable(this.teams.get(id));
    }

    /** The team with this name, ignoring case. */
    public Optional<Team> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Long id = this.byName.get(TeamNames.key(name));
        return id == null ? Optional.empty() : get(id);
    }

    /** The player's team. */
    public Optional<Team> of(UUID player) {
        Long id = this.byMember.get(player);
        if (id == null) {
            return Optional.empty();
        }
        Team team = this.teams.get(id);
        return team != null && team.isMember(player) ? Optional.of(team) : Optional.empty();
    }

    public List<Team> all() {
        return new ArrayList<>(this.teams.values());
    }

    public int count() {
        return this.teams.size();
    }

    /** Number of players in a team. */
    public int memberCount() {
        return this.byMember.size();
    }

    /** Names of every team, for suggestions. */
    public List<String> names() {
        List<String> names = new ArrayList<>(this.teams.size());
        for (Team team : this.teams.values()) {
            names.add(team.name());
        }
        return names;
    }

    /**
     * Checks that the three indexes agree: every member points at a team that contains them, every team member is
     * indexed, every name points at its team. Returns a description of the first problem, or null.
     */
    public String verify() {
        for (Map.Entry<UUID, Long> entry : this.byMember.entrySet()) {
            Team team = this.teams.get(entry.getValue());
            if (team == null || !team.isMember(entry.getKey())) {
                return "player " + entry.getKey() + " points at team " + entry.getValue() + " which does not list them";
            }
        }
        for (Team team : this.teams.values()) {
            for (UUID member : team.memberIds()) {
                Long indexed = this.byMember.get(member);
                if (indexed == null || indexed != team.id()) {
                    return "member " + member + " of team " + team.id() + " is not indexed to it";
                }
            }
            Long named = this.byName.get(TeamNames.key(team.name()));
            if (named == null || named != team.id()) {
                return "team " + team.id() + " is not indexed by its name";
            }
        }
        if (this.byName.size() != this.teams.size()) {
            return this.byName.size() + " names for " + this.teams.size() + " teams";
        }
        return null;
    }

    // ------------------------------------------------------------------ writes (serialized by the caller)

    /** Adds or replaces a team snapshot and updates the indexes. */
    void put(Team team) {
        Team old = this.teams.put(team.id(), team);
        if (old != null) {
            for (UUID member : old.memberIds()) {
                if (!team.isMember(member)) {
                    this.byMember.remove(member, team.id());
                }
            }
            if (!TeamNames.key(old.name()).equals(TeamNames.key(team.name()))) {
                this.byName.remove(TeamNames.key(old.name()), team.id());
            }
        }
        for (UUID member : team.memberIds()) {
            this.byMember.put(member, team.id());
        }
        this.byName.put(TeamNames.key(team.name()), team.id());
    }

    /** Removes a team and its index entries; returns the removed snapshot. */
    Team remove(long id) {
        Team old = this.teams.remove(id);
        if (old != null) {
            for (UUID member : old.memberIds()) {
                this.byMember.remove(member, id);
            }
            this.byName.remove(TeamNames.key(old.name()), id);
        }
        return old;
    }

    /** Replaces everything (startup). */
    void load(Collection<Team> loaded) {
        this.teams.clear();
        this.byMember.clear();
        this.byName.clear();
        for (Team team : loaded) {
            put(team);
        }
    }

    // ------------------------------------------------------------------ TeamLookup

    @Override
    public Optional<Long> team(UUID player) {
        return of(player).map(Team::id);
    }

    @Override
    public Optional<String> teamName(UUID player) {
        return of(player).map(Team::name);
    }

    @Override
    public boolean friendlyFire(long team) {
        Team snapshot = this.teams.get(team);
        return snapshot == null || snapshot.friendlyFire();
    }

    @Override
    public Set<UUID> members(long team) {
        Team snapshot = this.teams.get(team);
        return snapshot == null ? Set.of() : snapshot.memberIds();
    }

    @Override
    public boolean canInvite(UUID player) {
        return of(player).map(team -> TeamRules.invite(team.role(player)) == null).orElse(false);
    }
}
