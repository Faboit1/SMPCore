package net.siftvanilla.siftcore.feature.afk;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether something a player did is genuine activity, from what their client sent. Pure logic, one instance
 * per player, not thread-safe (its owner {@link PlayerAfk} serialises the calls).
 * <p>
 * Genuine: looking around (the view turned at least the look threshold since the last counted look), walking or
 * flying to a block column the player has not been in recently, and chat, commands and interactions that differ
 * from the player's last few.
 * <p>
 * Not genuine, so it never keeps a player from going AFK:
 * <ul>
 *   <li>movement while in water or lava (streams, bubble columns), in a vehicle (minecarts, boats), right after
 *   damage or a velocity change (knockback), or a step too long to walk (server corrections);</li>
 *   <li>vertical-only movement and moves inside one block column (jump macros);</li>
 *   <li>walking back into recently visited columns (pacing back and forth, circling a fixed path, piston loops), and
 *   walking more than {@link Settings#straightLimit()} new columns without a counted look in between (auto-walk,
 *   flying machines, conveyors);</li>
 *   <li>turning by exactly the same amount packet after packet (circle macros), or by two fixed amounts that cancel
 *   out (jitter macros);</li>
 *   <li>looks that alternate between two directions, or cycle through the same few directions in a fixed order
 *   (back-and-forth turning macros);</li>
 *   <li>view changes a vehicle caused (a boat turning its passenger);</li>
 *   <li>a chat line, command or interaction identical to one of the player's last few (auto-clickers, repeated
 *   macro commands), or the same cycle of up to ten of them three times in a row (a macro clicking through a few
 *   slots, scrolling through the hotbar or rotating a few commands).</li>
 * </ul>
 * Randomised anti-AFK scripts can still look human to these rules; {@link AfkClock} limits how long motion alone
 * keeps a player active.
 * <p>
 * Paper reports movement in steps: a {@code PlayerMoveEvent} fires once the player moved more than 1/16 block or
 * turned more than 10 degrees (yaw plus pitch) since the last one, and its "from" is where the last one ended. So a
 * {@link Move} is one such step, and a turn alone is at least 10 degrees; the look threshold matters below that only
 * while the player also moves.
 */
final class ActivityClassifier {

    /** The outcome of one observation. Everything except {@link #ACTIVE} leaves the AFK timer alone. */
    enum Verdict {
        /** Genuine activity. */
        ACTIVE,
        /** Nothing worth judging changed (no turn, no new column). */
        NONE,
        /** Moved by something else: liquid, a vehicle, knockback, or a step too long to walk. */
        PUSHED,
        /** Walked into a column visited recently, or too far in a straight line without looking around. */
        REVISIT,
        /** Turned less than the look threshold so far (still accumulating). */
        SMALL_TURN,
        /** Turned at a perfectly steady rate, as only a macro does. */
        MECHANICAL,
        /** Looked back and forth between the same few directions, or did the same few things, in a repeating order. */
        REPETITIVE,
        /** The same chat line, command or interaction as one of the last few. */
        REPEATED
    }

    /**
     * Tuning.
     *
     * @param lookThreshold   degrees the view has to turn to count as looking around
     * @param pathMemory      how many recently visited block columns are remembered
     * @param straightLimit   new columns that count as walking before the player has to look around again
     * @param maxStep         the longest horizontal move in one step that counts as walking or flying
     * @param maxGlideStep    the same while gliding with an elytra
     * @param pushedMillis    how long after damage or a velocity change movement is not the player's own
     */
    record Settings(double lookThreshold, int pathMemory, int straightLimit, double maxStep, double maxGlideStep,
                    long pushedMillis) {

        static final Settings DEFAULTS = new Settings(5.0, 256, 32, 1.5, 4.5, 1_500);

        Settings {
            if (!(lookThreshold > 0) || pathMemory < 8 || straightLimit < 1 || !(maxStep > 0) || !(maxGlideStep > 0)
                || pushedMillis < 0) {
                throw new IllegalArgumentException("Invalid activity settings");
            }
        }
    }

    /**
     * One movement packet as the server saw it.
     *
     * @param vehicleYaw the yaw of the vehicle carrying the player (a boat, a minecart), or NaN on foot and on a mount
     *                   the player steers (a horse moves where its rider looks and walks)
     * @param inLiquid   in water (bubble columns are water) or lava
     * @param gliding    gliding with an elytra
     * @param riptiding  riptiding with a trident
     */
    record Move(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                float fromYaw, float fromPitch, float toYaw, float toPitch,
                float vehicleYaw, boolean inLiquid, boolean gliding, boolean riptiding, long now) {

        boolean inVehicle() {
            return !Float.isNaN(this.vehicleYaw);
        }
    }

