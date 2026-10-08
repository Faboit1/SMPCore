package net.siftvanilla.siftcore.feature.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Claim tax, expiry and sums. */
class BountyMathTest {

    @ParameterizedTest(name = "{0} at {1}% -> tax {2}, payout {3}")
    @CsvSource({
        "10000, 10, 1000, 9000",
        "12345, 10, 1234, 11111",
        "1000, 0, 0, 1000",
        "1, 10, 0, 1",
        "9, 10, 0, 9",
        "10, 10, 1, 9",
        "99, 90, 89, 10",
        "1000000, 90, 900000, 100000",
        "0, 10, 0, 0",
    })
    void taxIsRoundedDownAndTheRestIsPaid(long total, int percent, long tax, long payout) {
        BountyMath.Split split = BountyMath.split(total, percent);
        assertEquals(tax, split.tax());
        assertEquals(payout, split.payout());
        assertEquals(total, split.tax() + split.payout(), "nothing is created or lost");
    }

    @Test
    void taxMatchesExactArithmeticForAnyTotal() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            long total = i < 20 ? Long.MAX_VALUE - i : random.nextLong(0, Long.MAX_VALUE);
            int percent = random.nextInt(0, 91);
            long expected = BigInteger.valueOf(total).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100)).longValueExact();
            assertEquals(expected, BountyMath.tax(total, percent), total + " at " + percent + "%");
        }
    }

    @Test
    void edgePercentages() {
        assertEquals(0, BountyMath.tax(5_000, -3), "negative tax is no tax");
        assertEquals(5_000, BountyMath.tax(5_000, 100));
        assertEquals(5_000, BountyMath.tax(5_000, 250), "never more than the total");
    }

    @Test
    void contributionsRunOutExactlyAfterTheirTime() {
        Duration fourteenDays = Duration.ofDays(14);
        long placed = 1_700_000_000_000L;
        long end = placed + fourteenDays.toMillis();
        assertEquals(end, BountyMath.expiresAt(placed, fourteenDays));
        assertFalse(BountyMath.expired(placed, end - 1, fourteenDays));
        assertTrue(BountyMath.expired(placed, end, fourteenDays));
        assertTrue(BountyMath.expired(placed, end + 1, fourteenDays));
        assertFalse(BountyMath.expired(Long.MAX_VALUE - 10, Long.MAX_VALUE - 1, fourteenDays), "no overflow near the end of time");
    }

    @Test
    void sumsSaturate() {
        assertEquals(30, BountyMath.add(10, 20));
        assertEquals(Long.MAX_VALUE, BountyMath.add(Long.MAX_VALUE - 5, 10));
        assertEquals(Long.MAX_VALUE, BountyMath.add(Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
