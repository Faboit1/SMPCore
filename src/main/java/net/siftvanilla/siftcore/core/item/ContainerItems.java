package net.siftvanilla.siftcore.core.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

/**
 * Items that hold other items: shulker boxes (the {@code container} component, 27 positions) and bundles (the
 * {@code bundle_contents} component, an ordered list). Selling and order delivery take matching items out of them
 * without opening them.
 * <p>
 * The rules: a box is only opened while it is a single item (a stacked box, which plain gameplay can't make, is
 * never touched); nested containers are never opened (a box inside a bundle stays as it is); stacks that don't match
 * stay exactly where they are; and a box whose contents were all taken goes back to its plain state, so an emptied
 * plain shulker box is a plain shulker box again. The box's own name, colour, lock and custom data never change.
 * <p>
 * The taking logic ({@link #extract(List, Predicate, long, Stacks)}) is generic so it can be tested without a
 * server; the {@link ItemStack} methods are thin wrappers around it.
 */
public final class ContainerItems {

    /** What the generic logic needs to know about a stack type. */
    public interface Stacks<T> {

        /** True for an empty position. */
        boolean empty(T stack);

        int amount(T stack);

        /** A copy of the stack with another amount (at least 1). */
        T withAmount(T stack, int amount);

        /** The empty stack used to keep a position free. */
        T emptyStack();
    }

    /**
     * What was taken out of a container.
     *
     * @param taken     the stacks taken, in content order (whole or partial copies)
     * @param remaining the contents afterwards, same size and positions as before (emptied positions hold empty
     *                  stacks)
     * @param units     how many items were taken in total
     */
    public record Extraction<T>(List<T> taken, List<T> remaining, long units) {

        public Extraction {
            taken = List.copyOf(taken);
            remaining = List.copyOf(remaining);
        }

        public boolean nothing() {
            return this.units == 0;
        }
    }

    /** The {@link Stacks} view of Bukkit item stacks. */
    public static final Stacks<ItemStack> ITEM_STACKS = new Stacks<>() {
        @Override
        public boolean empty(ItemStack stack) {
            return stack == null || stack.isEmpty();
        }

        @Override
        public int amount(ItemStack stack) {
            return stack.getAmount();
        }

        @Override
        public ItemStack withAmount(ItemStack stack, int amount) {
            return stack.asQuantity(amount);
        }

        @Override
        public ItemStack emptyStack() {
            return ItemStack.empty();
        }
    };

    private ContainerItems() {
    }

    // ------------------------------------------------------------------ generic logic (pure)

    /**
     * Takes stacks accepted by {@code take} out of {@code contents}, whole or partially, until {@code maxUnits}
     * items were taken. Stacks that are not accepted, and the part of a stack beyond the cap, stay where they are.
     * Containers inside the container are never opened: {@code take} decides about them as plain stacks.
     *
     * @throws IllegalArgumentException when {@code maxUnits} is negative
     */
    public static <T> Extraction<T> extract(List<T> contents, Predicate<T> take, long maxUnits, Stacks<T> stacks) {
        if (maxUnits < 0) {
            throw new IllegalArgumentException("Can't take a negative amount");
        }
        List<T> remaining = new ArrayList<>(contents.size());
        List<T> taken = new ArrayList<>();
        long left = maxUnits;
        for (T stack : contents) {
            if (stacks.empty(stack) || left == 0 || !take.test(stack)) {
                remaining.add(stacks.empty(stack) ? stacks.emptyStack() : stack);
                continue;
            }
            int amount = stacks.amount(stack);
            int part = (int) Math.min(amount, left);
            taken.add(stacks.withAmount(stack, part));
            left -= part;
            remaining.add(part == amount ? stacks.emptyStack() : stacks.withAmount(stack, amount - part));
        }
        return new Extraction<>(taken, remaining, maxUnits - left);
    }

    /**
     * Whether a stack may be opened: it is a container type and a single item. A stacked container (which plain
     * gameplay can't make) is never opened, so taking from it can't multiply its contents.
     */
    public static boolean openable(boolean containerType, int amount) {
        return containerType && amount == 1;
    }

