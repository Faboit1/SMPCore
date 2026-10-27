package net.siftvanilla.siftcore.feature.sell;

import java.util.OptionalLong;
import org.bukkit.entity.Player;

/**
 * The server shop as selling sees it: the worth browser, the item details and {@code /worth} show the shop price
 * next to the sell price and can open the purchase dialog. Implemented by the shop feature; thread-safe reads.
 */
public interface ShopOffers {

    ShopOffers NONE = new ShopOffers() {
        @Override
        public OptionalLong price(String itemKey) {
            return OptionalLong.empty();
        }

        @Override
        public boolean open(Player player, String itemKey, Runnable back) {
            return false;
        }
    };

    /** The lowest shop price of the plain item ({@code minecraft:diamond}), or empty when the shop doesn't sell it. */
    OptionalLong price(String itemKey);

    /** Opens the purchase dialog of the item (player's thread); false when the shop doesn't sell it. */
    boolean open(Player player, String itemKey, Runnable back);
}
