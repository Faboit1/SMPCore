package net.siftvanilla.siftcore.feature.teams;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.TeamJoinEvent;
import net.siftvanilla.siftcore.api.event.TeamLeaveEvent;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * Every team change. Each operation validates for a precise message, asks other plugins (the cancellable events),
 * then re-validates and changes memory under the economy lock and queues the matching database write while still
 * holding it. Team creation is one ledger transaction (charge, checks, the in-memory team and the insert commit
 * together); everything else is not economic and is a plain ordered write. Because every change, including
 * creation, runs under the same lock, concurrent clicks, commands from several players and console commands can
 * never interleave half-way: a second attempt simply sees the first one's result.
 * <p>
 * No Bukkit here (players are UUIDs), so all of it runs in unit tests against a real ledger and database.
 */
public final class TeamService {

    /**
     * The result of an operation.
     *
     * @param problem why it was refused, or null on success
     * @param team    the team after the change (on success), or the team concerned (on failure, may be null)
     * @param subject the other player involved: the inviter for joins and declines, the target otherwise (may be null)
     * @param stored  completes when the change is committed to the database; fails if it could not be stored
     */
    public record Outcome(TeamProblem problem, Team team, UUID subject, CompletableFuture<Void> stored) {

        public boolean ok() {
            return this.problem == null;
        }

        static Outcome ok(Team team, UUID subject, CompletableFuture<Void> stored) {
            return new Outcome(null, team, subject, stored);
        }

        static Outcome fail(TeamProblem problem) {
            return new Outcome(problem, null, null, CompletableFuture.completedFuture(null));
        }

        static Outcome fail(TeamProblem problem, Team team) {
            return new Outcome(problem, team, null, CompletableFuture.completedFuture(null));
        }
    }

    /** The ledger kind of the money destroyed when a team is created. */
    public static final String CREATE_KIND = "team_create";

    private final Ledger ledger;
    private final TeamRegistry registry;
    private final TeamStore store;
    private final Invites invites;
    private final TeamEventGate events;
    private final Supplier<TeamsSettings> settings;
    private final LongSupplier ids;
    private final LongSupplier clock;
    private final Logger logger;

    public TeamService(Ledger ledger, TeamRegistry registry, TeamStore store, Invites invites, TeamEventGate events,
                       Supplier<TeamsSettings> settings, LongSupplier ids, LongSupplier clock, Logger logger) {
        this.ledger = ledger;
        this.registry = registry;
        this.store = store;
        this.invites = invites;
        this.events = events;
        this.settings = settings;
        this.ids = ids;
        this.clock = clock;
        this.logger = logger;
    }

    public TeamRegistry registry() {
        return this.registry;
    }

    public Invites invites() {
        return this.invites;
    }

    public long now() {
        return this.clock.getAsLong();
    }

    /** The member limit of a team right now. */
    public int memberLimit(Team team) {
        return TeamRules.memberLimit(team.ownerRankLimit(), this.settings.get().defaultMemberLimit());
    }

    /** Checks a name's shape, the block list and uniqueness ({@code exceptTeam} may keep its own name). */
    public TeamProblem checkName(String name, long exceptTeam) {
        TeamsSettings s = this.settings.get();
        TeamProblem shape = TeamNames.validate(name, s.nameMinLength(), s.nameMaxLength(), s.blockedWords());
        if (shape != null) {
            return shape;
        }
        Optional<Team> other = this.registry.byName(name);
        return other.isPresent() && other.get().id() != exceptTeam ? TeamProblem.NAME_TAKEN : null;
    }

    // ------------------------------------------------------------------ create

    /** Everything that would refuse a creation, for an early message before asking to confirm. */
    public TeamProblem checkCreate(UUID player, String name) {
        if (this.registry.of(player).isPresent()) {
            return TeamProblem.ALREADY_IN_TEAM;
        }
        TeamProblem name1 = checkName(name, -1);
        if (name1 != null) {
            return name1;
        }
        long cost = this.settings.get().createCost();
        if (cost > 0) {
            if (!this.ledger.available()) {
                return TeamProblem.ECONOMY_UNAVAILABLE;
            }
            if (this.ledger.balance(player, Currency.MONEY) < cost) {
                return TeamProblem.NOT_ENOUGH_MONEY;
            }
        }
        return null;
    }

