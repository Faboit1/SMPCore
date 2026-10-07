package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EconomyLogicTest {

    @Test
    void payLimitGrowsWithPlaytimeAndIsCapped() {
        assertEquals(250_000, PayLimits.limit(250_000, 50_000, 100_000_000, 0));
        assertEquals(750_000, PayLimits.limit(250_000, 50_000, 100_000_000, 10));
        assertEquals(100_000_000, PayLimits.limit(250_000, 50_000, 100_000_000, 1_000_000));
        assertEquals(100_000_000, PayLimits.limit(250_000, Long.MAX_VALUE / 2, 100_000_000, 10), "overflow saturates");
        assertEquals(250_000, PayLimits.limit(250_000, 50_000, 100_000_000, -5), "negative hours count as zero");
    }
}
