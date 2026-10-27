package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SaleMathTest {

    @Test
    void addsUnitTimesAmount() {
        assertEquals(25_600, SaleMath.add(0, 400, 64));
        assertEquals(25_601, SaleMath.add(1, 400, 64));
    }

    @Test
    void additionOverflowIsReported() {
        assertThrows(ArithmeticException.class, () -> SaleMath.add(0, Long.MAX_VALUE / 2, 3));
        assertThrows(ArithmeticException.class, () -> SaleMath.add(Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> SaleMath.add(0, -1, 1));
    }

    @Test
    void multiplierRoundsDownExactly() {
        assertEquals(25_600, SaleMath.withMultiplier(25_600, 1.0));
        // 3 * 1.1 is 3.3000000000000003 in doubles; the exact value is 3.3 -> 3
        assertEquals(3, SaleMath.withMultiplier(3, 1.1));
        assertEquals(38_400, SaleMath.withMultiplier(25_600, 1.5));
        // 999,999,999,999,999 * 1.1 has no exact double; exact math floors 1,099,999,999,999,998.9 correctly
        assertEquals(1_099_999_999_999_998L, SaleMath.withMultiplier(999_999_999_999_999L, 1.1));
        assertEquals(0, SaleMath.withMultiplier(0, 1.5));
    }

    @Test
    void multiplierOverflowAndBadInputAreReported() {
        assertThrows(ArithmeticException.class, () -> SaleMath.withMultiplier(Long.MAX_VALUE, 1.5));
        assertThrows(IllegalArgumentException.class, () -> SaleMath.withMultiplier(10, 0.5));
        assertThrows(IllegalArgumentException.class, () -> SaleMath.withMultiplier(10, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SaleMath.withMultiplier(-1, 1.0));
    }
}
