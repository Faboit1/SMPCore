package net.siftvanilla.siftcore.integration.vault;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns the decimal amounts Vault uses into SiftCore's whole dollars. Rounding always favours the server, so no
 * sequence of plugin calls can create money out of rounding: money paid out rounds down, money taken rounds up.
 * Amounts are first rounded to six decimals, so floating-point noise such as 10.000000000000002 stays $10 instead of
 * being charged as $11.
 */
final class VaultMoney {

    /** The amount is unusable: negative, not a number, infinite or too large. */
    static final long INVALID = -1;

    private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private VaultMoney() {
    }

    /** Whole dollars a player receives for {@code amount} (rounded down), or {@link #INVALID}. */
    static long credit(BigDecimal amount) {
        return whole(amount, RoundingMode.DOWN);
    }

    /** Whole dollars a player pays for {@code amount} (rounded up), or {@link #INVALID}. */
    static long debit(BigDecimal amount) {
        return whole(amount, RoundingMode.UP);
    }

    static long credit(double amount) {
        return credit(decimal(amount));
    }

    static long debit(double amount) {
        return debit(decimal(amount));
    }

    /** The double as a decimal, or null when it is not a finite number. */
    static BigDecimal decimal(double amount) {
        return Double.isFinite(amount) ? BigDecimal.valueOf(amount) : null;
    }

    private static long whole(BigDecimal amount, RoundingMode mode) {
        if (amount == null || amount.signum() < 0) {
            return INVALID;
        }
        BigDecimal rounded = amount.setScale(6, RoundingMode.HALF_UP).setScale(0, mode);
        return rounded.compareTo(MAX) > 0 ? INVALID : rounded.longValueExact();
    }
}
