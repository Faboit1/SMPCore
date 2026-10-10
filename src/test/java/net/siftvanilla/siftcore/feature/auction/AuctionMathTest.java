package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AuctionMathTest {

    @Test
    void taxIsRoundedDown() {
        assertEquals(50, AuctionMath.tax(1_000, 500));
        assertEquals(0, AuctionMath.tax(19, 500));
        assertEquals(1, AuctionMath.tax(20, 500));
        assertEquals(0, AuctionMath.tax(1_000, 0));
        assertEquals(1_000, AuctionMath.tax(1_000, 10_000));
        assertEquals(24, AuctionMath.tax(999, 250));
        assertEquals(500_000_000, AuctionMath.tax(10_000_000_000L, 500));
        assertEquals(950, AuctionMath.proceeds(1_000, 500));
    }

    @Test
    void taxNeverOverflowsAndMatchesExactArithmetic() {
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            long price = i < 10 ? Long.MAX_VALUE - i : random.nextLong(Long.MAX_VALUE);
            int bps = random.nextInt(10_001);
            long expected = BigInteger.valueOf(price).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(10_000)).longValueExact();
            assertEquals(expected, AuctionMath.tax(price, bps), price + " at " + bps);
            assertTrue(AuctionMath.proceeds(price, bps) >= 0);
        }
    }

    @Test
    void taxRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.tax(-1, 500));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.tax(1, -1));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.tax(1, 10_001));
    }

    @Test
    void percentagesParse() {
        assertEquals(500, AuctionMath.parsePercent("5"));
        assertEquals(500, AuctionMath.parsePercent("5%"));
        assertEquals(500, AuctionMath.parsePercent(" 5 % "));
        assertEquals(250, AuctionMath.parsePercent("2.5"));
        assertEquals(25, AuctionMath.parsePercent("0.25%"));
        assertEquals(0, AuctionMath.parsePercent("0"));
        assertEquals(10_000, AuctionMath.parsePercent("100"));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent("100.01"));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent("-1"));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent("0.125"));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent("five"));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent(""));
        assertThrows(IllegalArgumentException.class, () -> AuctionMath.parsePercent(null));
    }

    @Test
    void percentagesFormat() {
        assertEquals("5%", AuctionMath.formatPercent(500));
        assertEquals("2.5%", AuctionMath.formatPercent(250));
        assertEquals("0.25%", AuctionMath.formatPercent(25));
        assertEquals("0%", AuctionMath.formatPercent(0));
        assertEquals("100%", AuctionMath.formatPercent(10_000));
        assertEquals("10%", AuctionMath.formatPercent(1_000));
    }

    @Test
    void priceRulesWholeListing() {
        AuctionMath.PriceRules rules = new AuctionMath.PriceRules(1, 10_000_000_000L, 0, 0);
        assertTrue(rules.allows(1, 64));
        assertTrue(rules.allows(10_000_000_000L, 1));
        assertFalse(rules.allows(0, 1));
        assertFalse(rules.allows(10_000_000_001L, 1));
        assertEquals(1, rules.lowest(64));
        assertEquals(10_000_000_000L, rules.highest(64));
    }

    @Test
    void priceRulesPerItem() {
        AuctionMath.PriceRules rules = new AuctionMath.PriceRules(1, 10_000_000_000L, 2, 100);
        assertEquals(128, rules.lowest(64));
        assertEquals(6_400, rules.highest(64));
        assertFalse(rules.allows(127, 64));
        assertTrue(rules.allows(128, 64));
        assertTrue(rules.allows(6_400, 64));
        assertFalse(rules.allows(6_401, 64));
        assertEquals(2, rules.lowest(1));
        assertEquals(100, rules.highest(1));
    }

    @Test
    void priceRulesSaturateInsteadOfOverflowing() {
        AuctionMath.PriceRules rules = new AuctionMath.PriceRules(1, Long.MAX_VALUE, Long.MAX_VALUE / 2, 0);
        assertEquals(Long.MAX_VALUE, rules.lowest(64));
        assertFalse(rules.allows(Long.MAX_VALUE - 1, 64));
        assertTrue(rules.allows(Long.MAX_VALUE, 64));
        assertEquals(Long.MAX_VALUE, AuctionMath.saturatedMultiply(Long.MAX_VALUE, 2));
        assertEquals(6, AuctionMath.saturatedMultiply(2, 3));
        assertEquals(0, AuctionMath.saturatedMultiply(0, Long.MAX_VALUE));
    }

    @Test
    void contradictoryPerItemLimitsAllowNothing() {
        AuctionMath.PriceRules rules = new AuctionMath.PriceRules(1, 100, 10, 0);
        assertFalse(rules.allows(100, 64), "64 items at 10 each cost at least 640, above the 100 maximum");
    }

    @Test
    void priceRulesValidateThemselves() {
        assertThrows(IllegalArgumentException.class, () -> new AuctionMath.PriceRules(0, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuctionMath.PriceRules(10, 9, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuctionMath.PriceRules(1, 10, -1, 0));
    }
}
