package net.siftvanilla.siftcore.ui.dialog;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The live dialog sessions of every player, kept in two pools per player so neither can push the other out:
 * <ul>
 *   <li><b>screens</b>: dialogs put on screen with {@link Dialogs#show}. Only the newest one is on the player's screen
 *       (a new dialog replaces the old one); the few before it stay so a late second click on them is recognised and
 *       ignored. Capped at {@link #MAX_SCREENS}, the oldest goes first.</li>
 *   <li><b>chat</b>: dialogs embedded in chat with {@link Dialogs#inline} (a teleport request's or a team invite's
 *       answer). The player may open them from chat at any time while they are fresh, however many screens they open
 *       meanwhile. Capped at {@link #MAX_CHAT}; over the cap, answered ones go before unanswered ones.</li>
 * </ul>
 * Each pool has its own time to live: a screen is only clicked while it is open, but a chat dialog answers a request
 * that may last much longer (a team invite up to an hour), so it lives at least as long. Sessions older than their
 * pool's time to live are dropped. Thread-safe: each player's pools are guarded by their own lock.
 */
final class DialogSessions {

    /** Screens kept per player: the one on screen plus the latest few before it (late clicks on them are ignored). */
    static final int MAX_SCREENS = 8;
    /** Dialogs waiting in chat kept per player: well above the requests and invites anyone can have open at once. */
    static final int MAX_CHAT = 32;

    /**
     * One shown view.
     *
     * @param inline     whether it is embedded in chat rather than put on screen
     * @param consumedAt when its one click was accepted, 0 while it is unused
     */
    record Session(long token, View view, long created, boolean inline, AtomicLong consumedAt) {

        boolean consumed() {
            return this.consumedAt.get() != 0;
        }
    }

    /** One player's two pools, in the order their sessions were made. */
    private static final class Pools {
        final Map<Long, Session> screens = new LinkedHashMap<>();
        final Map<Long, Session> chat = new LinkedHashMap<>();
    }

    private final long screenTtlMillis;
    private final long chatTtlMillis;
    private final LongSupplier clock;
    private final Map<UUID, Pools> players = new ConcurrentHashMap<>();

    /**
     * @param screenTtlMillis how long a dialog put on screen stays clickable
     * @param chatTtlMillis   how long a dialog embedded in chat stays clickable (at least as long as any request it answers)
     */
    DialogSessions(long screenTtlMillis, long chatTtlMillis, LongSupplier clock) {
        this.screenTtlMillis = screenTtlMillis;
        this.chatTtlMillis = chatTtlMillis;
        this.clock = clock;
    }

    /** Adds a session for a view just shown ({@code inline}: embedded in chat) and returns it. */
    Session add(UUID player, long token, View view, boolean inline) {
        long now = this.clock.getAsLong();
        Session session = new Session(token, view, now, inline, new AtomicLong());
        Pools pools = this.players.computeIfAbsent(player, k -> new Pools());
        synchronized (pools) {
            Map<Long, Session> pool = inline ? pools.chat : pools.screens;
            pool.put(token, session);
            dropExpired(pool, now);
            if (inline) {
                trim(pool, MAX_CHAT, true);
            } else {
                trim(pool, MAX_SCREENS, false);
            }
        }
        return session;
    }

    /** The live session of a token, or null when it is unknown, belongs to someone else or aged out. */
    Session get(UUID player, long token) {
        Pools pools = this.players.get(player);
        if (pools == null) {
            return null;
        }
        Session session;
        synchronized (pools) {
            session = pools.screens.get(token);
            if (session == null) {
                session = pools.chat.get(token);
            }
        }
        if (session == null || expired(session, this.clock.getAsLong())) {
            return null;
        }
        return session;
    }

    /** Forgets every session of a player (they left). */
    void forget(UUID player) {
        this.players.remove(player);
    }

    void clear() {
        this.players.clear();
    }

    /** Number of sessions held (metrics). */
    int size() {
        int count = 0;
        for (Pools pools : this.players.values()) {
            synchronized (pools) {
                count += pools.screens.size() + pools.chat.size();
            }
        }
        return count;
    }

    private boolean expired(Session session, long now) {
        return now - session.created() > (session.inline() ? this.chatTtlMillis : this.screenTtlMillis);
    }

    /** A pool's sessions share its time to live and are kept in the order they were made, so the expired ones are at the front. */
    private void dropExpired(Map<Long, Session> pool, long now) {
        Iterator<Session> it = pool.values().iterator();
        while (it.hasNext() && expired(it.next(), now)) {
            it.remove();
        }
    }

    /**
     * Drops sessions until at most {@code max} are left, oldest first. With {@code answeredFirst}, sessions already
     * clicked go before any unanswered one (a chat dialog that was answered is only kept to recognise a second click).
     */
    private static void trim(Map<Long, Session> pool, int max, boolean answeredFirst) {
        if (answeredFirst) {
            Iterator<Session> it = pool.values().iterator();
            while (pool.size() > max && it.hasNext()) {
                if (it.next().consumed()) {
                    it.remove();
                }
            }
        }
        Iterator<Session> it = pool.values().iterator();
        while (pool.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