    /**
     * Creates a team owned by {@code player} and charges the configured cost, as one ledger transaction: the charge,
     * the "not in a team" and "name free" checks, the in-memory team and the insert all happen together or not at
     * all. {@link Outcome#stored()} is the transaction's commit.
     *
     * @param expectedCost the cost the player was shown and agreed to; if the configured cost is different now (a
     *                     reload changed it), nothing happens and the outcome is {@link TeamProblem#COST_CHANGED}
     */
    public Outcome create(UUID player, String name, long expectedCost) {
        TeamProblem problem = checkCreate(player, name);
        if (problem != null) {
            return Outcome.fail(problem);
        }
        long cost = this.settings.get().createCost();
        if (cost != expectedCost) {
            return Outcome.fail(TeamProblem.COST_CHANGED);
        }
        if (!this.events.create(player, name, cost)) {
            return Outcome.fail(TeamProblem.CANCELLED);
        }
        long id = this.ids.getAsLong();
        Team team = Team.create(id, name, player, now(), this.settings.get().friendlyFireDefault());
        LedgerTx.Builder tx = LedgerTx.builder().actor(player).note("team " + name);
        if (cost > 0) {
            tx.sink(player, Currency.MONEY, cost, CREATE_KIND, Long.toString(id));
        } else {
            tx.silent();
        }
        tx.check(() -> this.registry.of(player).isPresent() ? "in_team" : null)
            .check(() -> this.registry.byName(name).isPresent() ? "name_taken" : null)
            .apply(() -> {
                this.registry.put(team);
                this.invites.clearInvitee(player);
            }, () -> this.registry.remove(id))
            .write(this.store.insertTeam(team));
        TransactionResult result = this.ledger.execute(tx.build());
        return switch (result.status()) {
            case SUCCESS -> Outcome.ok(team, player, result.committed());
            case REJECTED -> Outcome.fail("in_team".equals(result.reason()) ? TeamProblem.ALREADY_IN_TEAM
                : "name_taken".equals(result.reason()) ? TeamProblem.NAME_TAKEN : TeamProblem.FAILED);
            case INSUFFICIENT_FUNDS -> Outcome.fail(TeamProblem.NOT_ENOUGH_MONEY);
            case UNAVAILABLE -> Outcome.fail(TeamProblem.ECONOMY_UNAVAILABLE);
            case CANCELLED -> Outcome.fail(TeamProblem.CANCELLED);
            case BALANCE_LIMIT -> Outcome.fail(TeamProblem.FAILED);
        };
    }

    // ------------------------------------------------------------------ invites

