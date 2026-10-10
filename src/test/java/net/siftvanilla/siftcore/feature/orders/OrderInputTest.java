package net.siftvanilla.siftcore.feature.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What players can type as a quantity and a price. */
class OrderInputTest {

    private static final int MAX = 100_000;

    private static int ok(String input) {
        return ok(input, 64);
    }

    private static int ok(String input, int maxStack) {
        OrderInput.Quantity quantity = OrderInput.quantity(input, MAX, maxStack);
        assertTrue(quantity.ok(), "'" + input + "' should parse, got " + quantity.error());
        return quantity.value();
    }

    private static OrderInput.QuantityError error(String input) {
        OrderInput.Quantity quantity = OrderInput.quantity(input, MAX, 64);
        assertFalse(quantity.ok(), "'" + input + "' should be refused, got " + quantity.value());
        return quantity.error();
    }

    @Test
    void plainNumbersAndGrouping() {
        assertEquals(1, ok("1"));
        assertEquals(64, ok(" 64 "));
        assertEquals(1_500, ok("1,500"));
        assertEquals(1_500, ok("1_500"));
        assertEquals(100_000, ok("100000"));
    }

    @Test
    void thousandsAndMillions() {
        assertEquals(1_500, ok("1.5k"));
        assertEquals(2_000, ok("2K"));
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("2m"));
        assertEquals(1_000, ok("0.001m"));
        assertEquals(OrderInput.QuantityError.NOT_A_NUMBER, error("1.2345k"));
    }

    @Test
    void stacksUseTheItemsStackSize() {
        assertEquals(192, ok("3 stacks"));
        assertEquals(64, ok("1 stack"));
        assertEquals(128, ok("2st"));
        assertEquals(96, ok("1.5 stacks"));
        assertEquals(48, ok("3 stacks", 16), "ender pearls stack to 16");
        assertEquals(3, ok("3 stacks", 1), "unstackable items count one per stack");
    }

    @Test
    void shulkersAreTwentySevenStacks() {
        assertEquals(1_728, ok("1 shulker"));
        assertEquals(3_456, ok("2 shulkers"));
        assertEquals(3_456, ok("2sb"));
        assertEquals(432, ok("1 shulker", 16));
        assertEquals(27, ok("1sb", 1));
    }

    @Test
    void cappedByTheMaximum() {
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("100001"));
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("100 shulkers"));
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("0"));
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("0 stacks"));
        assertEquals(OrderInput.QuantityError.OUT_OF_RANGE, error("99999999999999999999"));
    }

    @Test
    void junkIsRefused() {
        for (String input : new String[] {"", " ", "abc", "-5", "+5", "1e3", "1E+5", "0x10", "NaN", "Infinity", "1.5", "5 apples",
            "stacks", "3 stack s", "1..5", ",5", "12345678901234567890123456789"}) {
            error(input);
        }
        assertEquals(OrderInput.QuantityError.NOT_A_NUMBER, error("1.5"), "fractions of an item are refused");
        assertEquals(OrderInput.QuantityError.NOT_A_NUMBER, error(null));
    }

    @Test
    void priceTextRefusesExponentsAndLongInput() {
        assertTrue(OrderInput.priceTextAllowed("1.5k"));
        assertTrue(OrderInput.priceTextAllowed("$1,000"));
        assertFalse(OrderInput.priceTextAllowed("1e100000000"));
        assertFalse(OrderInput.priceTextAllowed("2 E + 5"));
        assertFalse(OrderInput.priceTextAllowed("1".repeat(25)));
        assertFalse(OrderInput.priceTextAllowed(null));
    }
}
