package net.siftvanilla.siftcore.feature.afk;

/**
 * When a player is AFK, and when they are warned and kicked for it. Pure logic driven by timestamps, one instance per
 * player, not thread-safe (its owner {@link PlayerAfk} serialises the calls).
 * <p>
 * A player becomes AFK after {@code afkAfter} without genuine activity, or at once with /afk. Genuine activity ends
 * it, except during a short grace after /afk (so the keystrokes of the command itself don't undo it).
 * <p>
 * Activity comes in two kinds. <em>Actions</em> (chat, commands, using blocks, entities and menus, attacking) always
 * count. <em>Motion</em> (looking around, walking somewhere new) counts only within {@code motionLimit} of the last
 * action: an anti-AFK script that keeps turning the view in a random, human-looking way can't keep a player active
 * forever, because sooner or later a real player does something.
 * <p>
 * Only time spent AFK <em>outside</em> the AFK zone counts towards the kick: entering the zone stops the kick clock,
 * leaving it while still AFK starts it again from zero.
 */
final class AfkClock {

    /** What a call changed. */
    enum Change {
        NONE,
        /** Just became AFK (after the inactivity time, or with /afk). */
        BECAME_AFK,
        /** Was AFK and is back. */
        RETURNED,
        /** The kick comes in {@code warnBefore}; tell the player once. */
        KICK_WARNING,
        /** Kick now. */
        KICK
    }

    /**
     * Timing.
     *
     * @param afkAfterMillis    inactivity before a player is AFK
     * @param kickAfterMillis   time AFK outside the zone before the kick, or 0 for no kick
     * @param warnBeforeMillis  how long before the kick to warn, or 0 for no warning
     * @param graceMillis       after /afk, activity in this window does not end the AFK status
     * @param motionLimitMillis how long after the last action motion alone still counts, or 0 for no limit
     */
    record Timing(long afkAfterMillis, long kickAfterMillis, long warnBeforeMillis, long graceMillis, long motionLimitMillis) {

        Timing {
            if (afkAfterMillis <= 0 || kickAfterMillis < 0 || warnBeforeMillis < 0 || graceMillis < 0 || motionLimitMillis < 0) {
                throw new IllegalArgumentException("Invalid AFK timing");
            }
        }
    }

    private long lastAction;
    private long lastMotion;
    private boolean afk;
    private boolean manual;
    private long afkSince;
    private long graceUntil;
    private long outsideSince = -1;
    private boolean warned;

    AfkClock(long now) {
        this.lastAction = now;
        this.lastMotion = now;
    }

    boolean afk() {
        return this.afk;
    }

    boolean manual() {
        return this.afk && this.manual;
    }

    /** When the current AFK spell started, or -1 when not AFK. */
    long afkSince() {
        return this.afk ? this.afkSince : -1;
    }

    /** The last moment that counts as activity: the last action, or later motion within the motion limit. */
    long lastActivity(Timing timing) {
        long motion = timing.motionLimitMillis() == 0 ? this.lastMotion
            : Math.min(this.lastMotion, this.lastAction + timing.motionLimitMillis());
        return Math.max(this.lastAction, motion);
    }

    /**
     * Genuine activity.
     *
     * @param motion true for looking around or walking, false for an action (chat, a command, a click)
     */
    Change activity(long now, boolean motion, Timing timing) {
        if (this.afk && this.manual && now < this.graceUntil) {
            return Change.NONE;
        }
        if (motion) {
            this.lastMotion = Math.max(this.lastMotion, now);
            if (timing.motionLimitMillis() > 0 && now - this.lastAction > timing.motionLimitMillis()) {
                // Too long without a real action: moving around alone no longer counts.
                return Change.NONE;
            }
        } else {
            this.lastAction = Math.max(this.lastAction, now);
        }
        if (!this.afk) {
            return Change.NONE;
        }
        clear();
        return Change.RETURNED;
    }

    /** /afk: marks the player AFK at once. */
    Change goAfk(long now, Timing timing) {
        if (this.afk) {
            this.manual = true;
            this.graceUntil = now + timing.graceMillis();
            return Change.NONE;
        }
        this.afk = true;
        this.manual = true;
        this.afkSince = now;
        this.graceUntil = now + timing.graceMillis();
        this.outsideSince = -1;
        this.warned = false;
        return Change.BECAME_AFK;
    }

    /** /afk while AFK: back at once, grace or not (typing a command is an action). */
    Change comeBack(long now) {
        this.lastAction = Math.max(this.lastAction, now);
        if (!this.afk) {
            return Change.NONE;
        }
        clear();
        return Change.RETURNED;
    }

    private void clear() {
        this.afk = false;
        this.manual = false;
        this.afkSince = 0;
        this.graceUntil = 0;
        this.outsideSince = -1;
        this.warned = false;
    }

    /**
     * The periodic check (about once a second).
     *
     * @param inZone   whether the player is inside the AFK zone right now
     * @param kickable whether this player may be kicked (no bypass permission)
     */
    Change tick(long now, Timing timing, boolean inZone, boolean kickable) {
        if (!this.afk) {
            if (now - lastActivity(timing) >= timing.afkAfterMillis()) {
                this.afk = true;
                this.manual = false;
                this.afkSince = now;
                this.outsideSince = -1;
                this.warned = false;
                return Change.BECAME_AFK;
            }
            return Change.NONE;
        }
        if (inZone || timing.kickAfterMillis() == 0 || !kickable) {
            this.outsideSince = -1;
            this.warned = false;
            return Change.NONE;
        }
        if (this.outsideSince < 0) {
            this.outsideSince = now;
        }
        long outside = now - this.outsideSince;
        if (outside >= timing.kickAfterMillis()) {
            return Change.KICK;
        }
        long warnAt = timing.kickAfterMillis() - timing.warnBeforeMillis();
        if (!this.warned && timing.warnBeforeMillis() > 0 && warnAt > 0 && outside >= warnAt) {
            this.warned = true;
            return Change.KICK_WARNING;
        }
        return Change.NONE;
    }

    /** Milliseconds until the kick, or -1 when no kick is counting down. */
    long untilKick(long now, Timing timing) {
        if (!this.afk || this.outsideSince < 0 || timing.kickAfterMillis() == 0) {
            return -1;
        }
        return Math.max(0, timing.kickAfterMillis() - (now - this.outsideSince));
    }
}
