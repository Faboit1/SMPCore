package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * Picks entries with a probability proportional to their weight. Weights are positive and may be fractional; the
 * chance of an entry is its weight divided by the sum of all weights. Immutable and thread-safe. Pure logic.
 *
 * @param <T> the entry type
 */
final class WeightedTable<T> {

    private final List<T> entries;
    private final double[] weights;
    private final double[] cumulative;
    private final double total;

    private WeightedTable(List<T> entries, double[] weights) {
        this.entries = List.copyOf(entries);
        this.weights = weights.clone();
        this.cumulative = new double[weights.length];
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i];
            this.cumulative[i] = sum;
        }
        this.total = sum;
    }

    /**
     * Builds a table. Every weight must be finite and above zero.
     *
     * @throws IllegalArgumentException for an empty list or a weight that is not a positive finite number
     */
    static <T> WeightedTable<T> of(List<T> entries, ToDoubleFunction<T> weight) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A weighted table needs at least one entry");
        }
        double[] weights = new double[entries.size()];
        for (int i = 0; i < weights.length; i++) {
            double value = weight.applyAsDouble(entries.get(i));
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException("Weight " + value + " of entry " + i + " is not a positive number");
            }
            weights[i] = value;
        }
        return new WeightedTable<>(new ArrayList<>(entries), weights);
    }

    List<T> entries() {
        return this.entries;
    }

    int size() {
        return this.entries.size();
    }

    double total() {
        return this.total;
    }

    double weight(int index) {
        return this.weights[index];
    }

    /** The exact chance of the entry at {@code index}, from 0 to 1. */
    double chance(int index) {
        return this.weights[index] / this.total;
    }

    /** Every weight in entry order (a copy). */
    double[] weights() {
        return this.weights.clone();
    }

    /** The index chosen by {@code unit}, a number from 0 (inclusive) to 1 (exclusive). */
    int index(double unit) {
        if (!(unit >= 0) || unit >= 1) {
            throw new IllegalArgumentException("unit must be in [0, 1), got " + unit);
        }
        double target = unit * this.total;
        int low = 0;
        int high = this.cumulative.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (target < this.cumulative[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** The entry chosen by {@code unit}, a number from 0 (inclusive) to 1 (exclusive). */
    T pick(double unit) {
        return this.entries.get(index(unit));
    }

    T pick(RandomGenerator random) {
        return pick(random.nextDouble());
    }
}
