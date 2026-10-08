package net.siftvanilla.siftcore.feature.tpa;

import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;

/**
 * Who may send whom a teleport request, as a pure decision so it can be tested without a server. The order of the
 * checks is the order of the messages a player can get: yourself, not online (or vanished), staff teleport, ignored,
 * requests turned off, then allowed.
 */
final class TpaGate {

    /** The outcome of sending a request. */
    enum Verdict {
        /** The sender named themselves. */
        SELF,
        /** The target is offline, or hidden from the sender (vanished staff). */
        NOT_ONLINE,
        /** Staff /tpa: teleport at once, no request. */
        STAFF_INSTANT,
        /** The target ignores the sender; the target is never told. */
        IGNORED,
        /** The target turned requests off. */
        REQUESTS_OFF,
        /** The request may be sent (cooldown and the cancellable event still apply). */
        ALLOWED
    }

    private TpaGate() {
    }

    /**
     * @param self             sender and target are the same player
     * @param visible          the target is online and the sender may see them
     * @param staff            the sender has {@code siftcore.tpa.bypass}
     * @param ignored          the target ignores the sender
     * @param acceptsRequests  the target accepts teleport requests
     */
    static Verdict check(boolean self, boolean visible, boolean staff, Kind kind, boolean ignored, boolean acceptsRequests) {
        if (self) {
            return Verdict.SELF;
        }
        if (!visible) {
            return Verdict.NOT_ONLINE;
        }
        if (staff && kind == Kind.TO_TARGET) {
            return Verdict.STAFF_INSTANT;
        }
        if (!staff && ignored) {
            return Verdict.IGNORED;
        }
        if (!staff && !acceptsRequests) {
            return Verdict.REQUESTS_OFF;
        }
        return Verdict.ALLOWED;
    }

    /**
     * Whether an allowed request skips the answer because the two are friends and the target lets friends come
     * without asking. Only a /tpa (the friend comes to the target) skips; a /tpahere would move the target without
     * their consent, so it always asks.
     */
    static boolean friendSkips(Kind kind, boolean friends, boolean targetAllowsFriends) {
        return kind == Kind.TO_TARGET && friends && targetAllowsFriends;
    }
}
