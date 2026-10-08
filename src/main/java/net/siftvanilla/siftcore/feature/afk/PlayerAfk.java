package net.siftvanilla.siftcore.feature.afk;

import java.util.UUID;

/**
 * Everything the AFK feature tracks about one online player: their activity classifier and AFK clock, whether they
 * are in the AFK zone, and today's zone earnings. Events reach it from the player's region thread (movement,
 * interactions), the chat thread and the victim's thread (attacks), so every method is synchronized; the flags read
 * by placeholders and other features are volatile so those reads never block.
 */
final class PlayerAfk {

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
        return publish(this.clock.activity(now, motion, timing));
    }

    // ------------------------------------------------------------------ clock

    synchronized AfkClock.Change goAfk(long now, AfkClock.Timing timing) {
        return publish(this.clock.goAfk(now, timing));
    }

    synchronized AfkClock.Change comeBack(long now) {
        return publish(this.clock.comeBack(now));
    }

    synchronized AfkClock.Change tick(long now, AfkClock.Timing timing, boolean kickable) {
        return publish(this.clock.tick(now, timing, this.inZone, kickable));
    }

    private AfkClock.Change publish(AfkClock.Change change) {
        this.afk = this.clock.afk();
        this.afkSince = this.clock.afkSince();
        return change;
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
