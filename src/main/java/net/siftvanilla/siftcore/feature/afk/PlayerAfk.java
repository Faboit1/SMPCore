package net.siftvanilla.siftcore.feature.afk;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Everything the AFK feature tracks about one online player: their activity classifier and AFK clock, whether they
 * are in the AFK zone, and today's zone earnings. Events reach it from the player's region thread (movement,
 * interactions), the chat thread and the victim's thread (attacks), so every method is synchronized; the flags read
 * by placeholders and other features are volatile so those reads never block.
 */
final class PlayerAfk {

    /**
     * How long a player was away and the zone shards they earned meanwhile (the welcome-back summary). A spell the
     * clock noticed on its own starts at the player's last activity, not at the AFK mark {@code afk-after} later, so it
     * counts the whole time away and the shards paid before the mark; a {@code /afk} spell starts at the command.
     */
    record Spell(long millis, long shards) {

        /** Less than this away and nothing earned is not worth a summary (a quick /afk and back). */
        static final long SHORT_MILLIS = 60_000;

        /** Whether the summary says anything worth reading: shards were earned, or the player was away a while. */
        boolean worthTelling() {
            return this.shards > 0 || this.millis >= SHORT_MILLIS;
        }
    }

    /** Zone shards paid at a moment. */
    private record Payout(long at, long shards) {
    }

    private final UUID player;
    private ActivityClassifier classifier;
    private final AfkClock clock;
    private final DailyCounter daily = new DailyCounter();
    private volatile boolean afk;
    private volatile boolean inZone;
    private volatile long afkSince = -1;
    /** Seconds until the next zone reward, or -1 when not earning. */
    private volatile int nextShardSeconds = -1;
    private long lastStatus;
    private long holdStatusUntil;
    private long lastNotice;
    /** Zone shards earned during the current AFK spell (from {@link #spellStart} on). */
    private long spellShards;
    /** When the current AFK spell started for the summary, or -1 when not AFK. */
    private long spellStart = -1;
    /**
     * Zone payouts since the last activity while not AFK, oldest first: a spell the clock notices later started at
     * that activity, so they count towards it. Bounded: dropped at each activity and when a spell starts, so it
     * holds at most about {@code afk-after} divided by the reward interval.
     */
    private final ArrayDeque<Payout> paidSinceActivity = new ArrayDeque<>();
    /** The spell that ended last, for the welcome-back summary; null before the first one. */
    private Spell lastSpell;

    PlayerAfk(UUID player, ActivityClassifier.Settings settings, long now) {
        this.player = player;
        this.classifier = new ActivityClassifier(settings);
        this.clock = new AfkClock(now);
    }

    UUID player() {
        return this.player;
    }

    DailyCounter daily() {
        return this.daily;
    }

    boolean afk() {
        return this.afk;
    }

    long afkSince() {
        return this.afkSince;
    }

    boolean inZone() {
        return this.inZone;
    }

    int nextShardSeconds() {
        return this.nextShardSeconds;
    }

    void nextShardSeconds(int seconds) {
        this.nextShardSeconds = seconds;
    }

    synchronized boolean manual() {
        return this.clock.manual();
    }

    synchronized long lastActivity(AfkClock.Timing timing) {
        return this.clock.lastActivity(timing);
    }

    synchronized long untilKick(long now, AfkClock.Timing timing) {
        return this.clock.untilKick(now, timing);
    }

    /** Applies new tuning after a reload (the path and look history start over). */
    synchronized void settings(ActivityClassifier.Settings settings) {
        if (!this.classifier.settings().equals(settings)) {
            this.classifier = new ActivityClassifier(settings);
        }
    }

    // ------------------------------------------------------------------ observations

    /** A movement packet: looking around and walking are motion. */
    synchronized AfkClock.Change move(ActivityClassifier.Move move, AfkClock.Timing timing) {
        return active(this.classifier.move(move), move.now(), true, timing);
    }

    synchronized AfkClock.Change chat(String message, long now, AfkClock.Timing timing) {
        return active(this.classifier.chat(message), now, false, timing);
    }

    synchronized AfkClock.Change command(String command, long now, AfkClock.Timing timing) {
        return active(this.classifier.command(command), now, false, timing);
    }

    synchronized AfkClock.Change interaction(String signature, long now, AfkClock.Timing timing) {
        return active(this.classifier.interaction(signature), now, false, timing);
    }

    synchronized void pushed(long now) {
        this.classifier.pushed(now);
    }

    synchronized void rebase() {
        this.classifier.rebase();
    }

