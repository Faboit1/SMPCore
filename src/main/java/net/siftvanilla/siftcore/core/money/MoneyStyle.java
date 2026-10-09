package net.siftvanilla.siftcore.core.money;

import java.util.Locale;

/**
 * How amounts of money are written for one reader: the values of the {@code money-format} setting. Pure logic.
 * <ul>
 *   <li>{@link #SERVER}: the server's format ({@link MoneyFormat#format}: in full below {@code compact-from}, short
 *       above it, like {@code $1,500} and {@code $2.53m});</li>
 *   <li>{@link #FULL}: every digit ({@link MoneyFormat#formatExact}, like {@code $1,234,567});</li>
 *   <li>{@link #SHORT}: short from the smallest suffix on ({@link MoneyFormat#formatShort}, like {@code $1.2m} and
 *       {@code $15.5k}).</li>
 * </ul>
 * Amounts a player must agree to (confirmations) are always written {@link #FULL}, whatever they chose.
 */
public enum MoneyStyle {
    SERVER,
    FULL,
    SHORT;

    /** The stored value and command word: {@code server}, {@code full} or {@code short}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** An amount with the currency pattern in this style, e.g. {@code $1.2m}. */
    public String format(MoneyFormat format, long amount) {
        return switch (this) {
            case SERVER -> format.format(amount);
            case FULL -> format.formatExact(amount);
            case SHORT -> format.formatShort(amount);
        };
    }

    /**
     * Whether this style writes some amount differently from the server's way under this format. When it doesn't
     * (in full while the server never shortens, short while the server already shortens from {@code k} with one
     * decimal), offering it would be a switch that changes nothing.
     */
    public boolean differsFromServer(MoneyFormat format) {
        return switch (this) {
            case SERVER -> false;
            case FULL -> format.compactsSome();
            case SHORT -> !format.shortIsServers();
        };
    }

    /** Only the number part in this style (no currency symbol), e.g. {@code 1.2m}. */
    public String formatNumber(MoneyFormat format, long amount) {
        return switch (this) {
            case SERVER -> format.formatNumber(amount);
            case FULL -> format.formatExactNumber(amount);
            case SHORT -> format.formatShortNumber(amount);
        };
    }
}
