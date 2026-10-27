package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.siftvanilla.siftcore.api.event.ItemSellEvent;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A sale worked out from an inventory, before anything moved: exactly which slots and container contents it takes,
 * what the server pays, what buy orders pay and what it adds to mastery. A confirmation dialog keeps the draft it
 * showed; when the player confirms, the sale is worked out again from the live inventory and only goes ahead when
 * the new draft is the same ({@link #sameAs}), so nothing is ever sold that the player did not see.
 *
 * @param source      where the items come from
 * @param inventory   the inventory the slots belong to (the player's, or the sell menu)
 * @param stacks      whole or partial stacks taken from slots
 * @param containers  shulker boxes and bundles whose contents are taken
 * @param server      what the server buys, one line per item
 * @param multipliers the multiplier of each category in {@code server}, with the server booster on top
 * @param serverTotal what the server pays (each category rounded down once)
 * @param takes       units sent to buy orders
 * @param owners      who placed each order taking part
 * @param ordersGross what the orders pay before tax
 * @param ordersTax   the order tax
 * @param credits     base value added to each category's mastery
 * @param kept        items looked at that stay (can't be sold, don't stack, or no buyer)
 * @param boost       the server sell booster the server part was priced with, in percent (0 = none); buy orders
 *                    pay their own price and are never boosted
 */
record SaleDraft(
    ItemSellEvent.Source source,
    Inventory inventory,
    List<Stack> stacks,
    List<Container> containers,
    SalePlan server,
    Map<String, BigDecimal> multipliers,
    long serverTotal,
    List<OrderMarket.Take> takes,
    Map<Long, UUID> owners,
    long ordersGross,
    long ordersTax,
    Map<String, Long> credits,
    long kept,
    int boost) {

    /**
     * Units taken from one slot.
     *
     * @param slot     the slot
     * @param snapshot a copy of the whole stack as it was
     * @param key      the item key
     * @param units    how many are taken (the rest stays in the slot)
     */
    record Stack(int slot, ItemStack snapshot, String key, int units) {
        Stack {
            snapshot = snapshot.clone();
            if (units < 1 || units > snapshot.getAmount()) {
                throw new IllegalArgumentException("Take 1 to " + snapshot.getAmount() + " units, not " + units);
            }
        }

        /** What stays in the slot, or null when the whole stack is taken. */
        ItemStack remainder() {
            int left = this.snapshot.getAmount() - this.units;
            return left == 0 ? null : this.snapshot.asQuantity(left);
        }

        /** The units taken, as a stack. */
        ItemStack taken() {
            return this.snapshot.asQuantity(this.units);
        }

        boolean sameAs(Stack other) {
            return this.slot == other.slot && this.units == other.units && this.key.equals(other.key)
                && this.snapshot.equals(other.snapshot);
        }
    }

    /**
     * Contents taken out of a container in one slot.
     *
     * @param slot     the slot
     * @param kind     shulker box or bundle
     * @param original the container as it was
     * @param rebuilt  the container without what is taken
     * @param taken    the stacks taken, in content order
     * @param units    units taken per item key
     */
    record Container(int slot, ContainerItems.Kind kind, ItemStack original, ItemStack rebuilt, List<ItemStack> taken,
                     Map<String, Long> units) {
        Container {
            original = original.clone();
            rebuilt = rebuilt.clone();
            List<ItemStack> copies = new ArrayList<>(taken.size());
            for (ItemStack stack : taken) {
                copies.add(stack.clone());
            }
            taken = List.copyOf(copies);
            units = Map.copyOf(new LinkedHashMap<>(units));
        }

        long count() {
            long sum = 0;
            for (long value : this.units.values()) {
                sum += value;
            }
            return sum;
        }

        boolean sameAs(Container other) {
            return this.slot == other.slot && this.kind == other.kind && this.original.equals(other.original)
                && this.rebuilt.equals(other.rebuilt) && this.taken.equals(other.taken);
        }
    }

    SaleDraft {
        Objects.requireNonNull(source, "source");
        stacks = List.copyOf(stacks);
        containers = List.copyOf(containers);
        multipliers = Map.copyOf(multipliers);
        takes = List.copyOf(takes);
        owners = Map.copyOf(owners);
        credits = Map.copyOf(credits);
    }

    /** True when nothing would be sold. */
    boolean empty() {
        return this.stacks.isEmpty() && this.containers.isEmpty();
    }

    /** What the orders pay after tax. */
    long ordersNet() {
        return this.ordersGross - this.ordersTax;
    }

    /** What the seller receives in total. */
    long total() {
        return Math.addExact(this.serverTotal, ordersNet());
    }

    /** Items sold in total. */
    long count() {
        long sum = 0;
        for (Stack stack : this.stacks) {
            sum += stack.units();
        }
        return sum + innerCount();
    }

    /** Items sold out of containers. */
    long innerCount() {
        long sum = 0;
        for (Container container : this.containers) {
            sum += container.count();
        }
        return sum;
    }

    /** Distinct orders the sale goes to. */
    long orderCount() {
        return this.takes.stream().mapToLong(OrderMarket.Take::orderId).distinct().count();
    }

    /** Units of every item sold (server and orders), in the order they were found. */
    Map<String, Long> units() {
        Map<String, Long> units = new LinkedHashMap<>();
        for (Stack stack : this.stacks) {
            units.merge(stack.key(), (long) stack.units(), Long::sum);
        }
        for (Container container : this.containers) {
            container.units().forEach((key, amount) -> units.merge(key, amount, Long::sum));
        }
        return units;
    }

    /** Copies of everything sold, for events and for giving back. */
    List<ItemStack> items() {
        List<ItemStack> items = new ArrayList<>();
        for (Stack stack : this.stacks) {
            items.add(stack.taken());
        }
        for (Container container : this.containers) {
            for (ItemStack stack : container.taken()) {
                items.add(stack.clone());
            }
        }
        return items;
    }

    /** One multiplier when every category shares it (for the "your 1.5x bonus" line), else null. */
    BigDecimal sharedMultiplier() {
        BigDecimal shared = null;
        for (String category : this.server.categoryBase().keySet()) {
            BigDecimal value = this.multipliers.get(category);
            if (shared == null) {
                shared = value;
            } else if (value == null || shared.compareTo(value) != 0) {
                return null;
            }
        }
        return shared;
    }

    /** The highest multiplier of the sale (1 when none applies). */
    BigDecimal topMultiplier() {
        BigDecimal top = BigDecimal.ONE;
        for (String category : this.server.categoryBase().keySet()) {
            BigDecimal value = this.multipliers.get(category);
            if (value != null && value.compareTo(top) > 0) {
                top = value;
            }
        }
        return top;
    }

    /** The player's own bonus when every category shares it (rank and mastery, without the booster), else null. */
    BigDecimal sharedBonus() {
        BigDecimal shared = sharedMultiplier();
        return shared == null ? null : Boosts.remove(shared, this.boost);
    }

    /** The player's highest own bonus in the sale (without the booster; 1 when none applies). */
    BigDecimal topBonus() {
        return Boosts.remove(topMultiplier(), this.boost);
    }

    /** Whether a server booster raised what the server pays in this sale. */
    boolean boosted() {
        return this.boost > 0 && this.serverTotal > 0;
    }

    /**
     * True when {@code other} takes exactly the same slots, stacks, container contents and orders for exactly the
     * same totals. Anything else means the inventory (or the orders) changed since this draft was shown.
     */
    boolean sameAs(SaleDraft other) {
        if (other == null || this.source != other.source || this.stacks.size() != other.stacks.size()
            || this.containers.size() != other.containers.size() || !this.takes.equals(other.takes)
            || this.serverTotal != other.serverTotal || this.ordersGross != other.ordersGross
            || this.ordersTax != other.ordersTax) {
            return false;
        }
        for (int i = 0; i < this.stacks.size(); i++) {
            if (!this.stacks.get(i).sameAs(other.stacks.get(i))) {
                return false;
            }
        }
        for (int i = 0; i < this.containers.size(); i++) {
            if (!this.containers.get(i).sameAs(other.containers.get(i))) {
                return false;
            }
        }
        return true;
    }
}
