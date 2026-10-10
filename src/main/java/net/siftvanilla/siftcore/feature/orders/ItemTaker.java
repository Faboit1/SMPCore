package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Takes the items of a delivery out of a range of inventory slots (a delivery menu's grid, or a player's hotbar and
 * main inventory): matching stacks first, then the matching contents of single shulker boxes, which are swapped for a
 * rebuilt copy. Everything happens on the inventory owner's thread, and every slot is checked against the snapshot
 * the plan was made from before it is changed, so a plan can never take what is not there.
 * <p>
 * Remove before grant: {@link #apply} before the payment, {@link #restore} when the payment fails.
 */
final class ItemTaker {

    /**
     * One slot of a plan.
     *
     * @param slot     the inventory slot
     * @param original what the slot held when planned
     * @param after    what it holds once taken from (null: empty)
     * @param taken    what was taken out of it
     * @param units    how many items were taken
     * @param box      whether it is a shulker box whose contents were taken
     */
    record Pick(int slot, ItemStack original, ItemStack after, List<ItemStack> taken, int units, boolean box) {
        Pick {
            original = original.clone();
            after = after == null ? null : after.clone();
            taken = taken.stream().map(ItemStack::clone).toList();
        }
    }

    /** A plan: the slots to take from, in order, and the units in total. */
    record Plan(List<Pick> picks, int units) {
        Plan {
            picks = List.copyOf(picks);
        }

        /** Copies of every stack the plan takes. */
        List<ItemStack> taken() {
            List<ItemStack> all = new ArrayList<>();
            for (Pick pick : this.picks) {
                for (ItemStack stack : pick.taken()) {
                    all.add(stack.clone());
                }
            }
            return all;
        }

        /** Units taken from inside shulker boxes. */
        int inner() {
            int total = 0;
            for (Pick pick : this.picks) {
                if (pick.box()) {
                    total += pick.units();
                }
            }
            return total;
        }
    }

    /**
     * What a range of slots holds for an order.
     *
     * @param outer    matching items in plain stacks
     * @param inner    matching items inside single shulker boxes
     * @param rejected items that are not taken: other items, stacked boxes, boxes with nothing that matches
     */
    record Count(int outer, int inner, int rejected) {
        int total() {
            return this.outer + this.inner;
        }
    }

    private ItemTaker() {
    }

    /** Counts what slots {@code from} (inclusive) to {@code to} (exclusive) hold for {@code item}. */
    static Count count(Inventory inventory, int from, int to, OrderItem item) {
        long outer = 0;
        long inner = 0;
        long rejected = 0;
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (item.matches(stack)) {
                outer += stack.getAmount();
            } else if (ContainerItems.isShulker(stack)) {
                long inside = inside(stack, item);
                if (inside > 0) {
                    inner += inside;
                } else {
                    rejected += stack.getAmount();
                }
            } else {
                rejected += stack.getAmount();
            }
        }
        return new Count(clamp(outer), clamp(inner), clamp(rejected));
    }

    private static int clamp(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    /** How many items in a single shulker box match the order (0 for anything that is not a single box). */
    static long inside(ItemStack box, OrderItem item) {
        if (!ContainerItems.isShulker(box)) {
            return 0;
        }
        long total = 0;
        for (ItemStack stack : ContainerItems.contents(box)) {
            if (item.matches(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Plans taking up to {@code units} items: whole and partial matching stacks in slot order, then the contents of
     * shulker boxes in slot order. The last stack taken from may be split.
     */
    static Plan plan(Inventory inventory, int from, int to, OrderItem item, int units) {
        List<Pick> picks = new ArrayList<>();
        int left = Math.max(0, units);
        for (int slot = from; slot < to && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!item.matches(stack)) {
                continue;
            }
            int part = Math.min(left, stack.getAmount());
            ItemStack after = part == stack.getAmount() ? null : stack.asQuantity(stack.getAmount() - part);
            picks.add(new Pick(slot, stack, after, item.stacks(part), part, false));
            left -= part;
        }
        for (int slot = from; slot < to && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!ContainerItems.isShulker(stack) || item.matches(stack)) {
                continue;
            }
            ContainerItems.Extraction<ItemStack> extraction = ContainerItems.extract(ContainerItems.contents(stack), item::matches, left);
            if (extraction.nothing()) {
                continue;
            }
            int part = (int) extraction.units();
            picks.add(new Pick(slot, stack, ContainerItems.rebuild(stack, extraction.remaining()), extraction.taken(), part, true));
            left -= part;
        }
        return new Plan(picks, Math.max(0, units) - left);
    }

    /**
     * Applies a plan if every slot still holds exactly what was planned. On the first slot that changed, the slots
     * already changed are put back and false is returned, so either the whole plan applies or nothing does.
     */
    static boolean apply(Inventory inventory, Plan plan) {
        List<Pick> done = new ArrayList<>();
        for (Pick pick : plan.picks()) {
            ItemStack now = inventory.getItem(pick.slot());
            if (now == null || !now.equals(pick.original())) {
                for (Pick undo : done) {
                    inventory.setItem(undo.slot(), undo.original().clone());
                }
                return false;
            }
            inventory.setItem(pick.slot(), pick.after() == null ? null : pick.after().clone());
            done.add(pick);
        }
        return true;
    }

    /**
     * Undoes an applied plan: every slot that still holds what the plan left gets its original back. Returns the items
     * of the slots that changed meanwhile, which the caller must give back another way (they are never lost).
     */
    static List<ItemStack> restore(Inventory inventory, Plan plan) {
        List<ItemStack> giveBack = new ArrayList<>();
        for (Pick pick : plan.picks()) {
            ItemStack now = inventory.getItem(pick.slot());
            boolean untouched = pick.after() == null ? now == null || now.isEmpty() : pick.after().equals(now);
            if (untouched) {
                inventory.setItem(pick.slot(), pick.original().clone());
            } else {
                for (ItemStack stack : pick.taken()) {
                    giveBack.add(stack.clone());
                }
            }
        }
        return giveBack;
    }
}
