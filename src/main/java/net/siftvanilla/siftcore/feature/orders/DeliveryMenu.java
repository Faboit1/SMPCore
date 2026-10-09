package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Delivering to someone's order: the top five rows are a grid the player puts items into (shift click, drag, or the
 * fill button), the bottom row has back, the order (with the item rule), Deliver (how many items would be accepted,
 * how many of them come out of shulker boxes, and what the player gets) and Fill from inventory. Delivering takes
 * exactly the accepted items, plain stacks first and then the contents of shulker boxes (which stay in the grid,
 * emptied of what was delivered); everything else goes back to the player, as does the whole grid when the menu
 * closes (or drops with the death drops when the player dies without keeping their inventory). While items sit in the
 * grid, a copy of them is kept in the player's own data ({@link net.siftvanilla.siftcore.ui.gui.GridBackup}), saved
 * together with their inventory, so a crash can't lose them: a copy still there when the player joins is given back.
 */
final class DeliveryMenu extends Menu implements OrdersView {

    static final int GRID = PagedMenu.PAGE_SIZE;
    static final int SLOT_BACK = PagedMenu.SLOT_BACK;
    static final int SLOT_ORDER = PagedMenu.SLOT_FILTER;
    static final int SLOT_DELIVER = PagedMenu.SLOT_EXTRA_1;
    static final int SLOT_FILL = PagedMenu.SLOT_EXTRA_2;

    private final OrderService service;
    private final long orderId;
    private final long price;
    private final OrderItem item;
    private final Runnable back;

    private DeliveryMenu(OrderService service, Player viewer, Order order, OrderItem item, Runnable back) {
        super(service.services().menus(), viewer, Component.text(service.services().lang().plain(OrdersMessages.DELIVER_TITLE,
            Arg.text("item", item.plainName()))), 6);
        this.service = service;
        this.orderId = order.id();
        this.price = order.priceEach();
        this.item = item;
        this.back = back;
    }

    /** A delivery menu for an order, or null when the order's item can't be built right now. */
    static DeliveryMenu create(OrderService service, Player viewer, Order order, Runnable back) {
        OrderItem item = service.items().of(order);
        return item == null ? null : new DeliveryMenu(service, viewer, order, item, back);
    }

    long orderId() {
        return this.orderId;
    }

    /** The price each the player saw when opening the menu. */
    long price() {
        return this.price;
    }

    OrderItem item() {
        return this.item;
    }

    @Override
    protected boolean isItemSlot(int slot) {
        return slot >= 0 && slot < GRID;
    }

    @Override
    protected boolean acceptsItems() {
        return true;
    }

    @Override
    protected void itemsChanged() {
        redraw();
    }

    /** An item is about to leave (or be swapped out of) a grid slot: the copy drops it right away. */
    @Override
    protected void itemSlotClicked(int slot) {
        backup(slot);
    }

    /** Copies the grid into the viewer's player data, while this menu is the one they have open. Viewer's thread. */
    void backup() {
        backup(-1);
    }

    private void backup(int skip) {
        if (this.viewer.getOpenInventory().getTopInventory().getHolder(false) == this) {
            this.service.gridBackup().save(this.viewer, getInventory(), 0, GRID, skip);
        }
    }

    @Override
    protected void closed() {
        returnAll();
    }

