package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Prices typed into {@code /ah sell} and the sell form are parsed by the core money format. Short inputs in exponent
 * notation stand for numbers with millions of digits; they must be refused at once instead of stalling the thread.
 */
class AuctionPriceInputTest {

    private final MoneyFormat format = MoneyFormat.defaults();

    @ParameterizedTest
    @CsvSource({
        "1e100000000, TOO_LARGE",
        "1e999999999, TOO_LARGE",
        "1e2147483647, TOO_LARGE",
        "1000e2147483647, TOO_LARGE",
        "9e19, TOO_LARGE",
        "1e100000000k, TOO_LARGE",
        "1e-100000000, NOT_WHOLE",
        "1e-2147483647, NOT_WHOLE",
        "1000e-2147483647, NOT_WHOLE",
        "0e999999999, NOT_POSITIVE",
        "1.5e0, NOT_WHOLE"
    })
    void hugeExponentsAreRefusedQuickly(String input, MoneyFormat.ParseError expected) {
        MoneyFormat.ParseResult result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> this.format.parse(input));
        assertFalse(result.ok(), input + " was accepted");
        assertEquals(expected, result.error(), input);
    }

    @ParameterizedTest
    @CsvSource({
        "5e3, 5000",
        "1.5e3, 1500",
        "1.5e3k, 1500000",
        "2.50e2, 250",
        "1000, 1000",
        "10b, 10000000000"
    })
    void ordinaryPricesStillParse(String input, long expected) {
        MoneyFormat.ParseResult result = this.format.parse(input);
        assertTrue(result.ok(), () -> input + " failed with " + result.error());
        assertEquals(expected, result.amount());
    }

    @Test
    void theLargestAllowedAmountIsStillExact() {
        long max = this.format.maxAmount();
        MoneyFormat.ParseResult result = this.format.parse(Long.toString(max));
        assertTrue(result.ok());
        assertEquals(max, result.amount());
        assertEquals(MoneyFormat.ParseError.TOO_LARGE, this.format.parse(max + "0").error());
    }
}
