package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.item.ItemCategory;

/**
 * How the orders browser sorts and filters. Pure: categories come from a lookup the server side provides. Every sort
 * ends with the id, so equal orders always come in the same order.
 */
final class BrowserSort {

    /** The browser's sort orders, in button order. */
    enum Sort {
        /** Highest price each first (the default: what sellers look for). */
        PRICE("price", Comparator.comparingLong(Order::priceEach).reversed().thenComparingLong(Order::created)),
        /** Biggest orders first: the money still held for the items wanted. */
        TOTAL("total", Comparator.comparingLong(Order::escrow).reversed().thenComparingLong(Order::created)),
        /** Most items still wanted first. */
        WANTED("wanted", Comparator.comparingInt(Order::remaining).reversed().thenComparingLong(Order::created)),
        /** Newest first. */
        NEWEST("newest", Comparator.comparingLong(Order::created).reversed()),
        /** Ending soonest first. */
        ENDING("ending", Comparator.comparingLong(Order::expires).thenComparingLong(Order::created));

        private final String id;
        private final Comparator<Order> comparator;

        Sort(String id, Comparator<Order> comparator) {
            this.id = id;
            this.comparator = comparator.thenComparingLong(Order::id);
        }

        String id() {
            return this.id;
        }

        Comparator<Order> comparator() {
            return this.comparator;
        }

        /** The sort with this id, or the default when unknown (a stored setting from an older version). */
        static Sort byId(String id) {
            for (Sort sort : values()) {
                if (sort.id.equals(id)) {
                    return sort;
                }
            }
            return PRICE;
        }
    }

    /** The id of the "everything" filter. */
    static final String ALL = "all";

    private BrowserSort() {
    }

    /** The categories worth a filter button: those some orderable item belongs to, in enum order. */
    static List<ItemCategory> filters(Set<ItemCategory> orderable) {
        List<ItemCategory> list = new ArrayList<>();
        for (ItemCategory category : EnumSet.allOf(ItemCategory.class)) {
            if (orderable.contains(category)) {
                list.add(category);
            }
        }
        return list;
    }

    /** The filter for a stored id: {@link #ALL} or a category id; unknown ids show everything. */
    static Predicate<Order> filter(String id, Function<Order, ItemCategory> categoryOf) {
        ItemCategory category = ItemCategory.byId(id);
        if (category == null) {
            return order -> true;
        }
        return order -> categoryOf.apply(order) == category;
    }

    /** Applies a filter and a sort to a snapshot of orders (what the browser shows, before search and paging). */
    static List<Order> view(List<Order> orders, Sort sort, Predicate<Order> filter) {
        List<Order> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            if (filter.test(order)) {
                result.add(order);
            }
        }
        result.sort(sort.comparator());
        return result;
    }
}
