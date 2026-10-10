package net.siftvanilla.siftcore.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Formats and parses whole-number currency amounts. Pure logic, no server dependency.
 * <p>
 * Parsing accepts plain numbers, grouping commas, an optional currency symbol and the suffixes from the
 * config (default k, m, b, t). {@code 1.5k} is 1500. Results that are not whole numbers ({@code 1.2345k}),
 * zero or negative amounts (unless allowed), and values above {@link #maxAmount()} are rejected.
 */
public final class MoneyFormat {

    /** A suffix such as {@code k} meaning one thousand. */
    public record Suffix(String symbol, long multiplier) {
        public Suffix {
            symbol = symbol.toLowerCase(Locale.ROOT);
            if (symbol.isEmpty() || multiplier < 10) {
                throw new IllegalArgumentException("Suffix needs a symbol and a multiplier of at least 10");
            }
        }
    }

    /** Why a parse failed, for precise feedback. */
    public enum ParseError {
        EMPTY, NOT_A_NUMBER, NOT_WHOLE, NOT_POSITIVE, TOO_LARGE
    }

    /** Either a parsed amount or the reason it failed. */
    public record ParseResult(long amount, ParseError error) {
        public boolean ok() {
            return this.error == null;
        }

        static ParseResult of(long amount) {
            return new ParseResult(amount, null);
        }

        static ParseResult fail(ParseError error) {
            return new ParseResult(0, error);
        }
    }

    /** The most decimals the short format players can pick shows ({@code $1.2m}, {@link #formatShort}). */
    public static final int SHORT_DECIMALS = 1;

    public static final List<Suffix> DEFAULT_SUFFIXES = List.of(
        new Suffix("k", 1_000L),
        new Suffix("m", 1_000_000L),
        new Suffix("b", 1_000_000_000L),
        new Suffix("t", 1_000_000_000_000L));

    private final String pattern;
    private final boolean grouping;
    private final long compactThreshold;
    private final int compactDecimals;
    private final List<Suffix> suffixes;
    private final long maxAmount;
    private final String symbolChars;

    /**
     * @param pattern          display pattern containing {@code <amount>}, e.g. {@code $<amount>}
     * @param grouping         use grouping commas for full amounts (1,500)
     * @param compactThreshold amounts at or above this use suffix form (1.5m); 0 disables compact form
     * @param compactDecimals  maximum decimals in compact form (trailing zeros are dropped)
     * @param suffixes         suffixes in any order
     * @param maxAmount        the largest amount accepted by {@link #parse(String)}
     */
    public MoneyFormat(String pattern, boolean grouping, long compactThreshold, int compactDecimals,
                       List<Suffix> suffixes, long maxAmount) {
        if (!pattern.contains("<amount>")) {
            throw new IllegalArgumentException("The money pattern must contain <amount>");
        }
        this.pattern = pattern;
        this.grouping = grouping;
        this.compactThreshold = Math.max(0, compactThreshold);
        this.compactDecimals = Math.clamp(compactDecimals, 0, 3);
        this.suffixes = suffixes.stream().sorted((a, b) -> Long.compare(b.multiplier(), a.multiplier())).toList();
        this.maxAmount = maxAmount;
        this.symbolChars = pattern.replace("<amount>", "").trim();
    }

    public static MoneyFormat defaults() {
        return new MoneyFormat("$<amount>", true, 1_000_000L, 2, DEFAULT_SUFFIXES, 1_000_000_000_000_000L);
    }

    public long maxAmount() {
        return this.maxAmount;
    }

    /** Formats an amount with the currency pattern, e.g. {@code $1,500} or {@code $2.5m}. */
    public String format(long amount) {
        return this.pattern.replace("<amount>", formatNumber(amount));
    }

    /** Formats an amount exactly with the currency pattern, never compact, e.g. {@code $2,500,000}. */
    public String formatExact(long amount) {
        return this.pattern.replace("<amount>", group(amount));
    }

    /**
     * Formats an amount in one reader's {@link MoneyStyle}: the server's way, every digit, or short (null: the
     * server's way). Confirmations use {@link #formatExact} whatever the reader chose.
     */
    public String format(long amount, MoneyStyle style) {
        return (style == null ? MoneyStyle.SERVER : style).format(this, amount);
    }

    /**
     * Formats an amount short from the smallest suffix on, e.g. {@code $1.2m} or {@code $15.5k} (the "short" money
     * format players can pick): at most {@link #SHORT_DECIMALS} decimal (none when the server's
     * {@code compact-decimals} is 0), rounded down so an amount never reads as more than it is. Amounts below the
     * smallest suffix are written in full.
     */
    public String formatShort(long amount) {
        return this.pattern.replace("<amount>", formatShortNumber(amount));
    }

    /** Formats only the number part (no currency symbol). */
    public String formatNumber(long amount) {
        return compact(amount, this.compactThreshold, this.compactDecimals);
    }

    /** The number part of {@link #formatExact}: every digit, grouped when the server groups. */
    public String formatExactNumber(long amount) {
        return group(amount);
    }

    /** The number part of {@link #formatShort}. */
    public String formatShortNumber(long amount) {
        return compact(amount, smallestSuffix(), Math.min(this.compactDecimals, SHORT_DECIMALS));
    }

    /**
     * Whether {@link #format} writes some amount (up to {@link #maxAmount()}) short. When it doesn't
     * ({@code compact-from: 0}, no suffixes), the server's way is every digit, the same as {@link #formatExact}.
     */
    public boolean compactsSome() {
        long smallest = smallestSuffix();
        return this.compactThreshold > 0 && smallest > 0 && Math.max(this.compactThreshold, smallest) <= this.maxAmount;
    }

    /**
     * Whether {@link #formatShort} writes every amount exactly like {@link #format}: the server shortens from the
     * smallest suffix on (or earlier) with at most {@link #SHORT_DECIMALS} decimal, or nothing can be shortened.
     */
    public boolean shortIsServers() {
        long smallest = smallestSuffix();
        if (smallest == 0 || smallest > this.maxAmount) {
            return true;
        }
        return this.compactThreshold > 0 && this.compactThreshold <= smallest && this.compactDecimals <= SHORT_DECIMALS;
    }

    /** The multiplier of the smallest suffix ({@code k}: 1000), 0 without suffixes. */
    private long smallestSuffix() {
        long smallest = 0;
        for (Suffix suffix : this.suffixes) {
            smallest = smallest == 0 ? suffix.multiplier() : Math.min(smallest, suffix.multiplier());
        }
        return smallest;
    }

    /** Suffix form ({@code 2.5m}) at or above {@code threshold} (0: never), otherwise the grouped number. */
    private String compact(long amount, long threshold, int decimals) {
        long abs = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (threshold > 0 && abs >= threshold) {
            for (Suffix suffix : this.suffixes) {
                if (abs >= suffix.multiplier()) {
                    BigDecimal value = BigDecimal.valueOf(abs)
                        .divide(BigDecimal.valueOf(suffix.multiplier()), decimals, RoundingMode.DOWN)
                        .stripTrailingZeros();
                    String number = value.scale() < 0 ? value.setScale(0, RoundingMode.DOWN).toPlainString() : value.toPlainString();
                    return (amount < 0 ? "-" : "") + number + suffix.symbol();
                }
            }
        }
        return group(amount);
    }

    private String group(long amount) {
        return this.grouping ? String.format(Locale.ROOT, "%,d", amount) : Long.toString(amount);
    }

    /** Parses a strictly positive amount. */
    public ParseResult parse(String input) {
        return parse(input, false);
    }

    /** Parses an amount; zero is accepted when {@code allowZero}. Negative amounts are always rejected. */
    public ParseResult parse(String input, boolean allowZero) {
        if (input == null) {
            return ParseResult.fail(ParseError.EMPTY);
        }
        String text = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        for (char c : this.symbolChars.toCharArray()) {
            text = text.replace(String.valueOf(c).toLowerCase(Locale.ROOT), "");
        }
        text = text.trim();
        if (text.isEmpty()) {
            return ParseResult.fail(ParseError.EMPTY);
        }
        long multiplier = 1;
        for (Suffix suffix : this.suffixes) {
            if (text.endsWith(suffix.symbol())) {
                multiplier = suffix.multiplier();
                text = text.substring(0, text.length() - suffix.symbol().length()).trim();
                break;
            }
        }
        if (text.isEmpty() || text.startsWith("+")) {
            return ParseResult.fail(ParseError.NOT_A_NUMBER);
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            return ParseResult.fail(ParseError.NOT_A_NUMBER);
        }
        if (value.signum() < 0) {
            return ParseResult.fail(ParseError.NOT_POSITIVE);
        }
        // Exponent notation (1e100000000) is a short input for an enormous number: rescaling it would compute a
        // power of ten with millions of digits and stall the thread. Digit count and scale are cheap to read, so
        // anything with a fraction or with more digits than a long can hold is answered from them first.
        BigDecimal scaled;
        try {
            scaled = value.multiply(BigDecimal.valueOf(multiplier)).stripTrailingZeros();
        } catch (ArithmeticException e) {
            // Stripping zeros only overflows the scale for exponents far beyond any amount.
            return ParseResult.fail(ParseError.TOO_LARGE);
        }
        if (scaled.scale() > 0) {
            return ParseResult.fail(ParseError.NOT_WHOLE);
        }
        if ((long) scaled.precision() - scaled.scale() > 19) {
            return ParseResult.fail(ParseError.TOO_LARGE);
        }
        BigDecimal whole;
        try {
            whole = scaled.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            return ParseResult.fail(ParseError.NOT_WHOLE);
        }
        if (whole.signum() == 0 && !allowZero) {
            return ParseResult.fail(ParseError.NOT_POSITIVE);
        }
        if (whole.compareTo(BigDecimal.valueOf(this.maxAmount)) > 0) {
            return ParseResult.fail(ParseError.TOO_LARGE);
        }
        return ParseResult.of(whole.longValueExact());
    }

    /** Convenience: the parsed amount if valid. */
    public Optional<Long> tryParse(String input) {
        ParseResult result = parse(input);
        return result.ok() ? Optional.of(result.amount()) : Optional.empty();
    }
}
