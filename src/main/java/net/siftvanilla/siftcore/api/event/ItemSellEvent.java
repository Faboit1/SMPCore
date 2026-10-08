package net.siftvanilla.siftcore.api.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired before a player sells items ({@code /sell}, {@code /sell hand}, {@code /sell hand all}, {@code /sell all},
 * category selling), after the sale was priced and before anything is taken or paid. Fired once per sale. Cancelling
 * stops the sale: the items stay where they are. Fired on the player's thread.
 * <p>
 * A sale can pay from two sides: the server buys most items, and buy orders that pay the seller more (after tax)
 * take the units they want first. {@link #total()} is both together.
 */
public final class ItemSellEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Where the items come from. */
    public enum Source {
        /** The /sell menu. */
        MENU,
        /** The item in the main hand (or the contents of a shulker box held there). */
        HAND,
        /** The main inventory (never armor or the off hand). */
        ALL,
        /** Every plain stack of the held item's type ({@code /sell hand all}, and the worth details). */
        HAND_ALL,
        /** Every item of one sell category in the main inventory. */
        CATEGORY
    }

    /**
     * Units sent to one buy order.
     *
     * @param orderId   the order
     * @param units     how many items
     * @param priceEach what the order pays per item before tax
     */
    public record OrderFill(long orderId, long units, long priceEach) {
    }

    private final Player player;
    private final Source source;
    private final List<ItemStack> items;
    private final long serverTotal;
    private final long ordersTotal;
    private final List<OrderFill> orders;
    private final double multiplier;
    private final Map<String, Double> categoryMultipliers;

    /** A sale to the server only, with one multiplier for everything. */
    public ItemSellEvent(Player player, Source source, List<ItemStack> items, long total, double multiplier) {
        this(player, source, items, total, 0, List.of(), multiplier, Map.of());
    }

    /**
     * @param serverTotal         what the server pays
     * @param ordersTotal         what buy orders pay after tax
     * @param orders              the units sent to each order
     * @param multiplier          the player's highest multiplier in this sale (1.0 when none)
     * @param categoryMultipliers the multiplier of each sell category in this sale (rank plus mastery)
     */
    public ItemSellEvent(Player player, Source source, List<ItemStack> items, long serverTotal, long ordersTotal,
                         List<OrderFill> orders, double multiplier, Map<String, Double> categoryMultipliers) {
        this.player = player;
        this.source = source;
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copies.add(item.clone());
        }
        this.items = Collections.unmodifiableList(copies);
        this.serverTotal = serverTotal;
        this.ordersTotal = ordersTotal;
        this.orders = List.copyOf(orders);
        this.multiplier = multiplier;
        this.categoryMultipliers = Map.copyOf(categoryMultipliers);
    }

    public Player player() {
        return this.player;
    }

    public Source source() {
        return this.source;
    }

    /** Copies of the stacks being sold (also what is taken out of shulker boxes). */
    public List<ItemStack> items() {
        List<ItemStack> copies = new ArrayList<>(this.items.size());
        for (ItemStack item : this.items) {
            copies.add(item.clone());
        }
        return copies;
    }

    /** How many of each item type are being sold. */
    public Map<Material, Integer> summary() {
        Map<Material, Integer> summary = new EnumMap<>(Material.class);
        for (ItemStack item : this.items) {
            summary.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return summary;
    }

    /** The money the player receives: the server's part plus what buy orders pay after tax. */
    public long total() {
        return this.serverTotal + this.ordersTotal;
    }

    /** What the server pays, with the player's multipliers applied. */
    public long serverTotal() {
        return this.serverTotal;
    }

    /** What buy orders pay after tax (0 when no order takes part). */
    public long ordersTotal() {
        return this.ordersTotal;
    }

    /** The units sent to buy orders (copies; empty when none). */
    public List<OrderFill> orders() {
        return this.orders;
    }

    /** The player's sell multiplier (the highest in this sale; 1.0 when they have none). */
    public double multiplier() {
        return this.multiplier;
    }

    /** The multiplier of a sell category in this sale (rank plus mastery), or {@link #multiplier()} if not in it. */
    public double multiplier(String category) {
        return this.categoryMultipliers.getOrDefault(category, this.multiplier);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
