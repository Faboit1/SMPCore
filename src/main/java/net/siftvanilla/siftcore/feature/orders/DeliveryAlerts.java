package net.siftvanilla.siftcore.feature.orders;

import net.siftvanilla.siftcore.core.player.options.AlertStyle;

/**
 * The values of the {@code order-notices} setting: how an owner hears about items arriving for their orders. The ids
 * are stored; chat, actionbar and off share the labels of the notification vocabulary.
 */
public enum DeliveryAlerts {
    /** Every delivery as a chat line. */
    CHAT("chat"),
    /** Every delivery above the hotbar. */
    ACTIONBAR("actionbar"),
    /** Only a line when an order is complete. */
    COMPLETE("complete"),
    /** Nothing about deliveries (expiries and staff cancellations still show). */
    OFF("off");

    private final String id;

    DeliveryAlerts(String id) {
        this.id = id;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    /** Whether each delivery is told (not only completions). */
    public boolean everyDelivery() {
        return this == CHAT || this == ACTIONBAR;
    }

    /** Whether completed orders are told. */
    public boolean completions() {
        return this != OFF;
    }

    /** Where the lines go: above the hotbar for {@link #ACTIONBAR}, otherwise chat. */
    public AlertStyle place() {
        return this == ACTIONBAR ? AlertStyle.ACTIONBAR : AlertStyle.CHAT;
    }
}
