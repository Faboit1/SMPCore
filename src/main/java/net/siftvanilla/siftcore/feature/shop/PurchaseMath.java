package net.siftvanilla.siftcore.feature.shop;

import java.util.OptionalInt;
import java.util.OptionalLong;

/** Quantities, totals and inventory space for purchases. Pure logic; overflow is reported, never wrapped. */
public final class PurchaseMath {

    private PurchaseMath() {
    }

    /**
     * The total of {@code quantity} items at {@code unitPrice} each, or empty when it overflows or is above
     * {@code maxAmount} (the money limit).
     */
    public static OptionalLong total(long unitPrice, long quantity, long maxAmount) {
        if (unitPrice < 1 || quantity < 1) {
            return OptionalLong.empty();
        }
        try {
            long total = Math.multiplyExact(unitPrice, quantity);
            return total > maxAmount ? OptionalLong.empty() : OptionalLong.of(total);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** A typed quantity: a whole number from 1 to {@code max} (grouping commas and spaces allowed). */
    public static OptionalInt parseQuantity(String text, int max) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String clean = text.strip().replace(",", "").replace("_", "").replace(" ", "");
        if (clean.isEmpty() || clean.length() > 9 || !clean.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(clean);
        return value >= 1 && value <= max ? OptionalInt.of(value) : OptionalInt.empty();
    }

    /** True when {@code quantity} is a valid purchase size. */
    public static boolean validQuantity(long quantity, int max) {
        return quantity >= 1 && quantity <= max;
    }

    /**
     * The amount a purchase dialog asks for: the typed amount when something was typed (it must be valid), else the
     * slider's value. Empty when the input is not a valid amount.
     */
    public static OptionalInt chosenAmount(long slider, String typed, int max) {
        if (typed != null && !typed.isBlank()) {
            return parseQuantity(typed, max);
        }
        return validQuantity(slider, max) ? OptionalInt.of((int) slider) : OptionalInt.empty();
    }

    /** The amount a purchase dialog starts at: one stack, or less when a purchase is capped lower. */
    public static int defaultAmount(int stackSize, int max) {
        return Math.max(1, Math.min(Math.max(1, stackSize), max));
    }

    /**
     * How many more items of one kind fit: the free space in stacks of the same item plus whole empty slots.
     *
     * @param partialAmounts the amounts of the stacks that already hold this exact item
     * @param emptySlots     the number of empty slots
     * @param maxStack       the item's maximum stack size
     */
    public static long capacity(int[] partialAmounts, int emptySlots, int maxStack) {
        if (maxStack < 1 || emptySlots < 0) {
            throw new IllegalArgumentException("Invalid inventory shape");
        }
        long space = (long) emptySlots * maxStack;
        for (int amount : partialAmounts) {
            space += Math.max(0, maxStack - amount);
        }
        return space;
    }

    /**
     * "Max you can afford": the most of one item {@code balance} pays for at {@code unitPrice} each, capped at the
     * purchase limit {@code max}; 0 when not even one is affordable.
     */
    public static int affordable(long balance, long unitPrice, int max) {
        if (unitPrice < 1 || max < 1 || balance < unitPrice) {
            return 0;
        }
        return (int) Math.min(max, balance / unitPrice);
    }

    /** "Fill your inventory": as many as fit ({@code capacity}), capped at the purchase limit; 0 when nothing fits. */
    public static int fill(long capacity, int max) {
        if (capacity < 1 || max < 1) {
            return 0;
        }
        return (int) Math.min(max, capacity);
    }

    /** How a purchase of {@code quantity} splits: [into the inventory, into the claim box]. */
    public static int[] split(int quantity, long capacity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Negative quantity");
        }
        int inventory = (int) Math.min(quantity, Math.max(0, capacity));
        return new int[] {inventory, quantity - inventory};
    }
}
