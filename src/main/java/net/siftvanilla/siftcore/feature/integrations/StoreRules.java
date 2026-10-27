package net.siftvanilla.siftcore.feature.integrations;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The pure rules of store delivery: what a reference may look like and how long a granted rank lasts. */
final class StoreRules {

    /** Longest reference; leaves room for the {@code store:} prefix in crate grant references (64). */
    static final int MAX_REF = 48;
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_.+\\-]+");
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_\\-]{1,36}");

    private StoreRules() {
    }

    /** Why a reference is unusable, or null when it is fine: 1-48 letters, digits and {@code _ . + -}. */
    static String refProblem(String ref) {
        if (ref == null || ref.isEmpty()) {
            return "empty";
        }
        if (ref.length() > MAX_REF) {
            return "too_long";
        }
        return REF.matcher(ref).matches() ? null : "bad_characters";
    }

    /** A LuckPerms group name in lowercase, or null when it can't be one. */
    static String group(String input) {
        if (input == null) {
            return null;
        }
        String group = input.toLowerCase(Locale.ROOT);
        return GROUP.matcher(group).matches() ? group : null;
    }

    /** A revoke reason as one short lowercase word ({@code refund}, {@code chargeback}); {@code refund} when blank. */
    static String reason(String input) {
        if (input == null || input.isBlank()) {
            return "refund";
        }
        String clean = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (clean.isEmpty()) {
            return "refund";
        }
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    /** A player given as a UUID (with dashes), for buyers who never joined. */
    static Optional<UUID> uuid(String input) {
        if (input == null || input.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * When a granted rank ends, or null for permanent. A permanent purchase is permanent. A timed purchase adds its
     * duration to whatever the player already has: from the end of their current timed grant of that group, or from
     * now; if they hold the group permanently, it stays permanent. The end is a whole second.
     *
     * @param heldPermanently whether the player holds the group without an end
     * @param heldUntil       the end of their current timed grant, or null
     * @param duration        the purchase, null for permanent
     */
    static Instant rankEnd(boolean heldPermanently, Instant heldUntil, Instant now, Duration duration) {
        if (duration == null || heldPermanently) {
            return null;
        }
        Instant base = heldUntil != null && heldUntil.isAfter(now) ? heldUntil : now;
        // LuckPerms stores expiry in whole seconds; a whole-second end compares equal after the round trip.
        return base.plus(duration).truncatedTo(ChronoUnit.SECONDS);
    }
}