    private AfkClock.Change active(ActivityClassifier.Verdict verdict, long now, boolean motion, AfkClock.Timing timing) {
        if (verdict != ActivityClassifier.Verdict.ACTIVE) {
            return AfkClock.Change.NONE;
        }
        AfkClock.Change change = this.clock.activity(now, motion, timing);
        forgetPaidBefore(this.clock.lastActivity(timing));
        return publish(change, now, now);
    }

    /** Payouts before the last activity belong to no spell the clock could notice later. */
    private void forgetPaidBefore(long lastActivity) {
        while (!this.paidSinceActivity.isEmpty() && this.paidSinceActivity.peekFirst().at() < lastActivity) {
            this.paidSinceActivity.removeFirst();
        }
    }

    // ------------------------------------------------------------------ clock

    /** {@code /afk}: the spell starts now and counts only what is paid from now on. */
    synchronized AfkClock.Change goAfk(long now, AfkClock.Timing timing) {
        return publish(this.clock.goAfk(now, timing), now, now);
    }

    /** {@code /afk} again: back at once (typing the command is an action). */
    synchronized AfkClock.Change comeBack(long now) {
        AfkClock.Change change = this.clock.comeBack(now);
        forgetPaidBefore(now);
        return publish(change, now, now);
    }

    /** The periodic check: a spell it starts began at the last activity ({@code afk-after} ago or longer). */
    synchronized AfkClock.Change tick(long now, AfkClock.Timing timing, boolean kickable) {
        long lastActivity = this.clock.lastActivity(timing);
        return publish(this.clock.tick(now, timing, this.inZone, kickable), now, lastActivity);
    }

    /**
     * Makes the clock's state readable without the lock, and keeps the spell bookkeeping. A spell that starts begins
     * at {@code start}: a {@code /afk} spell counts shards from zero, one the clock noticed takes along the shards
     * paid since the last activity. A spell that ends is remembered for the summary.
     */
    private AfkClock.Change publish(AfkClock.Change change, long now, long start) {
        if (change == AfkClock.Change.BECAME_AFK) {
            boolean manual = this.clock.manual();
            this.spellStart = manual ? now : Math.min(now, start);
            this.spellShards = 0;
            if (!manual) {
                for (Payout payout : this.paidSinceActivity) {
                    if (payout.at() >= this.spellStart) {
                        this.spellShards += payout.shards();
                    }
                }
            }
            this.paidSinceActivity.clear();
        } else if (change == AfkClock.Change.RETURNED) {
            this.lastSpell = spell(this.spellStart, now, this.spellShards);
            this.spellStart = -1;
            this.spellShards = 0;
            this.paidSinceActivity.clear();
        }
        this.afk = this.clock.afk();
        this.afkSince = this.clock.afkSince();
        return change;
    }

    /** The summary of a spell from {@code start} (-1 when unknown) to {@code end}. Pure. */
    static Spell spell(long start, long end, long shards) {
        return new Spell(start < 0 ? 0 : Math.max(0, end - start), shards);
    }

    /**
     * Counts zone shards paid to the player at {@code now}: towards the current AFK spell, or, while they are not
     * AFK, towards a spell the clock may notice later (forgotten at their next activity).
     */
    synchronized void earned(long shards, long now) {
        if (shards <= 0) {
            return;
        }
        if (this.afk) {
            this.spellShards += shards;
        } else {
            this.paidSinceActivity.addLast(new Payout(now, shards));
        }
    }

    /** The spell that ended last, or null. */
    synchronized Spell lastSpell() {
        return this.lastSpell;
    }

    /** Records zone presence; returns true when it changed. */
    synchronized boolean inZone(boolean inside) {
        if (this.inZone == inside) {
            return false;
        }
        this.inZone = inside;
        if (!inside) {
            this.nextShardSeconds = -1;
        }
        return true;
    }

    // ------------------------------------------------------------------ pacing of action-bar text

    /** True (and remembers now) when the zone status line is due again. */
    synchronized boolean statusDue(long now, long everyMillis) {
        if (everyMillis <= 0 || now < this.holdStatusUntil || now - this.lastStatus < everyMillis) {
            return false;
        }
        this.lastStatus = now;
        return true;
    }

    /** Holds the zone status line back for a moment, so a one-off message on the action bar stays readable. */
    synchronized void holdStatus(long now, long millis) {
        this.holdStatusUntil = Math.max(this.holdStatusUntil, now + millis);
    }

    /** True (and remembers now) at most once per {@code everyMillis}; for refusal messages. */
    synchronized boolean noticeDue(long now, long everyMillis) {
        if (now - this.lastNotice < everyMillis) {
            return false;
        }
        this.lastNotice = now;
        return true;
    }
}
