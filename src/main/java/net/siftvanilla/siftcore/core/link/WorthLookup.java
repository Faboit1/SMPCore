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

    /**
     * What a player's sales are multiplied by right now, and why.
     *
     * @param multiplier what to multiply the worth by: {@code rank} with the booster on top
     * @param rank       the player's own rank multiplier (1.0 = none)
     * @param boost      the server sell booster running now, in percent (0 = none)
     */
    record SellRate(double multiplier, double rank, int boost) {
    }

    /** What the server pays for one of this item, 0 when it can't be sold (damaged, renamed, unknown). */
    long unitPrice(ItemStack item);

    /** The player's sell multiplier: their rank multiplier with the running server sell booster on top (1.0 = none). */
    double multiplier(Player player);

    /**
     * The player's sell multiplier and what it is made of, read once (so a price and the receipt that explains it
     * always agree, even when a booster ends in between).
     */
    default SellRate rate(Player player) {
        double multiplier = multiplier(player);
        return new SellRate(multiplier, multiplier, 0);
    }

    /** Total base value of the stack (unit price times amount). */
    default long price(ItemStack item) {
        long unit = unitPrice(item);
        return unit <= 0 ? 0 : Math.multiplyExact(unit, item.getAmount());
    }

    /** Total value of the stack for this player, with their multiplier (and the running booster), rounded down. */
    default long priceFor(Player player, ItemStack item) {
        long base = price(item);
        return base <= 0 ? 0 : (long) Math.floor(base * multiplier(player));
    }
}
