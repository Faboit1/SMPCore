package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import org.junit.jupiter.api.Test;

class OrderRoutingTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String DIAMOND = "minecraft:diamond";
    private static final String STICK = "minecraft:stick";

    private static OrderMarket.Bid bid(long id, long price, long remaining, long created) {
        return new OrderMarket.Bid(id, id % 2 == 0 ? A : B, price, remaining, created);
    }

    private static OrderRouting.Line line(String key, long units, String serverUnit, boolean routeUnpriced) {
        return new OrderRouting.Line(key, units, new BigDecimal(serverUnit), routeUnpriced);
    }

    @Test
    void anOrderMustPayStrictlyMoreThanTheServerAfterTax() {
        // the server pays 400 x 1.5 = 600 each; an order at 612 with 2% tax pays exactly 599.76 -> server
        var below = OrderRouting.plan(List.of(line(DIAMOND, 10, "600", false)), key -> List.of(bid(1, 612, 100, 1)), 200);
        assertTrue(below.takes().isEmpty());
        assertEquals(10, below.server(DIAMOND));
        // exactly equal (600 net) is a tie: the server wins
        var tie = OrderRouting.plan(List.of(line(DIAMOND, 10, "600", false)), key -> List.of(bid(1, 600, 100, 1)), 0);
        assertTrue(tie.takes().isEmpty());
        // one cent more goes to the order
        var above = OrderRouting.plan(List.of(line(DIAMOND, 10, "600", false)), key -> List.of(bid(1, 601, 100, 1)), 0);
        assertEquals(List.of(new OrderMarket.Take(1, DIAMOND, 10, 601)), above.takes());
        assertEquals(0, above.server(DIAMOND));
    }

    @Test
    void bestPriceFirstThenOldest() {
        var plan = OrderRouting.plan(List.of(line(DIAMOND, 50, "400", false)), key -> List.of(
            bid(1, 500, 10, 300), bid(2, 700, 10, 200), bid(3, 500, 10, 100), bid(4, 700, 10, 50)), 0);
        assertEquals(List.of(
            new OrderMarket.Take(4, DIAMOND, 10, 700),
            new OrderMarket.Take(2, DIAMOND, 10, 700),
            new OrderMarket.Take(3, DIAMOND, 10, 500),
            new OrderMarket.Take(1, DIAMOND, 10, 500)), plan.takes());
        assertEquals(10, plan.server(DIAMOND));
    }

    @Test
    void oneOrdersRemainingIsSharedByEveryStackOfItsKey() {
        // three stacks of diamonds, one order that wants 100: it gets 100 in total, never 100 per stack
        var plan = OrderRouting.plan(List.of(line(DIAMOND, 64, "400", false), line(DIAMOND, 64, "400", false),
            line(DIAMOND, 10, "400", false)), key -> List.of(bid(7, 450, 100, 1)), 0);
        assertEquals(List.of(new OrderMarket.Take(7, DIAMOND, 100, 450)), plan.takes());
        assertEquals(100, plan.routed(DIAMOND));
        assertEquals(38, plan.server(DIAMOND));
    }

    @Test
    void taxIsRoundedDownOncePerTake() {
        // 3 x 333 = 999 gross; 2% of 999 = 19.98 -> 19; net 980
        OrderMarket.Take take = new OrderMarket.Take(1, DIAMOND, 3, 333);
        assertEquals(19, OrderMarket.tax(take.gross(), 200));
        assertEquals(980, OrderMarket.net(take, 200));
        assertEquals(0, OrderMarket.tax(49, 200));
        // exact for every long: 100% of the largest gross is all of it, 0.01% is a ten-thousandth rounded down
        assertEquals(Long.MAX_VALUE, OrderMarket.tax(Long.MAX_VALUE, 10_000));
        assertEquals(Long.MAX_VALUE / 10_000, OrderMarket.tax(Long.MAX_VALUE, 1));
        assertEquals(0, new BigDecimal("326.34").compareTo(OrderRouting.netEach(333, 200)));
    }

    @Test
    void unpricedItemsGoToOrdersOnlyWhereAllowed() {
        // the server doesn't buy sticks: the menu and /sell hand may route them, /sell all never moves them
        var allowed = OrderRouting.plan(List.of(line(STICK, 40, "0", true)), key -> List.of(bid(1, 2, 30, 1)), 0);
        assertEquals(List.of(new OrderMarket.Take(1, STICK, 30, 2)), allowed.takes());
        assertEquals(10, allowed.kept(STICK));
        assertEquals(0, allowed.server(STICK));
        var notAllowed = OrderRouting.plan(List.of(line(STICK, 40, "0", false)), key -> List.of(bid(1, 2, 30, 1)), 0);
        assertTrue(notAllowed.takes().isEmpty());
        assertEquals(40, notAllowed.kept(STICK));
    }

    @Test
    void withoutBidsEverythingGoesToTheServer() {
        var plan = OrderRouting.plan(List.of(line(DIAMOND, 64, "400", false), line(STICK, 5, "0", true)), key -> List.of(), 0);
        assertTrue(plan.takes().isEmpty());
        assertEquals(Map.of(DIAMOND, 64L), plan.serverUnits());
        assertEquals(Map.of(STICK, 5L), plan.kept());
    }

    @Test
    void ordersWithNothingLeftAreSkipped() {
        var plan = OrderRouting.plan(List.of(line(DIAMOND, 5, "400", false)), key -> List.of(bid(1, 900, 0, 1),
            bid(2, 800, 3, 2)), 0);
        assertEquals(List.of(new OrderMarket.Take(2, DIAMOND, 3, 800)), plan.takes());
        assertEquals(2, plan.server(DIAMOND));
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> OrderRouting.plan(List.of(line(DIAMOND, Long.MAX_VALUE, "400", false),
            line(DIAMOND, 1, "400", false)), key -> List.of(), 0));
        assertThrows(ArithmeticException.class, () -> new OrderMarket.Take(1, DIAMOND, Long.MAX_VALUE, 2).gross());
    }

    @Test
    void oneKeyCantHaveTwoServerPrices() {
        assertThrows(IllegalArgumentException.class, () -> OrderRouting.plan(List.of(line(DIAMOND, 1, "400", false),
            line(DIAMOND, 1, "600", false)), key -> List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> OrderRouting.plan(List.of(), key -> List.of(), 10_001));
    }
}
