package net.siftvanilla.siftcore.feature.sell;

import java.util.Objects;
import net.siftvanilla.siftcore.api.event.ItemSellEvent;

/**
 * What a player asked to sell.
 *
 * @param source what the sale is reported as (events, ledger ref)
 * @param scope  which items are looked at
 * @param target the item key ({@link Scope#TYPE}) or category id ({@link Scope#CATEGORY}), else null
 */
record SaleRequest(ItemSellEvent.Source source, Scope scope, String target) {

    /** Which items a sale looks at. */
    enum Scope {
        /** The stack in the main hand (or what is inside it, for a shulker box). */
        HAND,
        /** Every plain stack of one item type in the hotbar and storage, and inside shulker boxes there. */
        TYPE,
        /** The hotbar and storage, as {@code sell-all} says. */
        ALL,
        /** {@link #ALL}, only items of one sell category. */
        CATEGORY,
        /** The grid of the sell menu. */
        MENU
    }

    SaleRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scope, "scope");
    }

    static SaleRequest hand() {
        return new SaleRequest(ItemSellEvent.Source.HAND, Scope.HAND, null);
    }

    /** {@code /sell hand all} and the worth details' "Sell your ..." button: every plain stack of one item. */
    static SaleRequest type(String key) {
        return new SaleRequest(ItemSellEvent.Source.HAND_ALL, Scope.TYPE, key);
    }

    static SaleRequest all() {
        return new SaleRequest(ItemSellEvent.Source.ALL, Scope.ALL, null);
    }

    static SaleRequest category(String id) {
        return new SaleRequest(ItemSellEvent.Source.CATEGORY, Scope.CATEGORY, id);
    }

    static SaleRequest menu() {
        return new SaleRequest(ItemSellEvent.Source.MENU, Scope.MENU, null);
    }

    /** Whether items the server doesn't buy may go to buy orders ({@code /sell all} only moves what the server buys). */
    boolean routesUnpriced() {
        return this.scope == Scope.HAND || this.scope == Scope.TYPE || this.scope == Scope.MENU;
    }
}
