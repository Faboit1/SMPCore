package net.siftvanilla.siftcore.feature.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import org.junit.jupiter.api.Test;

/** The orders browser's five sorts and its category filter. */
class BrowserSortTest {

    private static final UUID OWNER = new UUID(40, 1);

    /** An active order: {@code wanted} of {@code quantity} still wanted at {@code price} each. */
    private static Order order(long id, String item, int quantity, int wanted, long price, long created, long expires) {
        int filled = quantity - wanted;
        return new Order(id, OWNER, item, null, quantity, filled, 0, price, OrderMath.total(wanted, price), created, expires,
            OrderState.ACTIVE, 0, 0, false);
    }

    // id, item, quantity, wanted, price, created, expires
    private static final Order A = order(1, "minecraft:diamond", 100, 100, 50, 1_000, 9_000);
    private static final Order B = order(2, "minecraft:stone", 10_000, 9_000, 2, 2_000, 8_000);
    private static final Order C = order(3, "minecraft:bread", 64, 10, 50, 3_000, 5_000);
    private static final Order D = order(4, "minecraft:diamond_sword", 5, 5, 900, 4_000, 7_000);
    private static final Order E = order(5, "minecraft:diamond", 10, 10, 50, 1_000, 6_000);
    private static final List<Order> ALL = List.of(A, B, C, D, E);

    private static final Map<String, ItemCategory> CATEGORIES = Map.of("minecraft:diamond", ItemCategory.MISC, "minecraft:stone",
        ItemCategory.BLOCKS, "minecraft:bread", ItemCategory.FOOD, "minecraft:diamond_sword", ItemCategory.COMBAT);
    private static final Function<Order, ItemCategory> CATEGORY = order -> CATEGORIES.get(order.itemType());

    private static List<Long> ids(BrowserSort.Sort sort, String filter) {
        return BrowserSort.view(ALL, sort, BrowserSort.filter(filter, CATEGORY)).stream().map(Order::id).toList();
    }

    @Test
    void highestPriceEachFirstThenOldest() {
        assertEquals(List.of(4L, 1L, 5L, 3L, 2L), ids(BrowserSort.Sort.PRICE, BrowserSort.ALL),
            "$900, then the three $50 orders oldest first (equal times by id), then $2");
    }

    @Test
    void highestTotalHeldFirst() {
        // Held: A 5,000; B 18,000; C 500; D 4,500; E 500.
        assertEquals(List.of(2L, 1L, 4L, 5L, 3L), ids(BrowserSort.Sort.TOTAL, BrowserSort.ALL));
    }

    @Test
    void mostWantedFirst() {
        assertEquals(List.of(2L, 1L, 5L, 3L, 4L), ids(BrowserSort.Sort.WANTED, BrowserSort.ALL),
            "9,000, 100, then the two orders wanting 10 oldest first, then 5");
    }

    @Test
    void newestFirst() {
        assertEquals(List.of(4L, 3L, 2L, 1L, 5L), ids(BrowserSort.Sort.NEWEST, BrowserSort.ALL));
    }

    @Test
    void endingSoonFirst() {
        assertEquals(List.of(3L, 5L, 4L, 2L, 1L), ids(BrowserSort.Sort.ENDING, BrowserSort.ALL));
    }

    @Test
    void theCategoryFilterKeepsOnlyThatCategory() {
        assertEquals(List.of(1L, 5L), ids(BrowserSort.Sort.PRICE, "misc"));
        assertEquals(List.of(2L), ids(BrowserSort.Sort.PRICE, "blocks"));
        assertEquals(List.of(4L), ids(BrowserSort.Sort.PRICE, "combat"));
        assertEquals(List.of(), ids(BrowserSort.Sort.PRICE, "potions"));
        assertEquals(5, ids(BrowserSort.Sort.PRICE, "no-such-category").size(), "an unknown stored filter shows everything");
    }

    @Test
    void storedIdsReadBack() {
        for (BrowserSort.Sort sort : BrowserSort.Sort.values()) {
            assertEquals(sort, BrowserSort.Sort.byId(sort.id()));
        }
        assertEquals(BrowserSort.Sort.PRICE, BrowserSort.Sort.byId("cheapest"), "an unknown stored sort falls back to the default");
        assertEquals(BrowserSort.Sort.PRICE, BrowserSort.Sort.byId(null));
    }

    @Test
    void onlyCategoriesWithOrderableItemsGetAFilter() {
        assertEquals(List.of(ItemCategory.BLOCKS, ItemCategory.FOOD, ItemCategory.BOOKS),
            BrowserSort.filters(EnumSet.of(ItemCategory.BOOKS, ItemCategory.FOOD, ItemCategory.BLOCKS)), "in the categories' own order");
        assertEquals(List.of(), BrowserSort.filters(EnumSet.noneOf(ItemCategory.class)));
    }
}
