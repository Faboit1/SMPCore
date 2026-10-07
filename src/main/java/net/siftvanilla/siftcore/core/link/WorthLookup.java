package net.siftvanilla.siftcore.core.link;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The sell value of items. Implemented by the sell feature (worth table). Thread-safe. */
public interface WorthLookup {

    WorthLookup NONE = new WorthLookup() {
        @Override
        public long unitPrice(ItemStack item) {
            return 0;
        }

        @Override
        public double multiplier(Player player) {
            return 1.0;
        }
    };

    /** What the server pays for one of this item, 0 when it can't be sold (damaged, renamed, unknown). */
    long unitPrice(ItemStack item);

    /** The player's rank sell multiplier (1.0 = none). */
    double multiplier(Player player);

    /** Total base value of the stack (unit price times amount). */
    default long price(ItemStack item) {
        long unit = unitPrice(item);
        return unit <= 0 ? 0 : Math.multiplyExact(unit, item.getAmount());
    }

    /** Total value of the stack for this player, with their multiplier, rounded down. */
    default long priceFor(Player player, ItemStack item) {
        long base = price(item);
        return base <= 0 ? 0 : (long) Math.floor(base * multiplier(player));
    }
}
