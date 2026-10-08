package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a sale pays: the sold stacks grouped by item, in the order they were found, with exact totals. Pure logic;
 * every overflow throws {@link ArithmeticException} instead of wrapping.
 */
public final class SalePlan {

    /** The longest ledger note a sale writes (the ledger column holds 255 characters). */
    public static final int NOTE_LIMIT = 255;

    /**
     * One item kind of a sale.
     *
     * @param item      item key, e.g. {@code minecraft:diamond}
     * @param amount    how many are sold
     * @param unitPrice what one sells for, before the multiplier
     */
    public record Line(String item, long amount, long unitPrice) {
        public Line {
            Objects.requireNonNull(item, "item");
            if (amount < 1 || unitPrice < 1) {
                throw new IllegalArgumentException("A sale line sells at least one item worth at least $1");
            }
        }

        /** Amount times unit price, exactly. */
        public long value() {
            return Math.multiplyExact(this.amount, this.unitPrice);
        }
    }

    private final List<Line> lines;
    private final long baseTotal;
    private final long itemCount;

    private SalePlan(List<Line> lines, long baseTotal, long itemCount) {
        this.lines = List.copyOf(lines);
        this.baseTotal = baseTotal;
        this.itemCount = itemCount;
    }

    /**
     * Groups stacks into one line per item. Stacks of the same item must have the same unit price (they come from
     * one worth table).
     *
     * @throws ArithmeticException      when a total does not fit in a long
     * @throws IllegalArgumentException when one item has two different prices
     */
    public static SalePlan of(List<Line> stacks) {
        Map<String, long[]> merged = new LinkedHashMap<>();
        for (Line stack : stacks) {
            long[] entry = merged.computeIfAbsent(stack.item(), k -> new long[] {0, stack.unitPrice()});
            if (entry[1] != stack.unitPrice()) {
                throw new IllegalArgumentException(stack.item() + " has two prices in one sale");
            }
            entry[0] = Math.addExact(entry[0], stack.amount());
        }
        List<Line> lines = new ArrayList<>(merged.size());
        long total = 0;
        long count = 0;
        for (Map.Entry<String, long[]> entry : merged.entrySet()) {
            Line line = new Line(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            total = Math.addExact(total, line.value());
            count = Math.addExact(count, line.amount());
            lines.add(line);
        }
        return new SalePlan(lines, total, count);
    }

    /** One line per item kind, in the order they were found. */
    public List<Line> lines() {
        return this.lines;
    }

    /** What the items are worth before the multiplier. */
    public long baseTotal() {
        return this.baseTotal;
    }

    /** How many items are sold in total. */
    public long itemCount() {
        return this.itemCount;
    }

    public boolean isEmpty() {
        return this.lines.isEmpty();
    }

    /** True when every sold item is the same kind. */
    public boolean singleKind() {
        return this.lines.size() == 1;
    }

    /** What the player receives with their multiplier, rounded down. */
    public long total(double multiplier) {
        return SaleMath.withMultiplier(this.baseTotal, multiplier);
    }

    /**
     * A short description for the ledger: {@code 64 minecraft:diamond, 32 minecraft:iron_ingot}, cut to the ledger's
     * limit with a count of what did not fit.
     */
    public String note() {
        StringBuilder note = new StringBuilder();
        int size = this.lines.size();
        for (int i = 0; i < size; i++) {
            Line line = this.lines.get(i);
            String separator = i == 0 ? "" : ", ";
            String part = separator + line.amount() + " " + line.item();
            int after = size - i - 1;
            String suffix = after == 0 ? "" : ", +" + after + " more";
            if (note.length() + part.length() + suffix.length() > NOTE_LIMIT) {
                // The previous step made sure this always fits.
                note.append(separator).append('+').append(size - i).append(" more");
                break;
            }
            note.append(part);
        }
        return note.toString();
    }
}
