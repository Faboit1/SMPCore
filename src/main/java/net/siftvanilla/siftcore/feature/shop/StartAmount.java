package net.siftvanilla.siftcore.feature.shop;

/**
 * The values of the {@code shop-default-amount} setting: how many the purchase dialog starts with. The ids are
 * stored. Pure ({@link #start}), so it is unit tested; the purchase flow passes what it knows about the player.
 */
public enum StartAmount {
    /** One stack (one for spawners), or the purchase limit when that is lower: the shop's usual start. */
    STACK("stack"),
    /** One item. */
    ONE("one"),
    /** The amount of the player's last purchase of this entry (one stack when they never bought it). */
    LAST("last"),
    /** As many as fit into the inventory (one stack when nothing fits). */
    FILL("fill");

    private final String id;

    StartAmount(String id) {
        this.id = id;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    /**
     * The amount the dialog starts at, from 1 to {@code max}.
     *
     * @param stackSize  the size of one stack of the entry (1 for spawners)
     * @param max        the most one purchase may buy
     * @param lastAmount the amount of the player's last purchase of this entry, 0 when none
     * @param capacity   how many more of the item fit into the inventory (only read for {@link #FILL})
     */
    public int start(int stackSize, int max, int lastAmount, long capacity) {
        int stack = PurchaseMath.defaultAmount(stackSize, max);
        return switch (this) {
            case STACK -> stack;
            case ONE -> 1;
            case LAST -> lastAmount > 0 ? Math.max(1, Math.min(lastAmount, max)) : stack;
            case FILL -> {
                int fill = PurchaseMath.fill(capacity, max);
                yield fill > 0 ? fill : stack;
            }
        };
    }
}
