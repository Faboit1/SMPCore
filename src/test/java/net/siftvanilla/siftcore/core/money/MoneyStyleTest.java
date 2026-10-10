package net.siftvanilla.siftcore.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The three money formats players can pick: the server's way, every digit, and short. */
class MoneyStyleTest {

    private final MoneyFormat format = MoneyFormat.defaults();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0|$0|$0|$0",
        "999|$999|$999|$999",
        "1000|$1,000|$1,000|$1k",
        "1500|$1,500|$1,500|$1.5k",
        "15550|$15,550|$15,550|$15.5k",
        "999999|$999,999|$999,999|$999.9k",
        "1000000|$1m|$1,000,000|$1m",
        "1234567|$1.23m|$1,234,567|$1.2m",
        "2500000|$2.5m|$2,500,000|$2.5m",
        "1999999999|$1.99b|$1,999,999,999|$1.9b",
        "-1500|$-1,500|$-1,500|$-1.5k"
    })
    void eachStyleWritesAnAmountItsWay(long amount, String server, String full, String shortForm) {
        assertEquals(server, MoneyStyle.SERVER.format(this.format, amount));
        assertEquals(full, MoneyStyle.FULL.format(this.format, amount));
        assertEquals(shortForm, MoneyStyle.SHORT.format(this.format, amount));
        assertEquals(server, this.format.format(amount, MoneyStyle.SERVER));
        assertEquals(full, this.format.format(amount, MoneyStyle.FULL));
        assertEquals(shortForm, this.format.format(amount, MoneyStyle.SHORT));
    }

    @Test
    void fullIsTheExactFormatAndServerTheDefault() {
        for (long amount : new long[] {0, 7, 1_234, 1_234_567, 9_876_543_210L}) {
            assertEquals(this.format.formatExact(amount), MoneyStyle.FULL.format(this.format, amount));
            assertEquals(this.format.format(amount), MoneyStyle.SERVER.format(this.format, amount));
            assertEquals(this.format.format(amount), this.format.format(amount, null), "no style is the server's way");
        }
    }

    @Test
    void numbersLeaveTheSymbolOut() {
        assertEquals("1.23m", MoneyStyle.SERVER.formatNumber(this.format, 1_234_567));
        assertEquals("1,234,567", MoneyStyle.FULL.formatNumber(this.format, 1_234_567));
        assertEquals("1.2m", MoneyStyle.SHORT.formatNumber(this.format, 1_234_567));
        assertEquals("1,234,567", this.format.formatExactNumber(1_234_567));
        assertEquals("1.2m", this.format.formatShortNumber(1_234_567));
    }

    @Test
    void shortNeverRoundsUp() {
        // 999,999 must not read as a thousand thousands, nor 1,999,999 as two million.
        assertEquals("$999.9k", this.format.formatShort(999_999));
        assertEquals("$1.9m", this.format.formatShort(1_999_999));
        assertEquals("$9.9k", this.format.formatShort(9_999));
    }

    @Test
    void shortFollowsTheServersPatternGroupingAndSuffixes() {
        MoneyFormat coins = new MoneyFormat("<amount> coins", false, 0, 2,
            List.of(new MoneyFormat.Suffix("K", 1_000), new MoneyFormat.Suffix("M", 1_000_000)), 1_000_000_000_000L);
        assertEquals("1.2m coins", coins.formatShort(1_234_567));
        assertEquals("950 coins", coins.formatShort(950));
        assertEquals("1234567 coins", coins.formatExact(1_234_567), "no grouping on this server");
        assertEquals("1234567 coins", coins.format(1_234_567), "this server never writes short itself");
        assertEquals("1.2m coins", coins.format(1_234_567, MoneyStyle.SHORT), "players may still pick short");
    }

    @Test
    void shortStartsAtTheSmallestSuffixTheServerHas() {
        MoneyFormat millions = new MoneyFormat("$<amount>", true, 1_000_000, 2,
            List.of(new MoneyFormat.Suffix("m", 1_000_000), new MoneyFormat.Suffix("b", 1_000_000_000)), 1_000_000_000_000L);
        assertEquals("$950,000", millions.formatShort(950_000), "no k suffix: full below a million");
        assertEquals("$1.5m", millions.formatShort(1_500_000));
    }

    @Test
    void shortKeepsNoDecimalsWhenTheServerKeepsNone() {
        MoneyFormat whole = new MoneyFormat("$<amount>", true, 1_000_000, 0, MoneyFormat.DEFAULT_SUFFIXES, 1_000_000_000_000L);
        assertEquals("$1m", whole.formatShort(1_234_567));
        assertEquals("$1m", whole.format(1_234_567));
        assertEquals("$1k", whole.formatShort(1_999));
    }

    // ------------------------------------------------------------------ whether a style changes anything

    private static final long MAX = 1_000_000_000_000_000L;

    private static MoneyFormat format(long compactFrom, int decimals) {
        return new MoneyFormat("$<amount>", true, compactFrom, decimals, MoneyFormat.DEFAULT_SUFFIXES, MAX);
    }

    /** Amounts around every suffix and threshold of the formats below, to check the answers against what is written. */
    private static List<Long> amounts() {
        List<Long> amounts = new java.util.ArrayList<>(List.of(0L, 1L, 7L, 499L, 500L, 999L));
        for (int zeros = 3; zeros <= 18; zeros++) {
            long unit = (long) Math.pow(10, zeros);
            for (long amount : new long[] {unit, unit + 1, unit + unit / 10 + unit / 100 + 7, unit / 2 * 3, unit * 2 - 1}) {
                if (amount > 0 && amount <= MAX) {
                    amounts.add(amount);
                }
            }
        }
        return amounts;
    }

    /** Whether some amount is written differently in {@code style} than the server's way: the brute-force answer. */
    private static boolean writesSomeAmountDifferently(MoneyFormat format, MoneyStyle style) {
        return amounts().stream().anyMatch(amount -> !style.format(format, amount).equals(format.format(amount)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // compact-from | decimals | full differs | short differs
        "1000000|2|true|true",       // the shipped format: $1.23m against $1,234,567 and $1.2m
        "0|2|false|true",            // the server never shortens: in full is the server's way
        "0|0|false|true",
        "1000|1|true|false",         // the server shortens from k with one decimal: short is the server's way
        "500|1|true|false",          // below the smallest suffix nothing can be shortened anyway
        "1000|0|true|false",
        "1000|2|true|true",          // two decimals: $1.23k against $1.2k
        "1001|1|true|true",          // $1,000 in full on the server, $1k short
        "1000000|1|true|true",
        "2000000000000000000|2|false|true"  // above the largest amount: the server never shortens one
    })
    void aStyleIsOfferedOnlyWhenItWritesSomeAmountDifferently(long compactFrom, int decimals, boolean full, boolean shortForm) {
        MoneyFormat format = format(compactFrom, decimals);
        assertEquals(false, MoneyStyle.SERVER.differsFromServer(format), "the server's way is never different from itself");
        assertEquals(full, MoneyStyle.FULL.differsFromServer(format), "in full");
        assertEquals(shortForm, MoneyStyle.SHORT.differsFromServer(format), "short");
        assertEquals(full, writesSomeAmountDifferently(format, MoneyStyle.FULL), "in full, checked amount by amount");
        assertEquals(shortForm, writesSomeAmountDifferently(format, MoneyStyle.SHORT), "short, checked amount by amount");
    }

    @Test
    void withoutSuffixesEveryStyleIsTheServersWay() {
        MoneyFormat plain = new MoneyFormat("$<amount>", true, 1_000_000, 2, List.of(), MAX);
        assertEquals(false, MoneyStyle.FULL.differsFromServer(plain));
        assertEquals(false, MoneyStyle.SHORT.differsFromServer(plain));
        assertEquals("$1,234,567", MoneyStyle.SHORT.format(plain, 1_234_567));
        assertEquals("$1,234,567", plain.format(1_234_567));
    }

    @Test
    void theShippedFormatOffersBoth() {
        assertEquals(true, MoneyStyle.FULL.differsFromServer(this.format));
        assertEquals(true, MoneyStyle.SHORT.differsFromServer(this.format));
    }

    @Test
    void idsAreTheStoredValues() {
        assertEquals("server", MoneyStyle.SERVER.id());
        assertEquals("full", MoneyStyle.FULL.id());
        assertEquals("short", MoneyStyle.SHORT.id());
    }
}
