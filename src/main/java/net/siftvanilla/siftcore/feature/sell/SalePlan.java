package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What the server pays for a sale: the sold items grouped by item, in the order they were found, with exact
 * totals. The multiplier of each sell category (rank plus mastery) applies to that category's total, which is
 * rounded down once. Pure logic; every overflow throws {@link ArithmeticException} instead of wrapping.
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
     * @param category  the item's sell category
     */
    public record Line(String item, long amount, long unitPrice, String category) {
        public Line {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(category, "category");
            if (amount < 1 || unitPrice < 1) {
                throw new IllegalArgumentException("A sale line sells at least one item worth at least $1");
            }
        }

        /** A line in the fallback category. */
        public Line(String item, long amount, long unitPrice) {
            this(item, amount, unitPrice, SellCategories.OTHER);
        }

        /** Amount times unit price, exactly. */
        public long value() {
            return Math.multiplyExact(this.amount, this.unitPrice);
        }
    }

    private final List<Line> lines;
    private final Map<String, Long> categoryBase;
    private final long baseTotal;
    private final long itemCount;

    private SalePlan(List<Line> lines, Map<String, Long> categoryBase, long baseTotal, long itemCount) {
        this.lines = List.copyOf(lines);
        this.categoryBase = Map.copyOf(categoryBase);
        this.baseTotal = baseTotal;
        this.itemCount = itemCount;
    }

    /**
     * Groups stacks into one line per item. Stacks of the same item must have the same unit price and category
     * (they come from one worth table).
     *
     * @throws ArithmeticException      when a total does not fit in a long
     * @throws IllegalArgumentException when one item has two different prices or categories
     */
    public static SalePlan of(List<Line> stacks) {
        Map<String, Line> merged = new LinkedHashMap<>();
        for (Line stack : stacks) {
            Line known = merged.get(stack.item());
            if (known == null) {
                merged.put(stack.item(), stack);
                continue;
            }
            if (known.unitPrice() != stack.unitPrice()) {
                throw new IllegalArgumentException(stack.item() + " has two prices in one sale");
            }
            if (!known.category().equals(stack.category())) {
                throw new IllegalArgumentException(stack.item() + " has two categories in one sale");
            }
            merged.put(stack.item(), new Line(known.item(), Math.addExact(known.amount(), stack.amount()),
                known.unitPrice(), known.category()));
        }
        List<Line> lines = new ArrayList<>(merged.values());
        Map<String, Long> categories = new LinkedHashMap<>();
        long total = 0;
        long count = 0;
        for (Line line : lines) {
            long value = line.value();
            total = Math.addExact(total, value);
            count = Math.addExact(count, line.amount());
            categories.merge(line.category(), value, Math::addExact);
        }
        return new SalePlan(lines, categories, total, count);
    }

    /** One line per item kind, in the order they were found. */
    public List<Line> lines() {
        return this.lines;
    }

    /** The base value sold per category, in the order the categories were found. */
    public Map<String, Long> categoryBase() {
        return this.categoryBase;
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

    /** What the player receives with one multiplier for everything; each category is rounded down once. */
    public long total(double multiplier) {
        BigDecimal exact = BigDecimal.valueOf(multiplier);
        return total(category -> exact);
    }

    /**
     * What the player receives: each category's base value times its multiplier, rounded down once per category,
     * summed.
     *
     * @throws ArithmeticException on overflow
     */
    public long total(Function<String, BigDecimal> multiplierOf) {
        long total = 0;
        for (Map.Entry<String, Long> category : this.categoryBase.entrySet()) {
            total = Math.addExact(total, SaleMath.withMultiplier(category.getValue(), multiplierOf.apply(category.getKey())));
        }
        return total;
    }

    /** One category's part of {@link #total(Function)}. */
    public long categoryTotal(String category, BigDecimal multiplier) {
        return SaleMath.withMultiplier(this.categoryBase.getOrDefault(category, 0L), multiplier);
    }

    /**
     * A short description for the ledger: {@code 64 minecraft:diamond, 32 minecraft:iron_ingot}, cut to the ledger's
     * limit with a count of what did not fit.
     */
    public String note() {
        return note(this.lines, "");
    }

    /**
     * A note with an ending such as {@code (312 from shulker boxes)}; the item list is cut so that the ending always
     * fits.
     */
    public static String note(List<Line> lines, String ending) {
        int limit = NOTE_LIMIT - ending.length();
        StringBuilder note = new StringBuilder();
        int size = lines.size();
        for (int i = 0; i < size; i++) {
            Line line = lines.get(i);
            String separator = i == 0 ? "" : ", ";
            String part = separator + line.amount() + " " + line.item();
            int after = size - i - 1;
            String suffix = after == 0 ? "" : ", +" + after + " more";
            if (note.length() + part.length() + suffix.length() > limit) {
                // The previous step made sure this always fits.
                note.append(separator).append('+').append(size - i).append(" more");
                break;
            }
            note.append(part);
        }
        if (!ending.isEmpty()) {
            note.append(note.isEmpty() ? ending.strip() : ending);
        }
        return note.toString();
    }
}
