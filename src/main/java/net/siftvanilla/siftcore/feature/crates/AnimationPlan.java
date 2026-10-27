package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The timing and the reel of an opening animation, decided up front (pure, unit tested). The reel is a row of reward
 * icons that moves one slot left per step; the window shows nine of them and the middle one is the pointer's. Steps
 * start a tick apart and slow down towards the end (an ease-out), and the last step brings the reward that was won
 * under the pointer. What was won is decided and stored before the plan is made: the plan only shows it.
 *
 * @param delays ticks before each step (the first step comes {@code delays[0]} ticks after the start)
 * @param reel   reward indexes, {@code steps + WINDOW} long; at step {@code s} the window shows
 *               {@code reel[s .. s + WINDOW - 1]} and the pointer {@code reel[s + POINTER]}
 */
record AnimationPlan(int[] delays, int[] reel) {

    /** Slots the window shows at once. */
    static final int WINDOW = 9;
    /** The pointer's place in the window (the middle). */
    static final int POINTER = 4;
    /** The longest pause between two steps, at the very end. */
    static final int SLOWEST = 10;

    AnimationPlan {
        delays = delays.clone();
        reel = reel.clone();
        if (reel.length != delays.length + WINDOW) {
            throw new IllegalArgumentException("The reel must be the steps plus one window long");
        }
    }

    int steps() {
        return this.delays.length;
    }

    /** The reward index under the pointer at step {@code step} (0 is the start, {@link #steps()} the end). */
    int pointed(int step) {
        return this.reel[step + POINTER];
    }

    /** The reward index in window slot {@code slot} (0 to 8) at step {@code step}. */
    int shown(int step, int slot) {
        return this.reel[step + slot];
    }

    /** How far the roll has come at step {@code step}, from 0 to 1 (for the tick sound's rising pitch). */
    double progress(int step) {
        return this.delays.length == 0 ? 1 : Math.min(1.0, step / (double) this.delays.length);
    }

    /** Total ticks of the roll. */
    int ticks() {
        int total = 0;
        for (int delay : this.delays) {
            total += delay;
        }
        return total;
    }

    /**
     * Ticks between the steps of a roll lasting about {@code lengthTicks}: one tick apart at first, slowing down with
     * the cube of the time passed to {@link #SLOWEST} ticks at the end. At least one step.
     */
    static int[] delays(int lengthTicks) {
        int length = Math.max(1, lengthTicks);
        List<Integer> delays = new ArrayList<>();
        int elapsed = 0;
        while (elapsed < length) {
            double progress = elapsed / (double) length;
            int delay = 1 + (int) Math.floor(progress * progress * progress * (SLOWEST - 1));
            delays.add(delay);
            elapsed += delay;
        }
        int[] result = new int[delays.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = delays.get(i);
        }
        return result;
    }

    /**
     * A plan for a roll of about {@code lengthTicks} ending on {@code won}: every other place in the reel is drawn by
     * weight from {@code weights} (so likely rewards pass by more often, as they would), and the reward right after
     * the won one is a rarer one when there is one, for a near miss.
     *
     * @param weights the weights of the rewards that can be won, by index
     * @param won     the index of the reward that was won
     */
    static AnimationPlan of(int lengthTicks, double[] weights, int won, RandomGenerator random) {
        int[] delays = delays(lengthTicks);
        int[] reel = new int[delays.length + WINDOW];
        WeightedTable<Integer> table = WeightedTable.of(indexes(weights.length), i -> weights[i]);
        for (int i = 0; i < reel.length; i++) {
            reel[i] = table.pick(random);
        }
        int end = delays.length + POINTER;
        reel[end] = won;
        int rarest = rarest(weights);
        if (end + 1 < reel.length && rarest != won) {
            reel[end + 1] = rarest;
        }
        return new AnimationPlan(delays, reel);
    }

    private static List<Integer> indexes(int size) {
        List<Integer> indexes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    /** The index with the smallest weight (the first of them). */
    static int rarest(double[] weights) {
        int rarest = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] < weights[rarest]) {
                rarest = i;
            }
        }
        return rarest;
    }
}