    /** Raw per-packet turns kept for the mechanical-turning check. */
    static final int TURN_WINDOW = 10;
    /** Recent looks kept for the back-and-forth check. */
    static final int FEW_WINDOW = 8;
    /** Recent changes of direction kept for the repeating-cycle check. */
    static final int CHANGE_WINDOW = 24;
    /** Cycle lengths (in changes of direction) the repeating-cycle check tries; each must repeat three times. */
    static final int MAX_PERIOD = 8;
    /** Recent chat lines, commands and interactions compared against. */
    static final int RECENT_ACTIONS = 4;
    /** Longest cycle of chat lines, commands or interactions the repeating-cycle check tries (a scroll through the hotbar). */
    static final int ACTION_MAX_PERIOD = 10;
    /** Recent chat lines, commands and interactions kept for the repeating-cycle check. */
    static final int ACTION_WINDOW = ACTION_MAX_PERIOD * 3;
    private static final double TURN_EPSILON = 1e-3;
    private static final double STEADY_TOLERANCE = 0.02;
    private static final double STEADY_MINIMUM = 0.05;
    private static final double CANCEL_TOLERANCE = 1.0;
    private static final double BUCKET_DEGREES = 15.0;

    private final Settings settings;

    // view
    private boolean hasView;
    private double viewYaw;
    private double viewPitch;
    private double anchorYaw;
    private double anchorPitch;
    private float lastVehicleYaw = Float.NaN;
    private final double[] turnYaw = new double[TURN_WINDOW];
    private final double[] turnPitch = new double[TURN_WINDOW];
    private int turns;
    private final int[] looks = new int[FEW_WINDOW];
    private int lookCount;
    private final int[] changes = new int[CHANGE_WINDOW];
    private int changeCount;

    // path
    private final ArrayDeque<Long> pathOrder = new ArrayDeque<>();
    private final Set<Long> visited = new HashSet<>();
    private int straight;
    private long pushedUntil;

    // repeated actions
    private final Actions chat = new Actions();
    private final Actions commands = new Actions();
    private final Actions interactions = new Actions();

    ActivityClassifier(Settings settings) {
        this.settings = settings;
    }

    Settings settings() {
        return this.settings;
    }

    /** The player took damage or had their velocity set: movement right after is not their own. */
    void pushed(long now) {
        this.pushedUntil = Math.max(this.pushedUntil, now + this.settings.pushedMillis());
    }

    /**
     * Forgets the view baseline, after a change of world: the next packet starts from wherever the server put the
     * player. The path memory is kept.
     */
    void rebase() {
        this.hasView = false;
        this.lastVehicleYaw = Float.NaN;
        this.turns = 0;
    }

    /** Judges one movement packet: its turn and its position change; active if either one is. */
    Verdict move(Move move) {
        Verdict turn = turn(move);
        Verdict walk = walk(move);
        if (turn == Verdict.ACTIVE || walk == Verdict.ACTIVE) {
            return Verdict.ACTIVE;
        }
        return turn != Verdict.NONE ? turn : walk;
    }

    // ------------------------------------------------------------------ looking around

    private Verdict turn(Move move) {
        double dYaw = wrap(move.toYaw() - move.fromYaw());
        double dPitch = move.toPitch() - move.fromPitch();
        if (move.inVehicle()) {
            // A boat turns its passenger with it; a minecart does not. Take whichever reading attributes the most of
            // the turn to the vehicle, so a vehicle can never make a player look active.
            double vehicleTurn = Float.isNaN(this.lastVehicleYaw) ? 0 : wrap(move.vehicleYaw() - this.lastVehicleYaw);
            this.lastVehicleYaw = move.vehicleYaw();
            double relative = wrap(dYaw - vehicleTurn);
            if (Math.abs(relative) < Math.abs(dYaw)) {
                dYaw = relative;
            }
        } else {
            this.lastVehicleYaw = Float.NaN;
        }
        if (!this.hasView) {
            // The first packet's starting view is where the server last put the player.
            this.hasView = true;
            this.viewYaw = wrap(move.fromYaw());
            this.viewPitch = move.fromPitch();
            this.anchorYaw = this.viewYaw;
            this.anchorPitch = this.viewPitch;
        }
        if (Math.abs(dYaw) < TURN_EPSILON && Math.abs(dPitch) < TURN_EPSILON) {
            return Verdict.NONE;
        }
        this.viewYaw = wrap(this.viewYaw + dYaw);
        this.viewPitch = move.toPitch();
        recordTurn(dYaw, dPitch);
        double turned = Math.max(Math.abs(wrap(this.viewYaw - this.anchorYaw)), Math.abs(this.viewPitch - this.anchorPitch));
        if (turned < this.settings.lookThreshold()) {
            return Verdict.SMALL_TURN;
        }
        this.anchorYaw = this.viewYaw;
        this.anchorPitch = this.viewPitch;
        if (mechanical()) {
            return Verdict.MECHANICAL;
        }
        recordLook(bucket(this.viewYaw, this.viewPitch));
        if (repeating()) {
            return Verdict.REPETITIVE;
        }
        this.straight = 0;
        return Verdict.ACTIVE;
    }

