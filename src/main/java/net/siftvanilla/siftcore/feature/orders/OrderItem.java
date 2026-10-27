package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * The exact item an order takes: a canonical prototype stack. Deliveries match it with {@code isSimilar} (type and
 * every data component, amount aside) and the buyer collects copies of it, so nothing an item carried can be lost or
 * duplicated on the way:
 * <ul>
 *   <li>a plain order's prototype is the type's default item: a custom name, lore, enchantments, damage, a repair
 *       cost, custom data, contents, trims or potion effects all make a stack a different item;</li>
 *   <li>a variant's prototype is the one canonical item of that variant (see {@link Variant}).</li>
 * </ul>
 * Immutable; the prototype is never handed out, only copies.
 */
final class OrderItem {

    private final String key;
    private final String itemType;
    private final Variant variant;
    private final Material material;
    private final ItemStack prototype;
    private final Component name;
    private final String plainName;

    OrderItem(String itemType, Variant variant, Material material, ItemStack prototype, Component name, String plainName) {
        this.itemType = itemType;
        this.variant = variant;
        this.key = OrderKeys.key(itemType, variant == null ? null : variant.id());
        this.material = material;
        this.prototype = prototype.asOne();
        this.name = name;
        this.plainName = plainName;
    }

    /** The order key ({@code minecraft:diamond}, {@code minecraft:enchanted_book|enchant:minecraft:mending:1}). */
    String key() {
        return this.key;
    }

    String itemType() {
        return this.itemType;
    }

    /** The variant, or null for plain items. */
    Variant variant() {
        return this.variant;
    }

    /** The stored variant id, or null for plain items. */
    String variantId() {
        return this.variant == null ? null : this.variant.id();
    }

    Material material() {
        return this.material;
    }

    /** A copy of the canonical item (amount 1). */
    ItemStack prototype() {
        return this.prototype.clone();
    }

    /** The item's name, unstyled (the client shows it in its own language where it can). */
    Component name() {
        return this.name;
    }

    /** The item's English name, for search, suggestions and logs. */
    String plainName() {
        return this.plainName;
    }

    int maxStack() {
        return Math.max(1, this.prototype.getMaxStackSize());
    }

    /**
     * A copy to show in a menu or dialog, {@code amount} high but never more than one stack of it (an unstackable item
     * shows one).
     */
    ItemStack display(int amount) {
        return this.prototype.asQuantity(Math.clamp(amount, 1, maxStack()));
    }

    /** The menu icon of an order: its item, or paper when the item can't be built. */
    static ItemStack icon(OrderItem item, int amount) {
        return item == null ? ItemStack.of(org.bukkit.Material.PAPER, Math.clamp(amount, 1, 64)) : item.display(amount);
    }

    /** True when the stack is exactly this item (any amount). */
    boolean matches(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getType() == this.material && stack.isSimilar(this.prototype);
    }

    /** Copies of the item adding up to {@code amount}, each at most a full stack. */
    List<ItemStack> stacks(int amount) {
        if (amount <= 0) {
            return List.of();
        }
        int max = maxStack();
        List<ItemStack> stacks = new ArrayList<>(amount / max + 1);
        int left = amount;
        while (left > 0) {
            int size = Math.min(max, left);
            stacks.add(this.prototype.asQuantity(size));
            left -= size;
        }
        return Collections.unmodifiableList(stacks);
    }

    /** Total amount in a list of stacks. */
    static int count(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * How many of this item fit into the given storage contents (hotbar and main inventory), at most {@code cap}:
     * free room on matching stacks plus whole empty slots.
     */
    int space(ItemStack[] storage, int cap) {
        int max = maxStack();
        long space = 0;
        for (ItemStack stack : storage) {
            if (stack == null || stack.isEmpty()) {
                space += max;
            } else if (matches(stack)) {
                space += Math.max(0, max - stack.getAmount());
            }
            if (space >= cap) {
                return cap;
            }
        }
        return (int) Math.min(space, cap);
    }

    @Override
    public String toString() {
        return this.key;
    }
}
