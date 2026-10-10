package net.siftvanilla.siftcore.feature.teams;

import java.util.UUID;
import net.siftvanilla.siftcore.api.event.TeamCreateEvent;
import net.siftvanilla.siftcore.api.event.TeamDisbandEvent;
import net.siftvanilla.siftcore.api.event.TeamJoinEvent;
import net.siftvanilla.siftcore.api.event.TeamLeaveEvent;

/**
 * Asks other plugins whether a team change may happen, by firing the cancellable team events. Called by the
 * service on the acting thread before anything changes; returns false when a listener cancelled.
 */
public interface TeamEventGate {

    /** Allows everything (no server; used by tests). */
    TeamEventGate ALLOW = new TeamEventGate() {
        @Override
        public boolean create(UUID owner, String name, long cost) {
            return true;
        }

        @Override
        public boolean join(Team team, UUID player, TeamJoinEvent.Cause cause) {
            return true;
        }

        @Override
        public boolean leave(Team team, UUID player, TeamLeaveEvent.Reason reason, UUID actor) {
            return true;
        }

        @Override
        public boolean disband(Team team, UUID actor) {
            return true;
        }
    };

    boolean create(UUID owner, String name, long cost);

    boolean join(Team team, UUID player, TeamJoinEvent.Cause cause);

    boolean leave(Team team, UUID player, TeamLeaveEvent.Reason reason, UUID actor);

    /** {@code actor} is the owner, or null for staff and the console. */
    boolean disband(Team team, UUID actor);

    /** Fires the real events. */
    final class Bukkit implements TeamEventGate {

        @Override
        public boolean create(UUID owner, String name, long cost) {
            return new TeamCreateEvent(owner, name, cost).callEvent();
        }

        @Override
        public boolean join(Team team, UUID player, TeamJoinEvent.Cause cause) {
            return new TeamJoinEvent(team.id(), team.name(), player, cause).callEvent();
        }

        @Override
        public boolean leave(Team team, UUID player, TeamLeaveEvent.Reason reason, UUID actor) {
            return new TeamLeaveEvent(team.id(), team.name(), player, reason, actor).callEvent();
        }

        @Override
        public boolean disband(Team team, UUID actor) {
            return new TeamDisbandEvent(team.id(), team.name(), team.owner(), team.memberIds(), actor).callEvent();
        }
    }
}