    /** True when every position is empty (the container should go back to its plain state). */
    public static <T> boolean allEmpty(List<T> contents, Stacks<T> stacks) {
        for (T stack : contents) {
            if (!stacks.empty(stack)) {
                return false;
            }
        }
        return true;
    }

    /** The positions that hold something, without the empty ones (bundles keep no empty positions). */
    public static <T> List<T> compact(List<T> contents, Stacks<T> stacks) {
        List<T> result = new ArrayList<>(contents.size());
        for (T stack : contents) {
            if (!stacks.empty(stack)) {
                result.add(stack);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ shulker boxes

    /** A shulker box that may be opened: one of the shulker box items, as a single item. */
    public static boolean isShulker(ItemStack stack) {
        return stack != null && !stack.isEmpty() && openable(Tag.ITEMS_SHULKER_BOXES.isTagged(stack.getType()), stack.getAmount());
    }

    /** Any shulker box item, stacked or not. */
    public static boolean isShulkerType(Material material) {
        return Tag.ITEMS_SHULKER_BOXES.isTagged(material);
    }

    /** The box's contents with their positions (empty positions are empty stacks); empty for an empty box. */
    public static List<ItemStack> contents(ItemStack box) {
        ItemContainerContents contents = box.getData(DataComponentTypes.CONTAINER);
        if (contents == null) {
            return List.of();
        }
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack stack : contents.contents()) {
            list.add(stack == null || stack.isEmpty() ? ItemStack.empty() : stack.clone());
        }
        return list;
    }

    /** Takes matching stacks out of box contents; see {@link #extract(List, Predicate, long, Stacks)}. */
    public static Extraction<ItemStack> extract(List<ItemStack> contents, Predicate<ItemStack> take, long maxUnits) {
        return extract(contents, take, maxUnits, ITEM_STACKS);
    }

    /**
     * A copy of {@code box} holding {@code remaining}. When nothing is left the contents go back to the item's
     * default, so an emptied plain box is plain again. Everything else about the box is kept.
     */
    public static ItemStack rebuild(ItemStack box, List<ItemStack> remaining) {
        ItemStack copy = box.clone();
        if (allEmpty(remaining, ITEM_STACKS)) {
            copy.resetData(DataComponentTypes.CONTAINER);
        } else {
            copy.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(remaining));
        }
        return copy;
    }

    // ------------------------------------------------------------------ bundles

    /** A bundle that may be opened: one of the bundle items, as a single item. */
    public static boolean isBundle(ItemStack stack) {
        return stack != null && !stack.isEmpty() && openable(Tag.ITEMS_BUNDLES.isTagged(stack.getType()), stack.getAmount());
    }

    /** The bundle's contents in order; empty for an empty bundle. */
    public static List<ItemStack> bundleContents(ItemStack bundle) {
        BundleContents contents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null) {
            return List.of();
        }
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack stack : contents.contents()) {
            if (stack != null && !stack.isEmpty()) {
                list.add(stack.clone());
            }
        }
        return list;
    }

    /** A copy of {@code bundle} holding {@code remaining} (empty positions dropped), plain again when empty. */
    public static ItemStack rebuildBundle(ItemStack bundle, List<ItemStack> remaining) {
        ItemStack copy = bundle.clone();
        List<ItemStack> kept = compact(remaining, ITEM_STACKS);
        if (kept.isEmpty()) {
            copy.resetData(DataComponentTypes.BUNDLE_CONTENTS);
        } else {
            copy.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(kept));
        }
        return copy;
    }

    // ------------------------------------------------------------------ either kind

    /** Which kind of container a stack is, or null when it is not one that may be opened. */
    public static Kind kind(ItemStack stack) {
        if (isShulker(stack)) {
            return Kind.SHULKER_BOX;
        }
        if (isBundle(stack)) {
            return Kind.BUNDLE;
        }
        return null;
    }

    /** The two container kinds. */
    public enum Kind {
        SHULKER_BOX,
        BUNDLE;

        public List<ItemStack> contents(ItemStack container) {
            return this == SHULKER_BOX ? ContainerItems.contents(container) : bundleContents(container);
        }

        public ItemStack rebuild(ItemStack container, List<ItemStack> remaining) {
            return this == SHULKER_BOX ? ContainerItems.rebuild(container, remaining) : rebuildBundle(container, remaining);
        }
    }
}
