package net.siftvanilla.siftcore.feature.tpa;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import org.bukkit.Location;

/**
 * Who may send whom a teleport request, as a pure decision so it can be tested without a server. The order of the
 * checks is the order of the messages a player can get: yourself, not online (or vanished), staff teleport, ignored,
 * requests not taken from the sender, pull requests not taken from the sender, then allowed. Also who comes without
 * asking (auto-accept), when a request pops up, and the combat rules of accepting and arriving.
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
        /** The target takes no teleport requests from the sender ("Teleport requests from"). */
        REQUESTS_OFF,
        /** A /tpahere the target takes from nobody or not from the sender ("Pull requests from"). */
        PULLS_OFF,
        /** The request may be sent (cooldown and the cancellable event still apply). */
        ALLOWED
    }

    private TpaGate() {
    }

    /** {@link #check(boolean, boolean, boolean, Kind, boolean, boolean, boolean)} for a target who takes every /tpahere. */
    static Verdict check(boolean self, boolean visible, boolean staff, Kind kind, boolean ignored, boolean acceptsRequests) {
        return check(self, visible, staff, kind, ignored, acceptsRequests, true);
    }

    /**
     * @param self            sender and target are the same player
     * @param visible         the target is online and the sender may see them
     * @param staff           the sender has {@code siftcore.tpa.bypass}
     * @param ignored         the target ignores the sender
     * @param acceptsRequests the target's "Teleport requests from" lets the sender ask ({@link #accepts})
     * @param acceptsPulls    the target's "Pull requests from" lets the sender ask them over; only a /tpahere reads it,
     *                        on top of {@code acceptsRequests} (the stricter of the two wins)
     */
    static Verdict check(boolean self, boolean visible, boolean staff, Kind kind, boolean ignored, boolean acceptsRequests,
                         boolean acceptsPulls) {
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
        if (!staff && kind == Kind.TO_SENDER && !acceptsPulls) {
            return Verdict.PULLS_OFF;
        }
        return Verdict.ALLOWED;
    }

    /**
     * Whether a "who can" choice of the target lets the sender in: everyone, friends and teammates, friends, or
     * nobody (the shared {@link Relations#allows} rule).
     *
     * @param friends  the two are friends
     * @param sameTeam the two are in the same team
     */
    static boolean accepts(Audience audience, boolean friends, boolean sameTeam) {
        return Relations.allows(audience, friends, sameTeam);
    }

    /**
     * Whether the target's "Auto-accept /tpa from" lets the sender come without a request. An ignored player never
     * does, whatever the choice.
     *
     * @param mode      the target's choice (favourites reads as nobody while the server has no favourites)
     * @param friends   the two are friends
     * @param favourite the sender is one of the target's favourite friends
     * @param sameTeam  the two are in the same team
     * @param ignored   the target ignores the sender
     */
    static boolean autoAccepts(AutoAccept mode, boolean friends, boolean favourite, boolean sameTeam, boolean ignored) {
        if (ignored) {
            return false;
        }
        return switch (mode) {
            case NOBODY -> false;
            case FAVOURITES -> friends && favourite;
            case ALL -> friends;
            case FRIENDS_TEAM -> friends || sameTeam;
        };
    }

    /**
     * Whether an allowed request skips the answer because the target accepts the sender's /tpa without asking
     * ({@link #autoAccepts}). Only a /tpa (the sender comes to the target) skips; a /tpahere would move the target
     * without their consent, so it always asks. A target in combat is asked too: nobody arrives in the middle of their
     * fight without them saying yes once it is over.
     */
    static boolean skips(Kind kind, boolean autoAccepted, boolean targetInCombat) {
        return kind == Kind.TO_TARGET && autoAccepted && !targetInCombat;
    }

    /**
     * Whether an incoming request opens the accept/deny window ("Requests open a pop-up"): only when the player chose
     * it, and never while they are in combat, AFK, or busy in a window.
     *
     * @param windowOpen a window the server opened (a chest, a SiftCore menu), or the player's own inventory while
     *                   they are clicking in it ({@link InventoryUse}); the server can't see the own inventory merely
     *                   being open, nor another dialog
     */
    static boolean popsUp(boolean wanted, boolean inCombat, boolean afk, boolean windowOpen) {
        return wanted && !inCombat && !afk && !windowOpen;
    }

    /**
     * Whether accepting a request with /tpaccept asks once more ("Confirm before being pulled"): only for a /tpahere
     * (accepting it moves the player who answers) and only while they keep the setting on.
     */
    static boolean confirmsPull(Kind kind, boolean wanted) {
        return kind == Kind.TO_SENDER && wanted;
    }

    /** What answering one request with /tpaccept or /tpdeny does. */
    enum Answer {
        /** Accept now: the warmup of whoever moves starts. */
        ACCEPT,
        /** Show the request's own accept/deny window first ("Confirm before being pulled"). */
        ASK_FIRST,
        /** Deny it. */
        DENY
    }

    /**
     * What a /tpaccept or /tpdeny of one request does, however the request was chosen: the only one, a named one, or a
     * pick in the window of several. Accepting a /tpahere that still waits asks once more while "Confirm before being
     * pulled" is on ({@link #confirmsPull}); an answered or expired request is accepted straight away, which just says
     * it is gone.
     *
     * @param accept  /tpaccept (true) or /tpdeny (false)
     * @param wanted  the answering player's "Confirm before being pulled"
     * @param waiting the request still waits for the answering player
     */
    static Answer answer(boolean accept, Kind kind, boolean wanted, boolean waiting) {
        if (!accept) {
            return Answer.DENY;
        }
        return waiting && confirmsPull(kind, wanted) ? Answer.ASK_FIRST : Answer.ACCEPT;
    }

    /** How {@code /tpatoggle friends [choice]} answers before anything is changed. */
    enum Typed {
        /** The server locked or hides the setting: "... is set by the server.", whatever was typed. */
        SERVER,
        /** The player is offered no choice at all (the setting's permission): "... couldn't be changed.". */
        NOT_OFFERED,
        /** A word that is no choice: "Use one of these: ..." with the choices the player may pick. */
        UNKNOWN,
        /** Try the change (an option not offered now is still refused by the settings, and reported). */
        CHANGE
    }

    /**
     * The order of the checks of {@code /tpatoggle friends [choice]}: the server's say comes first, so a hidden or
     * locked setting never answers "Use one of these: ." with an empty list.
     *
     * @param serverDecides the server locked or hides the setting
     * @param known         the typed word is a choice (always true without one: the command flips)
     * @param anyOffered    the player may pick at least one choice
     */
    static Typed typedChoice(boolean serverDecides, boolean known, boolean anyOffered) {
        if (serverDecides) {
            return Typed.SERVER;
        }
        if (!anyOffered) {
            return Typed.NOT_OFFERED;
        }
        return known ? Typed.CHANGE : Typed.UNKNOWN;
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
