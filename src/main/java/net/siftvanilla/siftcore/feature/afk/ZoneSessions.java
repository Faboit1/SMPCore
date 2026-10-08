package net.siftvanilla.siftcore.feature.afk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is in the AFK zone, who of them earns, and when their next reward is due. Pure logic driven by timestamps;
 * thread-safe (every method is synchronized, the calls come from the threads of different players).
 * <p>
 * Only one account per connection earns at a time: players are grouped by a connection key (a salted hash of their
 * address). The first of a group to enter holds the group's slot; the others wait. When the holder leaves, the
 * group member who entered next takes over and starts a fresh interval. Leaving the zone, or a combat tag, throws
 * away the progress towards the next reward: only continuous, peaceful presence pays.
 */
final class ZoneSessions {

    /** Why a player in the zone is or isn't earning. */
    enum State {
        /** Earning; the next reward comes in {@link Status#nextInMillis()}. */
        EARNING,
        /** Another account on the same connection holds the slot. */
        WAITING_ALT,
        /** Combat-tagged: no progress until the tag ends. */
        COMBAT,
        /** Reached the daily limit. */
        CAPPED,
        /** Today's earnings are still being read from storage. */
        LOADING
    }

    /** What stops a slot holder from earning right now, if anything. */
    enum Block {
        NONE,
        COMBAT,
        CAPPED,
        LOADING
    }

    /**
     * A player's zone state after an update.
     *
     * @param due          a reward is due now (at most one per update)
     * @param nextInMillis time until the next reward when earning, otherwise the full interval
     */
    record Status(State state, boolean due, long nextInMillis) {
    }

    private static final class Session {
        private final UUID player;
        private final String connection;
        private final long entered;
        private long since = -1;

        private Session(UUID player, String connection, long entered) {
            this.player = player;
            this.connection = connection;
            this.entered = entered;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, UUID> holders = new HashMap<>();

    /** The player entered the zone. Entering again while inside changes nothing. */
    synchronized void enter(UUID player, String connection, long now) {
        if (this.sessions.containsKey(player)) {
            return;
        }
        Session session = new Session(player, connection, now);
        this.sessions.put(player, session);
        if (!this.holders.containsKey(connection)) {
            this.holders.put(connection, player);
            session.since = now;
        }
    }

    /** The player left the zone (or the server). Their progress is lost; a waiting account of theirs takes over. */
    synchronized void leave(UUID player, long now) {
        Session session = this.sessions.remove(player);
        if (session == null) {
            return;
        }
        if (player.equals(this.holders.get(session.connection))) {
            this.holders.remove(session.connection);
            Session next = null;
            for (Session other : this.sessions.values()) {
                if (other.connection.equals(session.connection) && (next == null || other.entered < next.entered)) {
                    next = other;
                }
            }
            if (next != null) {
                this.holders.put(next.connection, next.player);
                next.since = now;
            }
        }
    }

    synchronized boolean inside(UUID player) {
        return this.sessions.containsKey(player);
    }

    /** The account on the same connection that holds the slot, or null when this player holds it or is not inside. */
    synchronized UUID holderFor(UUID player) {
        Session session = this.sessions.get(player);
        if (session == null) {
            return null;
        }
        UUID holder = this.holders.get(session.connection);
        return player.equals(holder) ? null : holder;
    }

    /**
     * Advances a player's clock and tells whether a reward is due. Returns null when the player is not in the zone.
     *
     * @param block what stops the player from earning right now (only matters for the slot holder)
     */
    synchronized Status update(UUID player, long now, long intervalMillis, Block block) {
        Session session = this.sessions.get(player);
        if (session == null) {
            return null;
        }
        if (!player.equals(this.holders.get(session.connection))) {
            session.since = -1;
            return new Status(State.WAITING_ALT, false, intervalMillis);
        }
        switch (block) {
            case COMBAT -> {
                session.since = now;
                return new Status(State.COMBAT, false, intervalMillis);
            }
            case CAPPED -> {
                session.since = now;
                return new Status(State.CAPPED, false, intervalMillis);
            }
            case LOADING -> {
                session.since = now;
                return new Status(State.LOADING, false, intervalMillis);
            }
            case NONE -> {
            }
        }
        if (session.since < 0 || session.since > now) {
            session.since = now;
        }
        long elapsed = now - session.since;
        boolean due = elapsed >= intervalMillis;
        if (due) {
            // One reward per update; after a long stall (lag, a paused clock) restart the interval instead of paying
            // a burst for time that was not observed.
            session.since = elapsed >= 2 * intervalMillis ? now : session.since + intervalMillis;
        }
        long next = Math.max(0, intervalMillis - (now - session.since));
        return new Status(State.EARNING, due, next);
    }

    /** Everyone inside, in no particular order. */
    synchronized List<UUID> players() {
        return new ArrayList<>(this.sessions.keySet());
    }

    synchronized int size() {
        return this.sessions.size();
    }

    /** Forgets everyone (the zone moved or was turned off). */
    synchronized void clear() {
        this.sessions.clear();
        this.holders.clear();
    }

    /** Consistency check for the self-test: every holder is inside, and every connection inside has one holder. */
    synchronized String check() {
        for (Map.Entry<String, UUID> holder : this.holders.entrySet()) {
            Session session = this.sessions.get(holder.getValue());
            if (session == null || !session.connection.equals(holder.getKey())) {
                return "a slot is held by a player who is not inside";
            }
        }
        for (Session session : this.sessions.values()) {
            if (!this.holders.containsKey(session.connection)) {
                return "a connection inside the zone has nobody earning";
            }
        }
        return null;
    }
}