    /** Invites {@code target} to the actor's team for the configured time. */
    public Outcome invite(UUID actor, UUID target) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.invite(team.role(actor));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        if (actor.equals(target)) {
            return Outcome.fail(TeamProblem.NOT_YOURSELF, team);
        }
        Optional<Team> other = this.registry.of(target);
        if (other.isPresent()) {
            return Outcome.fail(other.get().id() == team.id() ? TeamProblem.TARGET_ALREADY_MEMBER : TeamProblem.TARGET_IN_TEAM, team);
        }
        if (!TeamRules.hasRoom(team.size(), memberLimit(team))) {
            return Outcome.fail(TeamProblem.TEAM_FULL, team);
        }
        TeamsSettings s = this.settings.get();
        long now = now();
        if (this.invites.get(target, team.id(), now) != null) {
            return Outcome.fail(TeamProblem.ALREADY_INVITED, team);
        }
        if (this.invites.pendingFromTeam(team.id(), now) >= s.maxOpenInvites()) {
            return Outcome.fail(TeamProblem.TOO_MANY_INVITES, team);
        }
        if (!this.invites.add(team.id(), target, actor, now, s.inviteExpiry().toMillis())) {
            return Outcome.fail(TeamProblem.ALREADY_INVITED, team);
        }
        return Outcome.ok(team, target, CompletableFuture.completedFuture(null));
    }

    /** Declines an invite; the outcome's subject is the inviter. */
    public Outcome decline(UUID player, long teamId) {
        long now = now();
        Team team = this.registry.get(teamId).orElse(null);
        if (this.invites.state(player, teamId, now) == Invites.State.NONE) {
            return Outcome.fail(TeamProblem.NO_INVITE, team);
        }
        Invites.Invite invite = this.invites.take(player, teamId, now);
        if (invite == null) {
            return Outcome.fail(TeamProblem.INVITE_EXPIRED, team);
        }
        return Outcome.ok(team, invite.inviter(), CompletableFuture.completedFuture(null));
    }

    /** Joins a team using its invite; the outcome's subject is the inviter. */
    public Outcome join(UUID player, long teamId) {
        long now = now();
        Invites.State state = this.invites.state(player, teamId, now);
        Team team = this.registry.get(teamId).orElse(null);
        if (state == Invites.State.NONE) {
            return Outcome.fail(TeamProblem.NO_INVITE, team);
        }
        if (state == Invites.State.EXPIRED) {
            this.invites.take(player, teamId, now);
            return Outcome.fail(TeamProblem.INVITE_EXPIRED, team);
        }
        if (team == null) {
            this.invites.take(player, teamId, now);
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        if (this.registry.of(player).isPresent()) {
            return Outcome.fail(TeamProblem.ALREADY_IN_TEAM, team);
        }
        if (!TeamRules.hasRoom(team.size(), memberLimit(team))) {
            return Outcome.fail(TeamProblem.JOIN_TEAM_FULL, team);
        }
        if (!this.events.join(team, player, TeamJoinEvent.Cause.INVITE)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            long at = now();
            Team current = this.registry.get(teamId).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            if (this.registry.of(player).isPresent()) {
                return Outcome.fail(TeamProblem.ALREADY_IN_TEAM, current);
            }
            if (!TeamRules.hasRoom(current.size(), memberLimit(current))) {
                return Outcome.fail(TeamProblem.JOIN_TEAM_FULL, current);
            }
            Invites.Invite invite = this.invites.take(player, teamId, at);
            if (invite == null) {
                return Outcome.fail(TeamProblem.INVITE_EXPIRED, current);
            }
            TeamMember member = new TeamMember(player, TeamRole.MEMBER, at);
            Team next = current.withMember(member);
            this.registry.put(next);
            this.invites.clearInvitee(player);
            return Outcome.ok(next, invite.inviter(), persist(this.store.putMember(teamId, member), "join"));
        });
    }

    // ------------------------------------------------------------------ membership

    public Outcome leave(UUID player) {
        Team team = this.registry.of(player).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.leave(team.role(player));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        if (!this.events.leave(team, player, TeamLeaveEvent.Reason.LEFT, player)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.of(player).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.NOT_IN_TEAM);
            }
            if (current.id() != team.id()) {
                return Outcome.fail(TeamProblem.FAILED, current);
            }
            TeamProblem again = TeamRules.leave(current.role(player));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withoutMember(player);
            this.registry.put(next);
            return Outcome.ok(next, player, persist(this.store.deleteMember(current.id(), player), "leave"));
        });
    }

    public Outcome kick(UUID actor, UUID target) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        if (actor.equals(target)) {
            return Outcome.fail(TeamProblem.NOT_YOURSELF, team);
        }
        TeamProblem problem = TeamRules.kick(team.role(actor), team.role(target));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        if (!this.events.leave(team, target, TeamLeaveEvent.Reason.KICKED, actor)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            if (!current.isMember(actor)) {
                return Outcome.fail(TeamProblem.NOT_IN_TEAM);
            }
            TeamProblem again = TeamRules.kick(current.role(actor), current.role(target));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withoutMember(target);
            this.registry.put(next);
            return Outcome.ok(next, target, persist(this.store.deleteMember(current.id(), target), "kick"));
        });
    }

    public Outcome promote(UUID actor, UUID target) {
        return changeRole(actor, target, TeamRole.ADMIN);
    }

    public Outcome demote(UUID actor, UUID target) {
        return changeRole(actor, target, TeamRole.MEMBER);
    }

    private Outcome changeRole(UUID actor, UUID target, TeamRole role) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        if (actor.equals(target)) {
            return Outcome.fail(TeamProblem.NOT_YOURSELF, team);
        }
        TeamProblem problem = role == TeamRole.ADMIN
            ? TeamRules.promote(team.role(actor), team.role(target))
            : TeamRules.demote(team.role(actor), team.role(target));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = role == TeamRole.ADMIN
                ? TeamRules.promote(current.role(actor), current.role(target))
                : TeamRules.demote(current.role(actor), current.role(target));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withRole(target, role);
            this.registry.put(next);
            return Outcome.ok(next, target, persist(this.store.updateRole(current.id(), target, role), "role"));
        });
    }

    /** Hands the team to another member; the old owner becomes an admin. */
    public Outcome transfer(UUID actor, UUID target) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.transfer(team.role(actor), team.role(target), actor.equals(target));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = TeamRules.transfer(current.role(actor), current.role(target), actor.equals(target));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withOwner(target, 0);
            this.registry.put(next);
            return Outcome.ok(next, target, persist(this.store.transfer(next, actor), "transfer"));
        });
    }

    /** Disbands the actor's team. The outcome's team is the team as it was. Nothing is refunded. */
    public Outcome disband(UUID actor) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.disband(team.role(actor));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        if (!this.events.disband(team, actor)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = TeamRules.disband(current.role(actor));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            this.registry.remove(current.id());
            this.invites.clearTeam(current.id());
            return Outcome.ok(current, actor, persist(this.store.deleteTeam(current.id()), "disband"));
        });
    }

    // ------------------------------------------------------------------ settings of a team

    public Outcome setHome(UUID actor, TeamHome home) {
        return setHome(actor, home, false, false);
    }

    /**
     * Sets the team home at a spot the caller checked against the home rules.
     *
     * @param worldDisabled homes are turned off in the spot's world
     * @param inSpawn       the spot is inside the protected spawn area
     */
    public Outcome setHome(UUID actor, TeamHome home, boolean worldDisabled, boolean inSpawn) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.setHome(team.role(actor), worldDisabled, inSpawn);
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = TeamRules.setHome(current.role(actor), worldDisabled, inSpawn);
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withHome(home);
            this.registry.put(next);
            return Outcome.ok(next, actor, persist(this.store.updateHome(current.id(), home), "home"));
        });
    }

    /** Sets friendly fire ({@code on} null toggles it). Refused with {@code UNCHANGED} when it is already that way. */
    public Outcome friendlyFire(UUID actor, Boolean on) {
        Team team = this.registry.of(actor).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.NOT_IN_TEAM);
        }
        TeamProblem problem = TeamRules.friendlyFire(team.role(actor));
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(team.id()).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = TeamRules.friendlyFire(current.role(actor));
            if (again != null) {
                return Outcome.fail(again, current);
            }
            boolean target = on == null ? !current.friendlyFire() : on;
            if (target == current.friendlyFire()) {
                return Outcome.fail(TeamProblem.UNCHANGED, current);
            }
            Team next = current.withFriendlyFire(target);
            this.registry.put(next);
            return Outcome.ok(next, actor, persist(this.store.updateFriendlyFire(current.id(), target), "friendly fire"));
        });
    }

    /**
     * Remembers the member limit the owner's rank grants (0 = none), so it keeps applying while they are offline.
     * Does nothing unless {@code owner} owns a team and the value changed.
     */
    public void updateRankLimit(UUID owner, int limit) {
        int clean = Math.max(0, limit);
        Team team = this.registry.of(owner).orElse(null);
        if (team == null || !team.owner().equals(owner) || team.ownerRankLimit() == clean) {
            return;
        }
        this.ledger.locked(() -> {
            Team current = this.registry.of(owner).orElse(null);
            if (current == null || !current.owner().equals(owner) || current.ownerRankLimit() == clean) {
                return null;
            }
            this.registry.put(current.withOwnerRankLimit(clean));
            persist(this.store.updateRankLimit(current.id(), clean), "member limit");
            return null;
        });
    }

    // ------------------------------------------------------------------ staff

    /** Puts a player in a team without an invite and regardless of the member limit. */
    public Outcome adminAdd(long teamId, UUID player) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        Optional<Team> existing = this.registry.of(player);
        if (existing.isPresent()) {
            return Outcome.fail(existing.get().id() == teamId ? TeamProblem.TARGET_ALREADY_MEMBER : TeamProblem.TARGET_IN_TEAM,
                existing.get());
        }
        if (!this.events.join(team, player, TeamJoinEvent.Cause.STAFF)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(teamId).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            Optional<Team> again = this.registry.of(player);
            if (again.isPresent()) {
                return Outcome.fail(again.get().id() == teamId ? TeamProblem.TARGET_ALREADY_MEMBER : TeamProblem.TARGET_IN_TEAM,
                    again.get());
            }
            TeamMember member = new TeamMember(player, TeamRole.MEMBER, now());
            Team next = current.withMember(member);
            this.registry.put(next);
            this.invites.clearInvitee(player);
            return Outcome.ok(next, player, persist(this.store.putMember(teamId, member), "staff add"));
        });
    }

    /** Removes a player from their team (not its owner). The outcome's team is the team after. */
    public Outcome adminKick(UUID player) {
        Team team = this.registry.of(player).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TARGET_NOT_MEMBER);
        }
        if (team.owner().equals(player)) {
            return Outcome.fail(TeamProblem.OWNER_CANT_LEAVE, team);
        }
        if (!this.events.leave(team, player, TeamLeaveEvent.Reason.STAFF, null)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.of(player).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TARGET_NOT_MEMBER);
            }
            if (current.owner().equals(player)) {
                return Outcome.fail(TeamProblem.OWNER_CANT_LEAVE, current);
            }
            Team next = current.withoutMember(player);
            this.registry.put(next);
            return Outcome.ok(next, player, persist(this.store.deleteMember(current.id(), player), "staff kick"));
        });
    }

    /** Makes a member the owner; the old owner becomes an admin. */
    public Outcome adminTransfer(long teamId, UUID player) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        TeamRole role = team.role(player);
        if (role == null) {
            return Outcome.fail(TeamProblem.TARGET_NOT_MEMBER, team);
        }
        if (role == TeamRole.OWNER) {
            return Outcome.fail(TeamProblem.UNCHANGED, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(teamId).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamRole now = current.role(player);
            if (now == null) {
                return Outcome.fail(TeamProblem.TARGET_NOT_MEMBER, current);
            }
            if (now == TeamRole.OWNER) {
                return Outcome.fail(TeamProblem.UNCHANGED, current);
            }
            UUID previous = current.owner();
            Team next = current.withOwner(player, 0);
            this.registry.put(next);
            return Outcome.ok(next, player, persist(this.store.transfer(next, previous), "staff transfer"));
        });
    }

    /** Disbands any team. The outcome's team is the team as it was. */
    public Outcome adminDisband(long teamId) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        if (!this.events.disband(team, null)) {
            return Outcome.fail(TeamProblem.CANCELLED, team);
        }
        return this.ledger.locked(() -> {
            Team removed = this.registry.remove(teamId);
            if (removed == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            this.invites.clearTeam(teamId);
            return Outcome.ok(removed, null, persist(this.store.deleteTeam(teamId), "staff disband"));
        });
    }

    public Outcome adminRename(long teamId, String name) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        if (team.name().equals(name)) {
            return Outcome.fail(TeamProblem.NAME_UNCHANGED, team);
        }
        TeamProblem problem = checkName(name, teamId);
        if (problem != null) {
            return Outcome.fail(problem, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(teamId).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            TeamProblem again = checkName(name, teamId);
            if (again != null) {
                return Outcome.fail(again, current);
            }
            Team next = current.withName(name);
            this.registry.put(next);
            return Outcome.ok(next, null, persist(this.store.updateName(teamId, name), "rename"));
        });
    }

    public Outcome adminDeleteHome(long teamId) {
        Team team = this.registry.get(teamId).orElse(null);
        if (team == null) {
            return Outcome.fail(TeamProblem.TEAM_GONE);
        }
        if (team.home() == null) {
            return Outcome.fail(TeamProblem.NO_HOME, team);
        }
        return this.ledger.locked(() -> {
            Team current = this.registry.get(teamId).orElse(null);
            if (current == null) {
                return Outcome.fail(TeamProblem.TEAM_GONE);
            }
            if (current.home() == null) {
                return Outcome.fail(TeamProblem.NO_HOME, current);
            }
            Team next = current.withHome(null);
            this.registry.put(next);
            return Outcome.ok(next, null, persist(this.store.updateHome(teamId, null), "staff home removal"));
        });
    }

    // ------------------------------------------------------------------ storage

    /** Queues a write (called under the economy lock, so the database sees changes in memory order). */
    private CompletableFuture<Void> persist(SqlWork<Void> work, String what) {
        CompletableFuture<Void> stored = this.store.write(work);
        stored.whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Could not save a team change (" + what + "); it is in memory but will be "
                    + "lost on restart", error);
            }
        });
        return stored;
    }
}
