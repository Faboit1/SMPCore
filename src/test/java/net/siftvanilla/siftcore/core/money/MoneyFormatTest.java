package net.siftvanilla.siftcore.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyFormatTest {

    private final MoneyFormat format = MoneyFormat.defaults();

    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "1500, 1500",
        "'1,500', 1500",
        "1.5k, 1500",
        "1.5K, 1500",
        "2m, 2000000",
        "2.25m, 2250000",
        "1b, 1000000000",
        "0.001m, 1000",
        "'$10', 10",
        "' $1.5k ', 1500",
        "3t, 3000000000000",
        "1_000, 1000"
    })
    void parsesValidAmounts(String input, long expected) {
        MoneyFormat.ParseResult result = this.format.parse(input);
        assertTrue(result.ok(), () -> input + " failed with " + result.error());
        assertEquals(expected, result.amount());
    }

    @ParameterizedTest
    @CsvSource({
        "1.2345k, NOT_WHOLE",
        "0.5, NOT_WHOLE",
        "1.0000001m, NOT_WHOLE",
        "abc, NOT_A_NUMBER",
        "k, NOT_A_NUMBER",
        "'', EMPTY",
        "-5, NOT_POSITIVE",
        "0, NOT_POSITIVE",
        "+5, NOT_A_NUMBER",
        "1.5e0, NOT_WHOLE",
        "2000t, TOO_LARGE",
        "99999999999999999999999, TOO_LARGE"
    })
    void rejectsInvalidAmounts(String input, MoneyFormat.ParseError expected) {
        MoneyFormat.ParseResult result = this.format.parse(input);
        assertFalse(result.ok());
        assertEquals(expected, result.error());
    }

    @Test
    void scientificNotationThatIsWholeIsAccepted() {
        assertEquals(1000, this.format.parse("1E3").amount());
    }

    @Test
    void zeroAllowedWhenAsked() {
        assertTrue(this.format.parse("0", true).ok());
        assertEquals(0, this.format.parse("0", true).amount());
    }

    @Test
    void formatsWithSymbolFirst() {
        assertEquals("$10", this.format.format(10));
        assertEquals("$1,500", this.format.format(1500));
        assertEquals("$999,999", this.format.format(999_999));
        assertEquals("$1m", this.format.format(1_000_000));
        assertEquals("$2.5m", this.format.format(2_500_000));
        assertEquals("$2.53m", this.format.format(2_534_999));
        assertEquals("$1b", this.format.format(1_000_000_000));
        assertEquals("$4.2t", this.format.format(4_200_000_000_000L));
        assertEquals("$0", this.format.format(0));
        assertEquals("$-1,500", this.format.format(-1500));
    }

    @Test
    void compactFormRoundsDownNeverUp() {
        assertEquals("$1.99m", this.format.format(1_999_999));
    }

    @Test
    void exactFormatNeverAbbreviates() {
        assertEquals("$2,500,000", this.format.formatExact(2_500_000));
    }

    @Test
    void formatAndParseRoundTripForCompactValues() {
        for (long value : new long[] {1, 999, 1500, 1_000_000, 2_500_000, 7_000_000_000L}) {
            String number = this.format.formatNumber(value);
            assertEquals(value, this.format.parse(number).amount(), number);
        }
    }

    @Test
    void customSuffixPatternWorks() {
        MoneyFormat suffix = new MoneyFormat("<amount> coins", false, 0, 2, MoneyFormat.DEFAULT_SUFFIXES, 1_000_000);
        assertEquals("1500 coins", suffix.format(1500));
        assertEquals(1500, suffix.parse("1500 coins").amount());
    }
}
