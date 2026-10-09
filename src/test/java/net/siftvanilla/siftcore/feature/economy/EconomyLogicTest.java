package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void aPaymentFromAnIgnoredPlayerArrivesQuietly() {
        assertTrue(PayService.notifies(true, false, false), "notifications on, not ignored");
        assertFalse(PayService.notifies(true, true, false), "the receiver ignores the payer: no /pay spam notices");
        assertTrue(PayService.notifies(true, true, true), "staff who can't be ignored are still announced");
        assertFalse(PayService.notifies(false, false, false), "notifications off");
    }
}
