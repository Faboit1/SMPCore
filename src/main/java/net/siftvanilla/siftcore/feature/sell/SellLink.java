package net.siftvanilla.siftcore.feature.sell;

import org.bukkit.entity.Player;

/**
 * Selling as the shop sees it: what the viewer would get back for an item, how many they carry, and selling them
 * from a shop page. Implemented by the sell feature.
 */
public interface SellLink {

    SellLink NONE = new SellLink() {
        @Override
        public long sellBack(Player player, String itemKey) {
            return 0;
        }

        @Override
        public long carried(Player player, String itemKey) {
            return 0;
        }

        @Override
        public boolean offerSell(Player player, String itemKey, Runnable back) {
            return false;
        }
    };

    /** What one plain item sells for to this player (rank and mastery included), 0 when the server doesn't buy it. */
    long sellBack(Player player, String itemKey);

    /** How many plain items of the kind the player carries (hotbar, storage and shulker boxes). Player's thread. */
    long carried(Player player, String itemKey);

    /**
     * Asks the player whether to sell every plain item of the kind they carry (always with a confirmation). False
     * when they have none to sell. Player's thread.
     */
    boolean offerSell(Player player, String itemKey, Runnable back);
}
