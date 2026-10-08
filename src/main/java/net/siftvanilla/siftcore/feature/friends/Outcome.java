package net.siftvanilla.siftcore.feature.friends;

/**
 * The result of a friends write unit. Business refusals are outcomes, never exceptions: a unit that throws counts as
 * a failed database write.
 */
public enum Outcome {
    /** The request is open and the target sees it. */
    SENT,
    /** The request is stored but hidden from the target; the sender is told {@link #SENT}. */
    SHADOWED,
    /** The two players are friends now. */
    BECAME_FRIENDS,
    /** The target already asked the sender: the request has to run again as an accept (with its event). */
    MUTUAL_NEEDED,
    ALREADY_FRIENDS,
    ALREADY_SENT,
    OUTGOING_FULL,
    DAILY_CAP,
    /** The acting player's friend list is full. */
    SENDER_FULL,
    /** The other player's friend list is full. */
    TARGET_FULL,
    /** The target's privacy setting refuses requests from the sender. */
    PRIVATE,
    /** The request (or row) the action was about no longer exists or has run out. */
    GONE,
    NOT_FRIENDS,
    FAVOURITES_FULL,
    /** The action was applied (or there was nothing left to change). */
    DONE;

    /** Whether the action changed (or confirmed) what the player asked for. */
    public boolean ok() {
        return this == SENT || this == SHADOWED || this == BECAME_FRIENDS || this == DONE;
    }
}
