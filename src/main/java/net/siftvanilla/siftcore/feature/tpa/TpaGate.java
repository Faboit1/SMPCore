package net.siftvanilla.siftcore.feature.tpa;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import org.bukkit.Location;

/**
 * Who may send whom a teleport request, as a pure decision so it can be tested without a server. The order of the
 * checks is the order of the messages a player can get: yourself, not online (or vanished), staff teleport, ignored,
 * requests turned off, then allowed. Also the combat rules of accepting and arriving.
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
     * their consent, so it always asks. A target in combat is asked too: nobody arrives in the middle of their fight
     * without them saying yes once it is over.
     */
    static boolean friendSkips(Kind kind, boolean friends, boolean targetAllowsFriends, boolean targetInCombat) {
        return kind == Kind.TO_TARGET && friends && targetAllowsFriends && !targetInCombat;
    }

    /** Who of the two players keeps a request from being accepted now because they are in combat. */
    enum Fight {
        /** Nobody: the request can be accepted. */
        NONE,
        /** The player answering. */
        YOU,
        /** The player who sent it. */
        OTHER
    }

    /**
     * Whether a request can be accepted while someone is in combat: never. The answering player can't pull a sender
     * into their fight (or leave it), and nobody is pulled into the sender's fight. The request stays, so it can be
     * accepted once the fight is over. Combat-tagged players can't send requests either (the commands are refused in
     * combat, and so is the main menu's request form).
     *
     * @param answererInCombat the player accepting is combat-tagged
     * @param senderInCombat   the player who sent the request is combat-tagged
     */
    static Fight acceptBlocked(boolean answererInCombat, boolean senderInCombat) {
        if (answererInCombat) {
            return Fight.YOU;
        }
        return senderInCombat ? Fight.OTHER : Fight.NONE;
    }

    /**
     * Where the moving player arrives after an accepted request (or a friend's visit), checked once more when the
     * warmup is over: the shared teleports re-check only the player who moves, so if the player who stays put got into
     * a fight during the warmup, nobody arrives in the middle of it. The destination then completes with null (no
     * teleport) after {@code refused} told the mover.
     *
     * @param destination     the staying player's position, read when the warmup ends
     * @param stayingInCombat whether the player who doesn't move is combat-tagged right now
     */
    static CompletableFuture<Location> unlessFighting(CompletableFuture<Location> destination, BooleanSupplier stayingInCombat,
                                                      Runnable refused) {
        return destination.thenApply(location -> {
            if (location != null && stayingInCombat.getAsBoolean()) {
                refused.run();
                return null;
            }
            return location;
        });
    }
}