    private void recordTurn(double dYaw, double dPitch) {
        int index = this.turns % TURN_WINDOW;
        this.turnYaw[index] = dYaw;
        this.turnPitch[index] = dPitch;
        this.turns++;
    }

    /**
     * True when the last {@link #TURN_WINDOW} turns came from a script: all the same to within a hundredth of a degree
     * (turning at a steady rate, like a circle macro), or two fixed turns that cancel out (turning back and forth by a
     * fixed amount around one spot, like a jitter macro). Mouse movement by hand is never that regular for ten
     * packets in a row.
     */
    private boolean mechanical() {
        if (this.turns < TURN_WINDOW) {
            return false;
        }
        double firstYaw = this.turnYaw[0];
        double firstPitch = this.turnPitch[0];
        double otherYaw = Double.NaN;
        double otherPitch = Double.NaN;
        double sumYaw = 0;
        double sumPitch = 0;
        for (int i = 0; i < TURN_WINDOW; i++) {
            double yaw = this.turnYaw[i];
            double pitch = this.turnPitch[i];
            sumYaw += yaw;
            sumPitch += pitch;
            if (near(yaw, pitch, firstYaw, firstPitch)) {
                continue;
            }
            if (Double.isNaN(otherYaw)) {
                otherYaw = yaw;
                otherPitch = pitch;
            } else if (!near(yaw, pitch, otherYaw, otherPitch)) {
                return false;
            }
        }
        if (Double.isNaN(otherYaw)) {
            return Math.abs(firstYaw) + Math.abs(firstPitch) >= STEADY_MINIMUM;
        }
        return Math.abs(sumYaw) <= CANCEL_TOLERANCE && Math.abs(sumPitch) <= CANCEL_TOLERANCE;
    }

    private static boolean near(double yaw, double pitch, double otherYaw, double otherPitch) {
        return Math.abs(yaw - otherYaw) <= STEADY_TOLERANCE && Math.abs(pitch - otherPitch) <= STEADY_TOLERANCE;
    }

    private void recordLook(int bucket) {
        this.looks[this.lookCount % FEW_WINDOW] = bucket;
        this.lookCount++;
        if (this.changeCount == 0 || change(0) != bucket) {
            this.changes[this.changeCount % CHANGE_WINDOW] = bucket;
            this.changeCount++;
        }
    }

    /** The {@code back}-th most recent look (0 = the newest). */
    private int look(int back) {
        return this.looks[Math.floorMod(this.lookCount - 1 - back, FEW_WINDOW)];
    }

    /** The {@code back}-th most recent change of direction (0 = the newest). */
    private int change(int back) {
        return this.changes[Math.floorMod(this.changeCount - 1 - back, CHANGE_WINDOW)];
    }

    /**
     * True when the recent looks keep going back and forth between the same directions: the last {@link #FEW_WINDOW}
     * looks alternate between at most two directions, or the last changes of direction repeat one cycle of up to
     * {@link #MAX_PERIOD} directions three times in a row. A player scanning slowly stays in one direction for several
     * looks before moving on, so neither rule catches them; a macro snapping between fixed directions trips both.
     */
    private boolean repeating() {
        if (this.lookCount >= FEW_WINDOW) {
            Set<Integer> distinct = new HashSet<>();
            int switches = 0;
            for (int i = 0; i < FEW_WINDOW; i++) {
                distinct.add(look(i));
                if (i > 0 && look(i) != look(i - 1)) {
                    switches++;
                }
            }
            if (distinct.size() <= 2 && switches >= FEW_WINDOW - 2) {
                return true;
            }
        }
        return periodic(this.changes, this.changeCount, MAX_PERIOD);
    }

