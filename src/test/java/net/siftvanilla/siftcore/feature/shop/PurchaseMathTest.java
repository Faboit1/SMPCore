package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PurchaseMathTest {

    private static final long MAX = 1_000_000_000_000_000L;

    @Test
    void totals() {
        assertEquals(1_600, PurchaseMath.total(25, 64, MAX).getAsLong());
        assertEquals(2_500_000L * 16, PurchaseMath.total(2_500_000, 16, MAX).getAsLong());
    }

    @Test
    void totalOverflowAndLimitsAreRefused() {
        assertTrue(PurchaseMath.total(Long.MAX_VALUE / 2, 3, Long.MAX_VALUE).isEmpty());
        assertTrue(PurchaseMath.total(MAX, 2, MAX).isEmpty());
        assertTrue(PurchaseMath.total(1, 0, MAX).isEmpty());
        assertTrue(PurchaseMath.total(0, 5, MAX).isEmpty());
        assertTrue(PurchaseMath.total(-3, 5, MAX).isEmpty());
        assertTrue(PurchaseMath.total(5, -3, MAX).isEmpty());
    }

    @Test
    void typedQuantities() {
        assertEquals(64, PurchaseMath.parseQuantity("64", 2304).getAsInt());
        assertEquals(1000, PurchaseMath.parseQuantity(" 1,000 ", 2304).getAsInt());
        assertEquals(1, PurchaseMath.parseQuantity("1", 1).getAsInt());
        assertTrue(PurchaseMath.parseQuantity("0", 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("65", 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("-5", 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("1.5", 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("2k", 6400).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("", 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity(null, 64).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("99999999999999999999", Integer.MAX_VALUE).isEmpty());
        assertTrue(PurchaseMath.parseQuantity("１２", 64).isEmpty());
    }

    @Test
    void quantityBounds() {
        assertTrue(PurchaseMath.validQuantity(1, 64));
        assertTrue(PurchaseMath.validQuantity(64, 64));
        assertFalse(PurchaseMath.validQuantity(0, 64));
        assertFalse(PurchaseMath.validQuantity(65, 64));
    }

    @Test
    void aTypedAmountWinsOverTheSlider() {
        assertEquals(64, PurchaseMath.chosenAmount(64, "", 2304).getAsInt());
        assertEquals(64, PurchaseMath.chosenAmount(64, "   ", 2304).getAsInt());
        assertEquals(64, PurchaseMath.chosenAmount(64, null, 2304).getAsInt());
        assertEquals(1000, PurchaseMath.chosenAmount(64, "1,000", 2304).getAsInt());
        // something typed must be valid on its own; the slider is not a fallback for a typo
        assertTrue(PurchaseMath.chosenAmount(64, "lots", 2304).isEmpty());
        assertTrue(PurchaseMath.chosenAmount(64, "2305", 2304).isEmpty());
        // a forged slider value outside the range is refused
        assertTrue(PurchaseMath.chosenAmount(0, "", 2304).isEmpty());
        assertTrue(PurchaseMath.chosenAmount(2305, "", 2304).isEmpty());
        assertTrue(PurchaseMath.chosenAmount(Long.MAX_VALUE, "", 2304).isEmpty());
    }

    @Test
    void dialogsStartAtOneStackOrTheCap() {
        assertEquals(64, PurchaseMath.defaultAmount(64, 2304));
        assertEquals(16, PurchaseMath.defaultAmount(16, 640));
        assertEquals(1, PurchaseMath.defaultAmount(1, 64));
        assertEquals(10, PurchaseMath.defaultAmount(64, 10));
        assertEquals(1, PurchaseMath.defaultAmount(0, 64));
        assertEquals(1, PurchaseMath.defaultAmount(64, 0));
    }

    @Test
    void inventoryCapacityAndSplit() {
        // stacks of 60, 64 and 10 of the item plus two empty slots, stacking to 64
        assertEquals(4 + 0 + 54 + 128, PurchaseMath.capacity(new int[] {60, 64, 10}, 2, 64));
        assertEquals(36 * 64, PurchaseMath.capacity(new int[0], 36, 64));
        assertEquals(36, PurchaseMath.capacity(new int[0], 36, 1));
        assertThrows(IllegalArgumentException.class, () -> PurchaseMath.capacity(new int[0], 1, 0));
        assertArrayEquals(new int[] {58, 42}, PurchaseMath.split(100, 58));
        assertArrayEquals(new int[] {10, 0}, PurchaseMath.split(10, 100));
        assertArrayEquals(new int[] {0, 5}, PurchaseMath.split(5, 0));
    }
}
