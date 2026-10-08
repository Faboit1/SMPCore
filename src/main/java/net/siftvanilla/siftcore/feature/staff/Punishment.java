package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * One punishment record. Immutable; lifting one produces a copy with {@link #revoked()} set.
 *
 * @param id         row id
 * @param type       what kind of punishment
 * @param target     the punished player
 * @param targetName their name when it happened
 * @param staff      who did it: a player UUID or {@code console}
 * @param staffName  that staff member's name at the time
 * @param reason     plain text given by staff, empty when none was given
 * @param created    when it was given (epoch milliseconds)
 * @param expires    when a lasting punishment ends, {@link #PERMANENT} for no end, {@link #NONE} for kicks and warnings
 * @param revoked    when it was lifted early, {@link #NONE} if never
 * @param revokedBy  who lifted it (a name), empty if never
 */
public record Punishment(long id, PunishmentType type, UUID target, String targetName, String staff, String staffName,
                         String reason, long created, long expires, long revoked, String revokedBy) {

    /** {@link #expires()} of a punishment without an end. */
    public static final long PERMANENT = Long.MAX_VALUE;
    /** {@link #expires()} of kicks and warnings, and {@link #revoked()} of a punishment that was never lifted. */
    public static final long NONE = 0L;

    public Punishment {
        Objects.requireNonNull(type);
        Objects.requireNonNull(target);
        targetName = targetName == null ? "" : targetName;
        staff = staff == null ? "" : staff;
        staffName = staffName == null ? "" : staffName;
        reason = reason == null ? "" : reason;
        revokedBy = revokedBy == null ? "" : revokedBy;
        if (!type.lasting()) {
            expires = NONE;
        } else if (expires <= NONE) {
            throw new IllegalArgumentException("A lasting punishment needs an end time or PERMANENT");
        }
    }

    /** Where this punishment stands at {@code now}. */
    public PunishmentState state(long now) {
        if (!this.type.lasting()) {
            return PunishmentState.RECORD;
        }
        if (this.revoked != NONE) {
            return PunishmentState.LIFTED;
        }
        if (this.expires != PERMANENT && this.expires <= now) {
            return PunishmentState.EXPIRED;
        }
        return PunishmentState.ACTIVE;
    }

    public boolean active(long now) {
        return state(now) == PunishmentState.ACTIVE;
    }

    public boolean permanent() {
        return this.type.lasting() && this.expires == PERMANENT;
    }

    /** Time left at {@code now}; zero once it is no longer active, and not meaningful for permanent ones. */
    public Duration remaining(long now) {
        if (!active(now) || permanent()) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(this.expires - now);
    }

    /** How long it was given for; null for permanent punishments, kicks and warnings. */
    public Duration length() {
        if (!this.type.lasting() || permanent()) {
            return null;
        }
        return Duration.ofMillis(Math.max(0, this.expires - this.created));
    }

    public boolean hasReason() {
        return !this.reason.isBlank();
    }

    /** A copy that was lifted at {@code when} by {@code by}. */
    public Punishment lift(long when, String by) {
        if (!this.type.lasting()) {
            throw new IllegalStateException(this.type + " can't be lifted");
        }
        return new Punishment(this.id, this.type, this.target, this.targetName, this.staff, this.staffName, this.reason,
            this.created, this.expires, Math.max(1L, when), by);
    }

    /** The end time for a punishment of {@code length} starting at {@code start}, or {@link #PERMANENT} for null. */
    public static long endOf(long start, Duration length) {
        if (length == null) {
            return PERMANENT;
        }
        long millis = length.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("A punishment must last a positive time");
        }
        try {
            return Math.addExact(start, millis);
        } catch (ArithmeticException e) {
            return PERMANENT;
        }
    }
}
