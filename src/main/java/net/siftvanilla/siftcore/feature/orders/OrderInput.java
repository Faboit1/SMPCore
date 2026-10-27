package net.siftvanilla.siftcore.feature.orders;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads what players type into the order form. Pure; every rejection says why. */
final class OrderInput {

    /** Why a quantity was refused. */
    enum QuantityError {
        NOT_A_NUMBER,
        OUT_OF_RANGE
    }

    /** A parsed quantity or the reason it was refused. */
    record Quantity(int value, QuantityError error) {
        boolean ok() {
            return this.error == null;
        }
    }

    /** Longest input worth parsing; anything longer is not a quantity or price a player means. */
    static final int MAX_INPUT = 24;

    /** Slots in a shulker box. */
    static final int SHULKER_SLOTS = 27;

    /**
     * A number (grouping commas allowed, an optional fraction) and an optional unit: {@code k} (thousand), {@code m}
     * (million), stacks ({@code st}, {@code stack}, {@code stacks}) or shulker boxes ({@code sb}, {@code shulker},
     * {@code shulkers}), with or without a space before the unit.
     */
    private static final Pattern QUANTITY = Pattern.compile(
        "([0-9][0-9,_]*(?:\\.[0-9]+)?)\\s*(k|m|st|stacks?|sb|shulkers?)?");
    /** Scientific notation ({@code 1e9}, {@code 2E+5}): a few characters for an enormous number. Never accepted. */
    private static final Pattern EXPONENT = Pattern.compile("[0-9.]\\s*[eE]\\s*[+-]?\\s*[0-9]");

    private OrderInput() {
    }

    /**
     * A quantity from 1 to {@code max}: whole numbers with optional grouping ({@code 1,500}), {@code k} and {@code m}
     * ({@code 1.5k} is 1500), stacks ({@code 3 stacks}, {@code 2st}: that many times {@code maxStack}) and shulker
     * boxes ({@code 1 shulker}, {@code 2sb}: 27 stacks each). Exponents, hex and anything else are refused.
     */
    static Quantity quantity(String input, int max, int maxStack) {
        String text = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > MAX_INPUT) {
            return new Quantity(0, QuantityError.NOT_A_NUMBER);
        }
        Matcher matcher = QUANTITY.matcher(text);
        if (!matcher.matches()) {
            return new Quantity(0, QuantityError.NOT_A_NUMBER);
        }
        String digits = matcher.group(1).replace(",", "").replace("_", "");
        BigDecimal value;
        try {
            value = new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return new Quantity(0, QuantityError.NOT_A_NUMBER);
        }
        String unit = matcher.group(2);
        long stack = Math.max(1, maxStack);
        long multiplier = unit == null ? 1 : switch (unit) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "st", "stack", "stacks" -> stack;
            default -> stack * SHULKER_SLOTS;
        };
        value = value.multiply(BigDecimal.valueOf(multiplier));
        if (value.stripTrailingZeros().scale() > 0) {
            return new Quantity(0, QuantityError.NOT_A_NUMBER);
        }
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return new Quantity(0, QuantityError.OUT_OF_RANGE);
        }
        return new Quantity(value.intValueExact(), null);
    }

    /**
     * True when a price text may be handed to the money parser: short, and not written in scientific notation
     * (which the money parser would try to expand digit by digit).
     */
    static boolean priceTextAllowed(String input) {
        return input != null && input.length() <= MAX_INPUT && !EXPONENT.matcher(input).find();
    }
}
