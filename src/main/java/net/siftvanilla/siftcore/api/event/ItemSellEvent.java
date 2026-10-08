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
 * Fired before a player sells items to the server ({@code /sell}, {@code /sell hand}, {@code /sell all}), after the
 * sale was priced and before anything is taken or paid. Cancelling stops the sale: the items stay where they are.
 * Fired on the player's thread.
 */
public final class ItemSellEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Where the items come from. */
    public enum Source {
        /** The /sell menu. */
        MENU,
        /** The item in the main hand. */
        HAND,
        /** The main inventory (never armor or the off hand). */
        ALL
    }

    private final Player player;
    private final Source source;
    private final List<ItemStack> items;
    private final long total;
    private final double multiplier;

    public ItemSellEvent(Player player, Source source, List<ItemStack> items, long total, double multiplier) {
        this.player = player;
        this.source = source;
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copies.add(item.clone());
        }
        this.items = Collections.unmodifiableList(copies);
        this.total = total;
        this.multiplier = multiplier;
    }

    public Player player() {
        return this.player;
    }

    public Source source() {
        return this.source;
    }

    /** Copies of the stacks being sold. */
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

    /** The money the player receives, with their multiplier applied. */
    public long total() {
        return this.total;
    }

    /** The player's sell multiplier (1.0 when they have none). */
    public double multiplier() {
        return this.multiplier;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
