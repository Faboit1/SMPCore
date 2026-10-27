package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.YearMonth;
import java.util.Set;
import net.kyori.adventure.text.Component;

/**
 * A chat tag from {@code features/cosmetics.yml}, shown before a player's name in public chat.
 *
 * @param id          stable id (stored for the players who pick it)
 * @param source      the configured MiniMessage of its look
 * @param display     its look, parsed
 * @param plain       its text without formatting
 * @param description one line about it, or empty
 * @param permission  who may pick it
 * @param month       for a monthly exclusive, the month it can be picked in; null otherwise
 * @param hint        what unlocks it (shown on locked tags), or empty
 */
record ChatTag(String id, String source, Component display, String plain, String description, String permission, YearMonth month,
               String hint) {

    /** A tag id: letters, digits, dashes and underscores. */
    static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }

    /**
     * Whether a player may pick the tag now: they own it (a monthly exclusive they picked in its month), or they have
     * its permission and it is not a monthly exclusive of another month.
     */
    boolean usable(boolean permitted, Set<String> owned, YearMonth now) {
        return owned.contains(this.id) || permitted && (this.month == null || this.month.equals(now));
    }

    /** Whether a player who can't pick it still sees it (locked) in /tags: monthly exclusives only in their month. */
    boolean shownLocked(YearMonth now) {
        return this.month == null || this.month.equals(now);
    }

    /** Whether picking it now makes it the player's for good (a monthly exclusive in its month). */
    boolean claimable(YearMonth now) {
        return this.month != null && this.month.equals(now);
    }
}
