package net.siftvanilla.siftcore.feature.crates;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * How reward chances are shown. Chances are rounded to hundredths of a percent with the largest remainder method,
 * so the numbers a player sees always add up to exactly 100%. A chance too small to show reads {@code <0.01%}.
 * Pure logic.
 */
final class Chances {

    /** 100% in hundredths of a percent. */
    static final long WHOLE = 10_000;

    private Chances() {
    }

    /**
     * The chance of each weight in hundredths of a percent ({@code 1250} is 12.5%), adding up to exactly
     * {@link #WHOLE}. Every weight must be positive.
     */
    static long[] hundredths(double[] weights) {
        if (weights.length == 0) {
            return new long[0];
        }
        double total = 0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException("Weights must be positive, got " + weight);
            }
            total += weight;
        }
        long[] result = new long[weights.length];
        double[] remainders = new double[weights.length];
        long assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            double exact = weights[i] / total * WHOLE;
            long floor = (long) Math.floor(exact);
            result[i] = floor;
            remainders[i] = exact - floor;
            assigned += floor;
        }
        long left = WHOLE - assigned;
        Integer[] order = IntStream.range(0, weights.length).boxed().toArray(Integer[]::new);
        // Largest remainder first; ties go to the earlier entry so the result is stable.
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> -remainders[i]).thenComparingInt(i -> i));
        for (int k = 0; k < left; k++) {
            result[order[k % order.length]]++;
        }
        return result;
    }

    /**
     * Formats hundredths of a percent: {@code 1250} is {@code 12.5%}, {@code 5} is {@code 0.05%}, {@code 10000} is
     * {@code 100%}. Zero is {@code <0.01%} because a reward in a table always has some chance.
     */
    static String format(long hundredths) {
        if (hundredths <= 0) {
            return "<0.01%";
        }
        return BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString() + "%";
    }
}
