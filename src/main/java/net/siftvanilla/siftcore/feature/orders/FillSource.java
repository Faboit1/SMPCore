package net.siftvanilla.siftcore.feature.orders;

import net.siftvanilla.siftcore.api.event.OrderFillEvent;

/** How items reached an order; stored in {@code order_fills.source}. */
enum FillSource {
    /** The order's delivery menu. */
    MENU("menu", OrderFillEvent.Source.MENU),
    /** Quick deliver, straight from the inventory. */
    QUICK("quick", OrderFillEvent.Source.QUICK),
    /** Routed from /sell through the order market. */
    SELL("sell", OrderFillEvent.Source.SELL);

    private final String id;
    private final OrderFillEvent.Source event;

    FillSource(String id, OrderFillEvent.Source event) {
        this.id = id;
        this.event = event;
    }

    /** The stored id. */
    String id() {
        return this.id;
    }

    OrderFillEvent.Source event() {
        return this.event;
    }

    /** The source of a stored id; unknown ids read as the menu (the column's default). */
    static FillSource byId(String id) {
        for (FillSource source : values()) {
            if (source.id.equals(id)) {
                return source;
            }
        }
        return MENU;
    }
}