    /**
     * Takes everything out of the grid for the death drops of a viewer who dies without keeping their inventory. The
     * menu is only closed after the inventory's drops were collected, so giving the grid back then would put it into an
     * inventory that is about to be emptied; as death drops it lies where the player died, like the rest of their
     * inventory (and the menu can't keep items safe from a death).
     */
    List<ItemStack> drainForDeath() {
        Inventory inventory = getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.isEmpty()) {
                stacks.add(stack.clone());
                inventory.setItem(slot, null);
            }
        }
        this.service.gridBackup().clear(this.viewer);
        return stacks;
    }

    @Override
    protected void draw() {
        Lang lang = this.ctx.lang();
        Order order = this.service.book().get(this.orderId);
        set(SLOT_BACK, Items.icon(Material.OAK_DOOR, lang.get(CoreMessages.UI_BACK), List.of()), click -> {
            click(Feedback.CLICK);
            this.back.run();
        });
        Arg itemArg = Arg.component("item", this.item.name());
        List<Component> orderLore = new ArrayList<>();
        if (order != null) {
            long now = this.service.engine().now();
            orderLore.addAll(lang.lines(OrdersMessages.ENTRY_PRICE, Arg.money("price", order.priceEach())));
            orderLore.addAll(lang.lines(OrdersMessages.ENTRY_DELIVERED, Arg.number("filled", order.filled()),
                Arg.number("quantity", order.quantity())));
            orderLore.addAll(lang.lines(OrdersMessages.ENTRY_OWNER, Arg.text("owner", this.service.name(order.owner()))));
            orderLore.addAll(lang.lines(OrdersMessages.ENTRY_ENDS, Arg.time("time", Duration.ofMillis(order.millisLeft(now)))));
            orderLore.add(Component.empty());
        }
        orderLore.addAll(lang.lines(this.item.variant() instanceof Variant.Enchant ? OrdersMessages.DELIVER_RULE_BOOK
            : OrdersMessages.DELIVER_RULE, itemArg));
        set(SLOT_ORDER, Items.display(this.item.prototype(), orderLore), null);

        ItemTaker.Count count = ItemTaker.count(getInventory(), 0, GRID, this.item);
        int remaining = order == null ? 0 : order.remaining();
        int units = Math.min(count.total(), remaining);
        List<Component> lore = new ArrayList<>();
        if (units > 0) {
            long paid = OrderMath.total(units, this.price);
            long tax = OrderMath.tax(paid, this.service.settings().taxBasisPoints());
            int inner = Math.max(0, Math.min(count.inner(), units - Math.min(count.outer(), units)));
            // The button delivers at once: the amounts agreed to, with every digit.
            lore.addAll(tax > 0
                ? lang.lines(OrdersMessages.DELIVER_BUTTON_LORE_TAXED, Arg.number("amount", units), Arg.number("remaining", remaining),
                    Arg.number("inner", inner), Arg.exact("payout", paid - tax), Arg.exact("tax", tax))
                : lang.lines(OrdersMessages.DELIVER_BUTTON_LORE, Arg.number("amount", units), Arg.number("remaining", remaining),
                    Arg.number("inner", inner), Arg.exact("payout", paid)));
        } else {
            lore.addAll(lang.lines(OrdersMessages.DELIVER_BUTTON_EMPTY, itemArg, Arg.number("remaining", remaining)));
        }
        int notAccepted = count.rejected() + Math.max(0, count.total() - remaining);
        if (notAccepted > 0) {
            lore.addAll(lang.lines(OrdersMessages.DELIVER_NOT_ACCEPTED, Arg.number("count", notAccepted)));
        }
        set(SLOT_DELIVER, Items.icon(Material.EMERALD, lang.get(OrdersMessages.DELIVER_BUTTON), lore), click -> deliver());
        set(SLOT_FILL, Items.icon(Material.HOPPER, lang.get(OrdersMessages.DELIVER_FILL),
            lang.lines(OrdersMessages.DELIVER_FILL_LORE, itemArg)), click -> fill());
        backup();
    }

    private void deliver() {
        if (!lock()) {
            return;
        }
        OrderService.Problem problem;
        try {
            problem = this.service.deliver(this.viewer, this);
        } finally {
            unlock();
        }
        if (problem != null) {
            this.service.send(this.viewer, problem);
            redraw();
            return;
        }
        click(Feedback.CLICK);
        // What was not accepted (other items, more than was wanted, the emptied boxes) goes back right away.
        returnAll();
        this.back.run();
    }

    private void fill() {
        if (!lock()) {
            return;
        }
        int moved;
        try {
            moved = this.service.fillFromInventory(this.viewer, this);
        } finally {
            unlock();
        }
        if (moved < 0) {
            this.ctx.messenger().send(this.viewer, OrdersMessages.DELIVER_FILL_NOTHING, Arg.component("item", this.item.name()));
        } else {
            click(Feedback.CLICK);
        }
        redraw();
    }

    // ------------------------------------------------------------------ the grid

    /** The first empty grid slot, or -1. */
    static int firstEmpty(Inventory grid) {
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack stack = grid.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** Puts as much of {@code stack} as fits into the grid (onto similar stacks, then empty slots); returns how much. */
    static int deposit(Inventory grid, ItemStack stack) {
        int left = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        for (int slot = 0; slot < GRID && left > 0; slot++) {
            ItemStack existing = grid.getItem(slot);
            if (existing != null && !existing.isEmpty() && existing.isSimilar(stack) && existing.getAmount() < max) {
                int moved = Math.min(left, max - existing.getAmount());
                grid.setItem(slot, existing.asQuantity(existing.getAmount() + moved));
                left -= moved;
            }
        }
        for (int slot = 0; slot < GRID && left > 0; slot++) {
            ItemStack existing = grid.getItem(slot);
            if (existing == null || existing.isEmpty()) {
                int moved = Math.min(left, max);
                grid.setItem(slot, stack.asQuantity(moved));
                left -= moved;
            }
        }
        return stack.getAmount() - left;
    }

    /**
     * Empties the grid into the player's inventory (what doesn't fit goes to their claim box). Safe to call more than
     * once; each item is given back exactly once because its slot is cleared first.
     */
    void returnAll() {
        Inventory inventory = getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.isEmpty()) {
                stacks.add(stack.clone());
                inventory.setItem(slot, null);
            }
        }
        // The copy drops the items before giving them back saves the player, so the saved file never holds them twice.
        this.service.gridBackup().clear(this.viewer);
        if (!stacks.isEmpty()) {
            this.service.give(this.viewer, stacks, Order.ref(this.orderId));
        }
    }
}