    /**
     * True when the newest values of a ring buffer repeat one cycle of 2 to {@code maxPeriod} values three times in a
     * row (the ring must hold at least {@code 3 * maxPeriod} values).
     *
     * @param count how many values were ever added (the newest is at {@code (count - 1) % ring.length})
     */
    static boolean periodic(int[] ring, int count, int maxPeriod) {
        for (int period = 2; period <= maxPeriod; period++) {
            int span = period * 3;
            if (Math.min(count, ring.length) < span) {
                break;
            }
            boolean periodic = true;
            for (int i = 0; i + period < span; i++) {
                if (ring[Math.floorMod(count - 1 - i, ring.length)] != ring[Math.floorMod(count - 1 - i - period, ring.length)]) {
                    periodic = false;
                    break;
                }
            }
            if (periodic) {
                return true;
            }
        }
        return false;
    }

    /** A direction bucket of 15 by 15 degrees. */
    static int bucket(double yaw, double pitch) {
        int yawBucket = Math.floorMod((int) Math.round(wrap(yaw) / BUCKET_DEGREES), (int) (360 / BUCKET_DEGREES));
        int pitchBucket = (int) Math.round(Math.clamp(pitch, -90.0, 90.0) / BUCKET_DEGREES) + 6;
        return yawBucket * 13 + pitchBucket;
    }

    // ------------------------------------------------------------------ walking

    private Verdict walk(Move move) {
        double dx = move.toX() - move.fromX();
        double dz = move.toZ() - move.fromZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        long from = column(move.fromX(), move.fromZ());
        long to = column(move.toX(), move.toZ());
        // Wherever the player stands is somewhere they have been, however they got there.
        remember(from);
        if (horizontal < 1e-6) {
            return Verdict.NONE;
        }
        double maxStep = move.gliding() ? this.settings.maxGlideStep() : this.settings.maxStep();
        boolean pushed = move.inVehicle() || move.inLiquid() || move.riptiding() || move.now() < this.pushedUntil
            || horizontal > maxStep;
        if (pushed) {
            remember(to);
            return Verdict.PUSHED;
        }
        if (to == from) {
            return Verdict.NONE;
        }
        if (this.visited.contains(to)) {
            return Verdict.REVISIT;
        }
        remember(to);
        if (this.straight >= this.settings.straightLimit()) {
            return Verdict.REVISIT;
        }
        this.straight++;
        return Verdict.ACTIVE;
    }

    private void remember(long column) {
        if (!this.visited.add(column)) {
            return;
        }
        this.pathOrder.addLast(column);
        while (this.pathOrder.size() > this.settings.pathMemory()) {
            this.visited.remove(this.pathOrder.removeFirst());
        }
    }

    static long column(double x, double z) {
        long bx = (long) Math.floor(x);
        long bz = (long) Math.floor(z);
        return (bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ chat, commands, interactions

    /** A chat line. Repeating one of the last few lines, or cycling through the same few, is not activity. */
    Verdict chat(String message) {
        return this.chat.judge(normalize(message));
    }

    /** A command line (without the slash). Repeating or cycling through the same few commands is not activity. */
    Verdict command(String command) {
        return this.commands.judge(normalize(command));
    }

    /**
     * An interaction: using a block, an entity or a menu, attacking, changing the held item. {@code signature} identifies
     * what was done and where (the caller includes the target position and the view direction), so an auto-clicker on
     * one spot repeats the same signature, and a macro that clicks through a few slots or scrolls through the hotbar
     * repeats the same cycle.
     */
    Verdict interaction(String signature) {
        return this.interactions.judge(signature);
    }

    /** The recent chat lines, commands or interactions of one kind. */
    private static final class Actions {
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        private final int[] history = new int[ACTION_WINDOW];
        private int count;

        /**
         * {@link Verdict#REPEATED} for one of the last {@link #RECENT_ACTIONS}, {@link Verdict#REPETITIVE} when the
         * last ones repeat a cycle of up to {@link #ACTION_MAX_PERIOD} three times, else {@link Verdict#ACTIVE}.
         */
        Verdict judge(String value) {
            this.history[this.count % ACTION_WINDOW] = value.hashCode();
            this.count++;
            if (this.recent.contains(value)) {
                return Verdict.REPEATED;
            }
            this.recent.addLast(value);
            while (this.recent.size() > RECENT_ACTIONS) {
                this.recent.removeFirst();
            }
            return periodic(this.history, this.count, ACTION_MAX_PERIOD) ? Verdict.REPETITIVE : Verdict.ACTIVE;
        }
    }

    private static String normalize(String text) {
        String trimmed = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
    }

    /** Wraps an angle to [-180, 180). */
    static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }
}
