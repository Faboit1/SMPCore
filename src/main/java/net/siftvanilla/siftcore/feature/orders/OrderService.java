package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.OrderCancelEvent;
import net.siftvanilla.siftcore.api.event.OrderCollectEvent;
import net.siftvanilla.siftcore.api.event.OrderCreateEvent;
import net.siftvanilla.siftcore.api.event.OrderEditEvent;
import net.siftvanilla.siftcore.api.event.OrderEndEvent;
import net.siftvanilla.siftcore.api.event.OrderFillEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.Handoffs;
import net.siftvanilla.siftcore.ui.gui.GridBackup;
import net.siftvanilla.siftcore.ui.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What players and staff do with orders: place, deliver (menu and quick), collect, change, extend, cancel, and the
 * expiry and warnings of orders that run out of time. Player actions run on the player's thread; each one
 * re-validates everything at execution time, fires the public event, runs one {@link OrderEngine} transaction, and
 * moves items only in the safe order: items leave a player before the delivery is stored, and collected items are
 * handed over only after the collect is committed.
 */
final class OrderService {

    static final String PERMISSION_USE = "siftcore.command.orders";
    static final String PERMISSION_CREATE = "siftcore.orders.create";
    static final String PERMISSION_ADMIN = "siftcore.admin.orders";
    static final String LIMIT_PREFIX = "siftcore.orders.limit";
    /** The claim box source of order items that could not go anywhere else. */
    static final String CLAIM_SOURCE = "orders";
    /** Slots of a player inventory that deliveries take from: the hotbar and the main inventory. */
    static final int STORAGE_SLOTS = 36;

    /** A problem to tell the player: the message and its arguments. */
    record Problem(MessageKey key, Arg... args) implements Prepared, QuickOutcome {
    }

    /** A checked order the player is about to confirm. */
    record Draft(String key, int quantity, long priceEach, long total) implements Prepared {
    }

    /** What checking an order form gives: a draft to confirm or a problem. */
    sealed interface Prepared permits Draft, Problem {
    }

    /**
     * What quick deliver would do right now, as shown to the seller.
     *
     * @param carried    matching items the seller carries (outer stacks and shulker box contents)
     * @param inner      of those, in shulker boxes
     * @param units      what would be delivered: the carried items, at most what is still wanted
     * @param serverEach what the server pays the seller per item with their multiplier (0: nothing)
     */
    record QuickView(long orderId, long priceEach, int remaining, int carried, int inner, int units, long paid, long tax,
                     long serverEach, boolean serverPaysMore) {
        long payout() {
            return this.paid - this.tax;
        }

        boolean sameAs(QuickView other) {
            return other != null && this.orderId == other.orderId && this.priceEach == other.priceEach && this.remaining == other.remaining
                && this.carried == other.carried && this.inner == other.inner && this.units == other.units;
        }
    }

    /** How a quick delivery ended when it did not go ahead. */
    sealed interface QuickOutcome permits Problem, QuickChanged {
    }

    /** The order or the inventory changed since the dialog was shown; here is the fresh view. */
    record QuickChanged(QuickView fresh) implements QuickOutcome {
    }

    /** How much of the waiting items to collect. */
    enum CollectMode {
        /** Everything that fits into the inventory. */
        FITS,
        /** One stack at most. */
        ONE_STACK,
        /** Everything: what fits into the inventory, the rest into the claim box. */
        CLAIM_REST
    }

    private final Services services;
    private final Setting<OrdersSettings> settings;
    private final OrderEngine engine;
    private final OrderStore store;
    private final OrderItems items;
    private final CombatStatus combat;
    private final WorthLookup worth;
    private final DoubleSupplier highestMultiplier;
    private final OwnerNotices notices;
    private final Handovers handovers = new Handovers();
    private final GridBackup gridBackup;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Map<UUID, Integer> limits = new ConcurrentHashMap<>();
    private final Logger logger;
    private volatile long lastSweep;
    private volatile Map<String, Integer> popularity = Map.of();

    OrderService(Services services, Setting<OrdersSettings> settings, OrderEngine engine, OrderStore store, OrderItems items,
                 CombatStatus combat, WorthLookup worth, DoubleSupplier highestMultiplier, OwnerNotices notices) {
        this.services = services;
        this.settings = settings;
        this.engine = engine;
        this.store = store;
        this.items = items;
        this.combat = combat;
        this.worth = worth;
        this.highestMultiplier = highestMultiplier;
        this.notices = notices;
        this.logger = services.plugin().getLogger();
        this.gridBackup = new GridBackup(new NamespacedKey(services.plugin(), "delivery_grid"), this.logger);
    }

    Services services() {
        return this.services;
    }

    /** The copy of an open delivery grid kept in the player's data (see {@link GridBackup}). */
    GridBackup gridBackup() {
        return this.gridBackup;
    }

    /**
     * Gives back what a delivery grid held when the server stopped hard (its copy in the player's data): into the
     * inventory, the rest into the claim box. Call on the player's thread when they join.
     */
    void restoreGrid(Player player) {
        List<ItemStack> items = this.gridBackup.take(player);
        if (items.isEmpty()) {
            return;
        }
        this.services.messenger().send(player, OrdersMessages.DELIVER_RESTORED);
        give(player, items, "delivery-grid");
    }

    OrderEngine engine() {
        return this.engine;
    }

    OrderBook book() {
        return this.engine.book();
    }

    OrderStore store() {
        return this.store;
    }

    OrderItems items() {
        return this.items;
    }

    OrdersSettings settings() {
        return this.settings.get();
    }

    Handovers handovers() {
        return this.handovers;
    }

    OwnerNotices notices() {
        return this.notices;
    }

    WorthLookup worth() {
        return this.worth;
    }

    long lastSweep() {
        return this.lastSweep;
    }

    /** How many orders were placed per order key in the last 30 days (refreshed hourly). */
    Map<String, Integer> popularity() {
        return this.popularity;
    }

    void popularity(Map<String, Integer> counts) {
        this.popularity = Map.copyOf(counts);
    }

    void send(CommandSender sender, Problem problem) {
        this.services.messenger().send(sender, problem.key(), problem.args());
    }

    String name(UUID player) {
        return this.services.directory().name(player);
    }

    /** Money with every digit, in the money colour (for confirmations, where the exact amount matters). */
    Arg exact(String name, long amount) {
        return Arg.component(name, Component.text(this.services.money().get().formatExact(amount),
            this.services.lang().style().palette().money()));
    }

    /** The name of an order key for messages and lore: shown by the client in its own language where it can. */
    Arg item(String name, String key) {
        return Arg.component(name, this.items.name(key));
    }

    MessageKey stateLabel(OrderState state) {
        return switch (state) {
            case ACTIVE -> OrdersMessages.STATE_ACTIVE;
            case FILLED -> OrdersMessages.STATE_FILLED;
            case CANCELLED -> OrdersMessages.STATE_CANCELLED;
            case EXPIRED -> OrdersMessages.STATE_EXPIRED;
        };
    }

    /** The player's limit of active orders ({@link Limits#UNLIMITED} for no limit). Call on the player's thread. */
    int limit(Player player) {
        int limit = Limits.highest(player, LIMIT_PREFIX, this.settings.get().defaultLimit());
        this.limits.put(player.getUniqueId(), limit);
        return limit;
    }

    /** The limit last read on the player's thread (for placeholders), or null when never read. */
    Integer cachedLimit(UUID player) {
        return this.limits.get(player);
    }

    void forget(UUID player) {
        this.limits.remove(player);
    }

    /** Null when the player may use orders right now, otherwise why not. */
    Problem blocked(Player player) {
        if (this.settings.get().blockInCombat() && this.combat.tagged(player.getUniqueId())) {
            return new Problem(OrdersMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(player.getUniqueId())));
        }
        return null;
    }

    /** Tells the player and returns false when they may not use orders right now. */
    boolean usable(Player player) {
        Problem problem = blocked(player);
        if (problem != null) {
            send(player, problem);
            return false;
        }
        return true;
    }

    /** The server's base price for one item of the key (no multiplier), 0 when it can't be sold. */
    long worthEach(OrderItem item) {
        return Math.max(0, this.worth.unitPrice(item.prototype()));
    }

    double highestMultiplier() {
        double value = this.highestMultiplier.getAsDouble();
        return Double.isFinite(value) && value >= 1.0 ? value : 1.0;
    }

    // ------------------------------------------------------------------ menus

    /** Redraws whatever orders menu the player is looking at (on their thread). */
    void refresh(Player player) {
        this.services.scheduler().entity(player, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof OrdersView view && view instanceof Menu menu) {
                menu.redraw();
            }
        }, null);
    }

    /**
     * Redraws every open orders menu soon (orders changed: prices, counts, things that ended). Coalesced, so a burst of
     * changes redraws once.
     */
    void refreshAll() {
        if (!this.refreshQueued.compareAndSet(false, true)) {
            return;
        }
        this.services.scheduler().asyncLater(() -> {
            this.refreshQueued.set(false);
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player);
            }
        }, Duration.ofMillis(500));
    }

    // ------------------------------------------------------------------ placing orders

    /** The order key of the held item when it can be ordered (to fill in the form), otherwise null. */
    String heldKey(Player player) {
        return this.items.key(player.getInventory().getItemInMainHand());
    }

    /** The suggested price each for a key: a little above what the server pays after tax (0 when no worth). */
    long suggestedPrice(String key) {
        OrderItem item = this.items.resolve(key);
        if (item == null) {
            return 0;
        }
        OrdersSettings s = this.settings.get();
        return OrderMath.suggestedPrice(worthEach(item), s.suggestMargin(), s.taxBasisPoints());
    }

    /**
     * Checks an order form. {@code chosenKey} is the item picked in a menu or list when the item field still shows
     * it, otherwise null and the field is read. Call on the player's thread.
     */
    Prepared prepare(Player player, String itemText, String chosenKey, String quantityText, String priceText) {
        Problem blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!player.hasPermission(PERMISSION_CREATE)) {
            return new Problem(CoreMessages.NO_PERMISSION);
        }
        OrdersSettings s = this.settings.get();
        String key = chosenKey;
        if (key == null) {
            if (itemText == null || itemText.isBlank()) {
                return new Problem(OrdersMessages.CREATE_NO_ITEM);
            }
            var found = this.items.find(itemText);
            if (found.isEmpty()) {
                return new Problem(OrdersMessages.CREATE_UNKNOWN_ITEM, Arg.text("input", clip(itemText.strip())));
            }
            key = found.get();
        }
        if (OrderKeys.parse(key).variant() == null && OrderItems.variantOnly(key)) {
            return this.items.familyEnabled(key) ? new Problem(OrdersMessages.CREATE_CHOOSE_VARIANT, item("item", key))
                : new Problem(OrdersMessages.CREATE_BLOCKED, item("item", key));
        }
        OrderItem item = this.items.resolve(key);
        if (item == null) {
            return new Problem(OrdersMessages.CREATE_UNKNOWN_ITEM, Arg.text("input", clip(itemText == null ? key : itemText.strip())));
        }
        OrderInput.Quantity quantity = OrderInput.quantity(quantityText, s.maxQuantity(), item.maxStack());
        if (!quantity.ok()) {
            return new Problem(OrdersMessages.CREATE_QUANTITY_RANGE, Arg.number("max", s.maxQuantity()));
        }
        String price = priceText == null ? "" : priceText.strip();
        if (!OrderInput.priceTextAllowed(price)) {
            return new Problem(CoreMessages.INVALID_AMOUNT, Arg.text("input", clip(price)));
        }
        MoneyFormat money = this.services.money().get();
        MoneyFormat.ParseResult parsed = money.parse(price);
        if (!parsed.ok()) {
            return switch (parsed.error()) {
                case NOT_WHOLE -> new Problem(CoreMessages.AMOUNT_NOT_WHOLE, Arg.text("input", price));
                case NOT_POSITIVE -> new Problem(CoreMessages.AMOUNT_NOT_POSITIVE);
                case TOO_LARGE -> new Problem(OrdersMessages.CREATE_TOTAL_HIGH, Arg.money("max", s.maxTotal()));
                default -> new Problem(CoreMessages.INVALID_AMOUNT, Arg.text("input", price));
            };
        }
        return check(player, key, quantity.value(), parsed.amount());
    }

    private static String clip(String text) {
        return text.length() > 32 ? text.substring(0, 32) : text;
    }

    /** The rules every order must follow, checked when the form is sent and again when the order is confirmed. */
    Prepared check(Player player, String key, int quantity, long priceEach) {
        OrdersSettings s = this.settings.get();
        if (!this.items.orderable(key)) {
            return new Problem(OrdersMessages.CREATE_BLOCKED, item("item", key));
        }
        if (quantity < 1 || quantity > s.maxQuantity()) {
            return new Problem(OrdersMessages.CREATE_QUANTITY_RANGE, Arg.number("max", s.maxQuantity()));
        }
        OrderItem item = this.items.resolve(key);
        long worthEach = item == null ? 0 : worthEach(item);
        long floor = Math.max(s.minPrice(), OrderMath.floorFromWorth(worthEach, s.minVsWorth()));
        if (priceEach < floor) {
            return new Problem(OrdersMessages.CREATE_PRICE_LOW, Arg.money("min", floor));
        }
        long ceiling = OrderMath.ceilingFromWorth(worthEach, s.maxVsWorth());
        if (ceiling > 0 && priceEach > ceiling) {
            return new Problem(OrdersMessages.CREATE_PRICE_HIGH, Arg.money("max", ceiling));
        }
        long total = OrderMath.total(quantity, priceEach);
        if (total <= 0 || total > s.maxTotal()) {
            return new Problem(OrdersMessages.CREATE_TOTAL_HIGH, Arg.money("max", s.maxTotal()));
        }
        int limit = limit(player);
        if (limit <= 0) {
            return new Problem(OrdersMessages.CREATE_NO_ORDERS);
        }
        if (this.engine.book().activeCount(player.getUniqueId()) >= limit) {
            return new Problem(OrdersMessages.CREATE_LIMIT, Arg.number("limit", limit));
        }
        if (this.services.ledger().balance(player.getUniqueId(), Currency.MONEY) < total) {
            return new Problem(CoreMessages.NOT_ENOUGH_MONEY, exact("amount", total));
        }
        return new Draft(key, quantity, priceEach, total);
    }

    /**
     * Places a confirmed order: checks every rule again, fires {@link OrderCreateEvent} and holds the money. Returns
     * the problem to show, or null when the order went ahead (the player is told once it is stored). Call on the
     * player's thread.
     */
    Problem create(Player player, Draft draft) {
        if (!this.services.ledger().available()) {
            return new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
        }
        Problem blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!player.hasPermission(PERMISSION_CREATE)) {
            return new Problem(CoreMessages.NO_PERMISSION);
        }
        Prepared again = check(player, draft.key(), draft.quantity(), draft.priceEach());
        if (again instanceof Problem problem) {
            return problem;
        }
        OrderItem item = this.items.resolve(draft.key());
        if (item == null || !new OrderCreateEvent(player.getUniqueId(), item.material(), item.variantId(), draft.quantity(),
            draft.priceEach()).callEvent()) {
            return new Problem(OrdersMessages.CREATE_CANCELLED);
        }
        int limit = limit(player);
        OrderEngine.Created created;
        try {
            created = this.engine.create(new OrderEngine.Draft(player.getUniqueId(), item.itemType(), item.variantId(), draft.quantity(),
                draft.priceEach(), this.settings.get().duration().toMillis()), limit, player.getUniqueId().toString());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Placing an order for " + player.getName() + " failed", e);
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        TransactionResult result = created.result();
        if (!result.success()) {
            return switch (result.status()) {
                case INSUFFICIENT_FUNDS -> new Problem(CoreMessages.NOT_ENOUGH_MONEY, exact("amount", draft.total()));
                case CANCELLED -> new Problem(OrdersMessages.CREATE_CANCELLED);
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                default -> Refusal.from(result.reason()) == Refusal.LIMIT
                    ? new Problem(OrdersMessages.CREATE_LIMIT, Arg.number("limit", limit))
                    : new Problem(CoreMessages.ACTION_FAILED);
            };
        }
        Order order = created.order();
        String ownerName = player.getName();
        // Read on the owner's thread while they are surely loaded (the commit may land after they left).
        boolean announceMine = this.services.settings().get(player.getUniqueId(), OrdersFeature.ANNOUNCE_MINE);
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Order " + order.id() + " could not be stored; nothing was charged", error);
                this.services.messenger().send(player, OrdersMessages.CREATE_FAILED);
                return;
            }
            this.services.messenger().send(player, OrdersMessages.CREATE_DONE, Arg.number("quantity", order.quantity()),
                item("item", order.key()), exact("total", order.escrow()));
            this.notices.announce(order, ownerName, announceMine);
            refreshAll();
        });
        return null;
    }

    // ------------------------------------------------------------------ delivering

    /** Null when the order can still take deliveries from this seller at the price they saw. */
    Problem deliverable(Player seller, Order order, long seenPrice) {
        if (order == null || !order.active() || order.expiredAt(this.engine.now())) {
            return new Problem(OrdersMessages.DELIVER_GONE);
        }
        if (order.owner().equals(seller.getUniqueId())) {
            return new Problem(OrdersMessages.DELIVER_OWN);
        }
        if (order.priceEach() != seenPrice) {
            return new Problem(OrdersMessages.DELIVER_CHANGED);
        }
        if (this.items.of(order) == null) {
            return new Problem(OrdersMessages.DELIVER_UNAVAILABLE);
        }
        if (this.settings.get().refuseSameIp() && this.services.directory().sameIp(order.owner(), seller.getUniqueId())) {
            return new Problem(OrdersMessages.DELIVER_RELATED);
        }
        return null;
    }

    /** Fires the cancellable fill event for a delivery about to happen; false when a listener cancelled it. */
    boolean approved(Order order, UUID seller, OrderItem item, int units, FillSource source) {
        long paid = OrderMath.total(units, order.priceEach());
        long tax = OrderMath.tax(paid, this.settings.get().taxBasisPoints());
        return new OrderFillEvent(order.id(), order.owner(), seller, item.material(), item.variantId(), units, order.priceEach(), tax,
            source.event()).callEvent();
    }

    /**
     * Delivers what the delivery menu's grid holds for its order: plain stacks first, then the matching contents of
     * shulker boxes (each box is swapped for its rebuilt copy). Checks the order, fires {@link OrderFillEvent}, takes
     * the items and saves the player (remove before grant), then runs the fill. Everything else in the grid goes back
     * to the player. Returns the problem to show, or null when the delivery went ahead. Call on the seller's thread
     * with the menu locked.
     */
    Problem deliver(Player seller, DeliveryMenu menu) {
        Problem blocked = blocked(seller);
        if (blocked != null) {
            return blocked;
        }
        long id = menu.orderId();
        Order order = this.engine.book().get(id);
        Problem problem = deliverable(seller, order, menu.price());
        if (problem != null) {
            return problem;
        }
        OrderItem item = this.items.of(order);
        Inventory grid = menu.getInventory();
        ItemTaker.Count count = ItemTaker.count(grid, 0, DeliveryMenu.GRID, item);
        int units = Math.min(count.total(), order.remaining());
        if (units <= 0) {
            return new Problem(OrdersMessages.DELIVER_NOTHING, item("item", order.key()));
        }
        if (!approved(order, seller.getUniqueId(), item, units, FillSource.MENU)) {
            return new Problem(OrdersMessages.DELIVER_CANCELLED);
        }
        // Listeners run other plugins' code: look at the order and the grid again before taking anything.
        order = this.engine.book().get(id);
        problem = deliverable(seller, order, menu.price());
        if (problem != null) {
            return problem;
        }
        if (Math.min(ItemTaker.count(grid, 0, DeliveryMenu.GRID, item).total(), order.remaining()) < units) {
            return new Problem(OrdersMessages.DELIVER_LESS_LEFT);
        }
        return take(seller, order, item, units, grid, 0, DeliveryMenu.GRID, FillSource.MENU);
    }

    /**
     * Takes {@code units} from the slots, saves the player, runs the fill, and puts everything back exactly when the
     * fill is refused. Shared by the delivery menu and quick deliver.
     */
    private Problem take(Player seller, Order order, OrderItem item, int units, Inventory inventory, int from, int to, FillSource source) {
        // Remove before grant: the items leave the inventory, and the player file is written without them, before the
        // delivery can be stored. A crash in between can never leave the items with the seller and the money too.
        ItemTaker.Plan plan = ItemTaker.plan(inventory, from, to, item, units);
        if (plan.units() != units || !ItemTaker.apply(inventory, plan)) {
            this.logger.warning("The items of " + seller.getName() + " changed while delivering to order " + order.id() + "; nothing was taken");
            gridChanged(inventory);
            return new Problem(OrdersMessages.DELIVER_CHANGED);
        }
        // A delivery grid's copy in the player's data drops the taken items before the player is saved.
        gridChanged(inventory);
        boolean save = this.services.core().get().savePlayerAfterTrade();
        if (save) {
            seller.saveData();
        }
        int taxRate = this.settings.get().taxBasisPoints();
        TransactionResult result;
        try {
            result = this.engine.fill(seller.getUniqueId(), order.id(), units, order.priceEach(), taxRate, source,
                seller.getUniqueId().toString());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Delivering to order " + order.id() + " failed for " + seller.getName(), e);
            putBack(seller, inventory, plan, order.id(), save);
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        if (!result.success()) {
            putBack(seller, inventory, plan, order.id(), save);
            return fillProblem(seller, result, order);
        }
        filled(seller, order, item, units, plan.taken(), result, source);
        return null;
    }

    /** Undoes a taken plan: exact slots back where nothing changed, the rest handed to the seller. */
    private void putBack(Player seller, Inventory inventory, ItemTaker.Plan plan, long orderId, boolean save) {
        List<ItemStack> rest = ItemTaker.restore(inventory, plan);
        gridChanged(inventory);
        if (!rest.isEmpty()) {
            give(seller, rest, Order.ref(orderId));
        }
        if (save) {
            seller.saveData();
        }
    }

    /** After items were taken from (or put back into) a delivery grid: its copy in the player's data follows. */
    private static void gridChanged(Inventory inventory) {
        if (inventory.getHolder(false) instanceof DeliveryMenu menu) {
            menu.backup();
        }
    }

    /**
     * A delivery was applied: once it is stored the seller is told and the owner notified; if storing fails the money
     * was taken back and the taken items go back to the seller.
     */
    private void filled(Player seller, Order order, OrderItem item, int units, List<ItemStack> taken, TransactionResult result,
                        FillSource source) {
        Order after = this.engine.book().get(order.id());
        boolean complete = after != null && after.state() == OrderState.FILLED;
        long paid = OrderMath.total(units, order.priceEach());
        long tax = OrderMath.tax(paid, this.settings.get().taxBasisPoints());
        long token = this.handovers.returnIfFailed(seller.getUniqueId(), order.id(), order.key(), taken, result.committed());
        String sellerName = seller.getName();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Delivery to order " + order.id() + " could not be stored; returning the items", error);
                this.services.messenger().send(seller, OrdersMessages.DELIVER_FAILED);
                // The seller left (or the server is stopping) before that can run: the items go to their claim box.
                Handoffs.onEntity(this.services.scheduler(), seller, () -> giveBack(seller, token), () -> claimBack(token));
                return;
            }
            this.handovers.take(token);
            this.services.messenger().send(seller, OrdersMessages.DELIVER_DONE, Arg.number("amount", units),
                item("item", order.key()), Arg.money("payout", paid - tax), Arg.money("tax", tax));
            arrived(new OwnerNotices.Arrival(order.owner(), sellerName, source == FillSource.SELL,
                List.of(new OwnerNotices.Part(order, units, paid, complete))));
            refreshAll();
        });
    }

    /**
     * What to tell a seller whose delivery transaction failed. A refusal is explained by the seller-side checks run
     * again on the order as it is now (they tell an unavailable item or a shared address apart from a gone order),
     * falling back to the refusal itself.
     */
    Problem fillProblem(Player seller, TransactionResult result, Order order) {
        return switch (result.status()) {
            case BALANCE_LIMIT -> new Problem(OrdersMessages.DELIVER_TOO_RICH);
            case CANCELLED -> new Problem(OrdersMessages.DELIVER_CANCELLED);
            case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
            case INSUFFICIENT_FUNDS -> {
                this.logger.severe("The orders escrow account could not pay for order " + order.id() + "; run /orders admin check");
                yield new Problem(CoreMessages.ACTION_FAILED);
            }
            default -> {
                Problem now = deliverable(seller, this.engine.book().get(order.id()), order.priceEach());
                if (now != null) {
                    yield now;
                }
                Refusal refusal = Refusal.from(result.reason());
                yield switch (refusal == null ? Refusal.CHANGED : refusal) {
                    case GONE, NOT_ACTIVE, EXPIRED -> new Problem(OrdersMessages.DELIVER_GONE);
                    case OWN_ORDER -> new Problem(OrdersMessages.DELIVER_OWN);
                    case NOT_ENOUGH_LEFT -> new Problem(OrdersMessages.DELIVER_LESS_LEFT);
                    default -> new Problem(OrdersMessages.DELIVER_CHANGED);
                };
            }
        };
    }

    // ------------------------------------------------------------------ quick deliver

    /** What quick deliver would do now for this seller and order. Call on the seller's thread. */
    QuickView quickView(Player seller, Order order, OrderItem item) {
        ItemTaker.Count count = ItemTaker.count(seller.getInventory(), 0, STORAGE_SLOTS, item);
        int remaining = order.remaining();
        int units = Math.min(count.total(), remaining);
        long paid = units <= 0 ? 0 : OrderMath.total(units, order.priceEach());
        long tax = units <= 0 ? 0 : OrderMath.tax(paid, this.settings.get().taxBasisPoints());
        long unit = worthEach(item);
        double multiplier = this.worth.multiplier(seller);
        boolean more = OrderMath.serverPaysMore(unit, multiplier, order.priceEach(), this.settings.get().taxBasisPoints());
        long serverEach = unit <= 0 ? 0 : OrderMath.serverEach(unit, multiplier).longValue();
        return new QuickView(order.id(), order.priceEach(), remaining, count.total(), count.inner(), units, paid, tax, serverEach, more);
    }

    /**
     * Delivers straight from the seller's inventory what the dialog showed. The inventory and the order are read
     * again first: if anything the dialog showed changed, nothing happens and the fresh view comes back. Returns null
     * when the delivery went ahead. Call on the seller's thread.
     */
    QuickOutcome quickDeliver(Player seller, QuickView seen) {
        Problem blocked = blocked(seller);
        if (blocked != null) {
            return blocked;
        }
        Order order = this.engine.book().get(seen.orderId());
        Problem problem = deliverable(seller, order, seen.priceEach());
        if (problem != null) {
            return problem;
        }
        OrderItem item = this.items.of(order);
        QuickView fresh = quickView(seller, order, item);
        if (!fresh.sameAs(seen)) {
            return new QuickChanged(fresh);
        }
        if (fresh.units() <= 0) {
            return new Problem(OrdersMessages.DELIVER_NOTHING, item("item", order.key()));
        }
        if (!approved(order, seller.getUniqueId(), item, fresh.units(), FillSource.QUICK)) {
            return new Problem(OrdersMessages.DELIVER_CANCELLED);
        }
        order = this.engine.book().get(seen.orderId());
        problem = deliverable(seller, order, seen.priceEach());
        if (problem != null) {
            return problem;
        }
        QuickView after = quickView(seller, order, item);
        if (!after.sameAs(seen)) {
            return new QuickChanged(after);
        }
        PlayerInventory inventory = seller.getInventory();
        Problem taken = take(seller, order, item, after.units(), inventory, 0, STORAGE_SLOTS, FillSource.QUICK);
        return taken;
    }

    // ------------------------------------------------------------------ fill from inventory (delivery menu)

    /**
     * Moves matching stacks, and shulker boxes holding matching items (whole), from the player's hotbar and main
     * inventory into the delivery menu's empty grid slots, up to what the order still wants. The items stay the
     * player's: closing the menu gives everything back. Returns how many items now count toward the delivery, or -1
     * when nothing could move. Call on the player's thread.
     */
    int fillFromInventory(Player player, DeliveryMenu menu) {
        Order order = this.engine.book().get(menu.orderId());
        if (order == null || !order.active()) {
            return -1;
        }
        OrderItem item = this.items.of(order);
        if (item == null) {
            return -1;
        }
        Inventory grid = menu.getInventory();
        int needed = order.remaining() - ItemTaker.count(grid, 0, DeliveryMenu.GRID, item).total();
        PlayerInventory inventory = player.getInventory();
        int moved = 0;
        for (int slot = 0; slot < STORAGE_SLOTS && needed > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (item.matches(stack)) {
                int part = Math.min(stack.getAmount(), needed);
                int placed = DeliveryMenu.deposit(grid, stack.asQuantity(part));
                if (placed <= 0) {
                    break;
                }
                inventory.setItem(slot, placed == stack.getAmount() ? null : stack.asQuantity(stack.getAmount() - placed));
                needed -= placed;
                moved += placed;
            } else if (ContainerItems.isShulker(stack)) {
                long inside = ItemTaker.inside(stack, item);
                if (inside <= 0) {
                    continue;
                }
                int free = DeliveryMenu.firstEmpty(grid);
                if (free < 0) {
                    break;
                }
                grid.setItem(free, stack.clone());
                inventory.setItem(slot, null);
                needed -= (int) Math.min(inside, Integer.MAX_VALUE);
                moved += (int) Math.min(inside, Integer.MAX_VALUE);
            }
        }
        return moved == 0 ? -1 : moved;
    }

    // ------------------------------------------------------------------ claim box and giving back

    /** A delivery could not be stored: its items go back to the seller (on the seller's thread). */
    private void giveBack(Player seller, long token) {
        Handovers.Pending entry = this.handovers.take(token);
        if (entry != null) {
            give(seller, entry.items(), Order.ref(entry.orderId()));
        }
    }

    /** A delivery could not be stored and the seller left: its items go to their claim box. */
    private void claimBack(long token) {
        Handovers.Pending entry = this.handovers.take(token);
        if (entry != null) {
            toClaimBox(entry.player(), entry.items(), Order.ref(entry.orderId()));
        }
    }

    /**
     * Puts items into the player's inventory; whatever does not fit goes to their claim box, never on the ground. The
     * player is saved before anything reaches the claim box ({@link GridBackup#handBack}): items coming from a delivery
     * grid must already be out of its copy. Call on the player's thread (or during shutdown).
     */
    void give(Player player, List<ItemStack> stacks, String ref) {
        if (stacks.isEmpty()) {
            return;
        }
        GridBackup.handBack(stacks,
            all -> new ArrayList<>(player.getInventory().addItem(all.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).values()),
            () -> {
                if (this.services.core().get().savePlayerAfterTrade()) {
                    player.saveData();
                }
            },
            overflow -> {
                toClaimBox(player.getUniqueId(), overflow, ref);
                this.services.messenger().send(player, OrdersMessages.ITEMS_IN_CLAIM_BOX, Arg.number("amount", OrderItem.count(overflow)));
                return OrderItem.count(overflow);
            });
    }

    /** Stores items in the claim box; logs them precisely if even that fails, so staff can give them back. */
    void toClaimBox(UUID player, List<ItemStack> stacks, String ref) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            TransactionResult result = this.services.deliveries().give(player, CLAIM_SOURCE, ref, stack, "system");
            if (!result.success()) {
                lost(player, stack, ref, result.status().name());
                continue;
            }
            result.committed().whenComplete((ignored, error) -> {
                if (error != null) {
                    lost(player, stack, ref, "storage failed");
                }
            });
        }
    }

    private void lost(UUID player, ItemStack stack, String ref, String why) {
        this.logger.severe("Could not return " + stack.getAmount() + " " + stack.getType().getKey().asString() + " to "
            + name(player) + " (" + player + ", " + ref + "): " + why + ". Give them back by hand.");
    }

    // ------------------------------------------------------------------ collecting

    /**
     * Collects delivered items of one order: as many as fit (or one stack), optionally sending the rest to the claim
     * box in the same transaction. The count changes first; the items are handed over on the owner's thread after the
     * change is committed, and anything that no longer fits by then goes back into the order. Call on the owner's
     * thread.
     */
    Problem collect(Player owner, long id, CollectMode mode) {
        Problem blocked = blocked(owner);
        if (blocked != null) {
            return blocked;
        }
        Order order = this.engine.book().get(id);
        if (order == null || !order.owner().equals(owner.getUniqueId())) {
            return new Problem(OrdersMessages.CANCEL_GONE);
        }
        int waiting = order.waiting();
        if (waiting <= 0) {
            return new Problem(OrdersMessages.COLLECT_NOTHING);
        }
        OrderItem item = this.items.of(order);
        if (item == null) {
            return new Problem(OrdersMessages.COLLECT_UNAVAILABLE);
        }
        int fits = item.space(owner.getInventory().getStorageContents(), waiting);
        int toInventory = mode == CollectMode.ONE_STACK ? Math.min(fits, item.maxStack()) : fits;
        int toClaimBox = mode == CollectMode.CLAIM_REST ? waiting - toInventory : 0;
        if (toInventory + toClaimBox <= 0) {
            return new Problem(CoreMessages.INVENTORY_FULL);
        }
        String ref = order.ref();
        TransactionResult result;
        try {
            result = this.engine.collect(owner.getUniqueId(), id, toInventory + toClaimBox, owner.getUniqueId().toString(),
                toClaimBox <= 0 ? null : tx -> {
                    for (ItemStack stack : item.stacks(toClaimBox)) {
                        this.services.deliveries().add(tx, owner.getUniqueId(), CLAIM_SOURCE, ref, stack);
                    }
                });
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Collecting from order " + id + " failed for " + owner.getName(), e);
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        if (!result.success()) {
            return collectProblem(result);
        }
        long token = toInventory > 0 ? this.handovers.collect(owner.getUniqueId(), id, order.key(), toInventory, result.committed()) : -1;
        String key = order.key();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                if (token >= 0) {
                    this.handovers.take(token);
                }
                this.services.messenger().send(owner, OrdersMessages.COLLECT_FAILED);
                return;
            }
            new OrderCollectEvent(id, owner.getUniqueId(), item.itemType(), item.variantId(), toInventory + toClaimBox).callEvent();
            if (toClaimBox > 0) {
                this.services.messenger().send(owner, OrdersMessages.COLLECT_TO_CLAIM_BOX, Arg.number("amount", toClaimBox), item("item", key));
            }
            if (token >= 0) {
                // The owner left (or the server is stopping) before the hand-over: the items go back into the order.
                Handoffs.onEntity(this.services.scheduler(), owner, () -> handOver(owner, token, true), () -> putBack(token));
            } else {
                refresh(owner);
            }
        });
        return null;
    }

    /**
     * Collects from every order of the owner, newest first, as much of each as still fits after the ones before it, in
     * one transaction. Call on the owner's thread.
     */
    Problem collectAll(Player owner) {
        Problem blocked = blocked(owner);
        if (blocked != null) {
            return blocked;
        }
        List<Order> orders = new ArrayList<>();
        for (Order order : this.engine.book().of(owner.getUniqueId())) {
            if (order.waiting() > 0 && this.items.of(order) != null) {
                orders.add(order);
            }
        }
        if (orders.isEmpty()) {
            return new Problem(OrdersMessages.COLLECT_NOTHING);
        }
        orders.sort(Comparator.comparingLong(Order::created).reversed().thenComparingLong(Order::id));
        List<OrderEngine.Collect> collects = plan(owner, orders);
        if (collects.isEmpty()) {
            return new Problem(CoreMessages.INVENTORY_FULL);
        }
        int collected = collects.stream().mapToInt(OrderEngine.Collect::amount).sum();
        int count = collects.size();
        return collectPlanned(owner, orders, collects, given -> {
            this.services.messenger().send(owner, OrdersMessages.COLLECT_ALL_DONE, Arg.number("amount", collected),
                Arg.number("count", count));
        }, () -> this.services.messenger().send(owner, OrdersMessages.COLLECT_FAILED), () -> { });
    }

    /**
     * How much of each order to collect so that it all fits into the owner's inventory, in the orders' order: each
     * takes what still fits after the ones before it (a simulated copy of the inventory is filled as it goes). Orders
     * nothing fits from are left out. Owner's thread.
     */
    private List<OrderEngine.Collect> plan(Player owner, List<Order> orders) {
        ItemStack[] simulated = cloneAll(owner.getInventory().getStorageContents());
        List<OrderEngine.Collect> collects = new ArrayList<>();
        for (Order order : orders) {
            OrderItem item = this.items.of(order);
            if (item == null || order.waiting() <= 0) {
                continue;
            }
            int fits = item.space(simulated, order.waiting());
            if (fits <= 0) {
                continue;
            }
            addVirtual(simulated, item, fits);
            collects.add(new OrderEngine.Collect(order.id(), fits));
        }
        return collects;
    }

    /**
     * Collects planned amounts from several orders in one transaction, then hands the items over on the owner's
     * thread once it is committed (what no longer fits by then goes back into its order). Returns the problem when
     * the transaction is refused (nothing changed). After the commit exactly one callback runs: {@code handed} on the
     * owner's thread with how many items reached the inventory; {@code failed} when storing failed (the items stay in
     * the orders); or {@code left} when the owner left before the hand-over (the items went back into the orders).
     */
    private Problem collectPlanned(Player owner, List<Order> orders, List<OrderEngine.Collect> collects, IntConsumer handed,
                                   Runnable failed, Runnable left) {
        Map<Long, Order> byId = new HashMap<>();
        for (Order order : orders) {
            byId.put(order.id(), order);
        }
        TransactionResult result;
        try {
            result = this.engine.collectAll(owner.getUniqueId(), collects, owner.getUniqueId().toString());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Collecting orders failed for " + owner.getName(), e);
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        if (!result.success()) {
            return collectProblem(result);
        }
        List<Long> tokens = new ArrayList<>();
        for (OrderEngine.Collect collect : collects) {
            tokens.add(this.handovers.collect(owner.getUniqueId(), collect.orderId(), byId.get(collect.orderId()).key(), collect.amount(),
                result.committed()));
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                tokens.forEach(this.handovers::take);
                failed.run();
                return;
            }
            for (OrderEngine.Collect collect : collects) {
                Order order = byId.get(collect.orderId());
                new OrderCollectEvent(order.id(), owner.getUniqueId(), order.itemType(), order.variant(), collect.amount()).callEvent();
            }
            // The owner left (or the server is stopping) before the hand-over: the items go back into their orders.
            Handoffs.onEntity(this.services.scheduler(), owner, () -> {
                int given = 0;
                for (long token : tokens) {
                    given += handOver(owner, token, false);
                }
                handed.accept(given);
                refresh(owner);
            }, () -> {
                tokens.forEach(this::putBack);
                left.run();
            });
        });
        return null;
    }

    /**
     * Items reached an owner's orders (the delivery or sale is stored). An owner who is online with
     * {@code order-auto-collect} on gets what fits collected into their inventory on their thread, through the same
     * safe path as Collect all, and is then told once, as their {@code order-notices} setting says, that the items
     * are in their inventory (nothing for "never", only completions for "only when complete"). When nothing is
     * collected (they are in combat while orders are blocked in combat, nothing fits, the collect was refused or
     * could not be stored) they are told about the arrival as usual and the items wait in the order; an owner who is
     * away, or leaves first, gets the usual notice rows.
     */
    void arrived(OwnerNotices.Arrival arrival) {
        Player owner = Bukkit.getPlayer(arrival.owner());
        if (owner == null || !this.services.settings().get(arrival.owner(), OrdersFeature.AUTO_COLLECT)) {
            this.notices.tell(owner, arrival);
            return;
        }
        Handoffs.onEntity(this.services.scheduler(), owner, () -> autoCollect(owner, arrival), () -> this.notices.tell(null, arrival));
    }

    /** {@link #arrived} for an owner with auto-collect on, on their thread. */
    private void autoCollect(Player owner, OwnerNotices.Arrival arrival) {
        if (!owner.isOnline()) {
            this.notices.tell(null, arrival);
            return;
        }
        List<Order> orders = new ArrayList<>();
        for (long id : arrival.orderIds()) {
            Order order = this.engine.book().get(id);
            if (order != null && order.owner().equals(owner.getUniqueId())) {
                orders.add(order);
            }
        }
        List<OrderEngine.Collect> collects = blocked(owner) != null ? List.of() : plan(owner, orders);
        if (collects.isEmpty()) {
            this.notices.tell(owner, arrival);
            return;
        }
        Problem refused = collectPlanned(owner, orders, collects, given -> {
            if (given <= 0) {
                this.notices.tell(owner, arrival);
                return;
            }
            Map<Long, Integer> waiting = new HashMap<>();
            for (long id : arrival.orderIds()) {
                Order now = this.engine.book().get(id);
                waiting.put(id, now == null ? 0 : now.waiting());
            }
            this.notices.collected(owner, arrival, waiting);
        }, () -> this.notices.tell(owner, arrival), () -> this.notices.tell(null, arrival));
        if (refused != null) {
            this.notices.tell(owner, arrival);
        }
    }

    private Problem collectProblem(TransactionResult result) {
        return switch (result.status()) {
            case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
            default -> Refusal.from(result.reason()) == Refusal.NOTHING_WAITING
                ? new Problem(OrdersMessages.COLLECT_NOTHING) : new Problem(OrdersMessages.CANCEL_GONE);
        };
    }

    private static ItemStack[] cloneAll(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** Adds items to a simulated inventory the way {@code addItem} would: onto matching stacks, then empty slots. */
    static void addVirtual(ItemStack[] slots, OrderItem item, int amount) {
        int left = amount;
        int max = item.maxStack();
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (item.matches(slots[i]) && slots[i].getAmount() < max) {
                int add = Math.min(left, max - slots[i].getAmount());
                slots[i] = slots[i].asQuantity(slots[i].getAmount() + add);
                left -= add;
            }
        }
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (slots[i] == null || slots[i].isEmpty()) {
                int add = Math.min(left, max);
                slots[i] = item.prototype().asQuantity(add);
                left -= add;
            }
        }
    }

    /**
     * Hands collected items to the owner (on the owner's thread, after the commit); what doesn't fit goes back into
     * the order. With {@code tell} the owner is told what they got. Returns how many reached the inventory.
     */
    private int handOver(Player owner, long token, boolean tell) {
        Handovers.Pending entry = this.handovers.take(token);
        if (entry == null) {
            return 0;
        }
        OrderItem item = this.items.resolve(entry.key());
        if (item == null) {
            uncollect(entry.player(), entry.orderId(), entry.key(), entry.amount());
            return 0;
        }
        Map<Integer, ItemStack> left = owner.getInventory().addItem(item.stacks(entry.amount()).toArray(ItemStack[]::new));
        int notGiven = OrderItem.count(new ArrayList<>(left.values()));
        if (notGiven > 0) {
            uncollect(entry.player(), entry.orderId(), entry.key(), notGiven);
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            owner.saveData();
        }
        int given = entry.amount() - notGiven;
        if (!tell) {
            return given;
        }
        Order now = this.engine.book().get(entry.orderId());
        long more = now == null ? 0 : now.waiting();
        if (given <= 0) {
            this.services.messenger().send(owner, CoreMessages.INVENTORY_FULL);
        } else if (more > 0) {
            this.services.messenger().send(owner, OrdersMessages.COLLECT_DONE_MORE, Arg.number("amount", given),
                item("item", entry.key()), Arg.number("waiting", more));
        } else {
            this.services.messenger().send(owner, OrdersMessages.COLLECT_DONE, Arg.number("amount", given), item("item", entry.key()));
        }
        refresh(owner);
        return given;
    }

    /** The owner left before collected items reached them: the items go back into the order. */
    private void putBack(long token) {
        Handovers.Pending entry = this.handovers.take(token);
        if (entry != null) {
            uncollect(entry.player(), entry.orderId(), entry.key(), entry.amount());
        }
    }

    /** Puts collected items back into the order; if that can't be stored they go to the owner's claim box. */
    private void uncollect(UUID owner, long orderId, String key, int amount) {
        TransactionResult result;
        try {
            result = this.engine.uncollect(owner, orderId, amount, "system");
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Putting " + amount + " items back into order " + orderId + " failed", e);
            result = null;
        }
        OrderItem item = this.items.resolve(key);
        if (result == null || !result.success()) {
            if (item != null) {
                toClaimBox(owner, item.stacks(amount), Order.ref(orderId));
            } else {
                this.logger.severe("Could not put " + amount + " items back into order " + orderId + " of " + name(owner)
                    + " and its item (" + key + ") is unknown. Settle it by hand.");
            }
            return;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null && item != null) {
                toClaimBox(owner, item.stacks(amount), Order.ref(orderId));
            }
        });
    }

    // ------------------------------------------------------------------ changing an order

    /** The extra money an edit would hold now, or a problem with the new terms. */
    Prepared prepareEdit(Player owner, Order order, String priceText, String addText) {
        OrdersSettings s = this.settings.get();
        if (order == null || !order.owner().equals(owner.getUniqueId()) || !order.active()) {
            return new Problem(OrdersMessages.CANCEL_GONE);
        }
        String price = priceText == null ? "" : priceText.strip();
        long newPrice = order.priceEach();
        if (!price.isEmpty()) {
            if (!OrderInput.priceTextAllowed(price)) {
                return new Problem(CoreMessages.INVALID_AMOUNT, Arg.text("input", clip(price)));
            }
            MoneyFormat.ParseResult parsed = this.services.money().get().parse(price);
            if (!parsed.ok()) {
                return new Problem(CoreMessages.INVALID_AMOUNT, Arg.text("input", clip(price)));
            }
            newPrice = parsed.amount();
        }
        if (newPrice < order.priceEach()) {
            return new Problem(OrdersMessages.EDIT_LOWER);
        }
        OrderItem item = this.items.of(order);
        int add = 0;
        String addInput = addText == null ? "" : addText.strip();
        int roomLeft = s.maxQuantity() - order.quantity();
        if (!addInput.isEmpty() && !addInput.equals("0")) {
            if (roomLeft <= 0) {
                return new Problem(OrdersMessages.EDIT_ADD_RANGE, Arg.number("max", 0));
            }
            OrderInput.Quantity quantity = OrderInput.quantity(addInput, roomLeft, item == null ? 64 : item.maxStack());
            if (!quantity.ok()) {
                return new Problem(OrdersMessages.EDIT_ADD_RANGE, Arg.number("max", roomLeft));
            }
            add = quantity.value();
        }
        if (newPrice == order.priceEach() && add == 0) {
            return new Problem(OrdersMessages.EDIT_NOTHING);
        }
        int newQuantity = order.quantity() + add;
        long ceiling = item == null ? 0 : OrderMath.ceilingFromWorth(worthEach(item), s.maxVsWorth());
        if (ceiling > 0 && newPrice > ceiling) {
            return new Problem(OrdersMessages.CREATE_PRICE_HIGH, Arg.money("max", ceiling));
        }
        long total = OrderMath.total(newQuantity, newPrice);
        if (total <= 0 || total > s.maxTotal()) {
            return new Problem(OrdersMessages.CREATE_TOTAL_HIGH, Arg.money("max", s.maxTotal()));
        }
        long extra = OrderMath.editExtra(order.quantity(), order.filled(), order.priceEach(), newQuantity, newPrice);
        if (extra <= 0) {
            return new Problem(OrdersMessages.EDIT_NOTHING);
        }
        if (this.services.ledger().balance(owner.getUniqueId(), Currency.MONEY) < extra) {
            return new Problem(CoreMessages.NOT_ENOUGH_MONEY, exact("amount", extra));
        }
        return new Draft(order.key(), newQuantity, newPrice, extra);
    }

    /**
     * Raises the price and/or the quantity of the owner's order as confirmed: re-checks against the order as it was
     * seen, fires {@link OrderEditEvent}, holds the extra money. Returns the problem to show, or null.
     */
    Problem edit(Player owner, long id, OrderEngine.Seen seen, Draft terms) {
        Problem blocked = blocked(owner);
        if (blocked != null) {
            return blocked;
        }
        if (!this.services.ledger().available()) {
            return new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
        }
        Order order = this.engine.book().get(id);
        if (order == null || !order.owner().equals(owner.getUniqueId()) || !order.active()) {
            return new Problem(OrdersMessages.CANCEL_GONE);
        }
        if (!OrderEngine.Seen.of(order).equals(seen)) {
            return new Problem(OrdersMessages.EDIT_CHANGED);
        }
        long extra = OrderMath.editExtra(seen.quantity(), seen.filled(), seen.priceEach(), terms.quantity(), terms.priceEach());
        if (extra != terms.total() || extra <= 0) {
            return new Problem(OrdersMessages.EDIT_CHANGED);
        }
        if (!new OrderEditEvent(id, owner.getUniqueId(), seen.priceEach(), terms.priceEach(), seen.quantity(), terms.quantity(), extra)
            .callEvent()) {
            return new Problem(OrdersMessages.EDIT_CANCELLED);
        }
        OrdersSettings s = this.settings.get();
        TransactionResult result;
        try {
            result = this.engine.edit(owner.getUniqueId(), id, seen, terms.priceEach(), terms.quantity(),
                new OrderEngine.EditLimits(s.minPrice(), s.maxQuantity(), s.maxTotal()), owner.getUniqueId().toString());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Changing order " + id + " failed for " + owner.getName(), e);
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        if (!result.success()) {
            return switch (result.status()) {
                case INSUFFICIENT_FUNDS -> new Problem(CoreMessages.NOT_ENOUGH_MONEY, exact("amount", extra));
                case CANCELLED -> new Problem(OrdersMessages.EDIT_CANCELLED);
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                default -> switch (Refusal.from(result.reason()) == null ? Refusal.CHANGED : Refusal.from(result.reason())) {
                    case OUT_OF_LIMITS -> new Problem(OrdersMessages.CREATE_TOTAL_HIGH, Arg.money("max", s.maxTotal()));
                    case GONE, NOT_ACTIVE, EXPIRED, NOT_OWNER -> new Problem(OrdersMessages.CANCEL_GONE);
                    default -> new Problem(OrdersMessages.EDIT_CHANGED);
                };
            };
        }
        String key = order.key();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().send(owner, CoreMessages.ACTION_FAILED);
                return;
            }
            this.services.messenger().send(owner, OrdersMessages.EDIT_DONE, Arg.number("quantity", terms.quantity()), item("item", key),
                Arg.money("price", terms.priceEach()), exact("extra", extra));
            refreshAll();
        });
        return null;
    }

    /** The end an extension would give the order now, or 0 when it can't be extended any further. */
    long extendedEnd(Order order) {
        OrdersSettings s = this.settings.get();
        long latest = Math.addExact(order.created(), s.maxLifetime().toMillis());
        long wanted = Math.addExact(this.engine.now(), s.duration().toMillis());
        long end = Math.min(latest, wanted);
        return end > order.expires() + 60_000L ? end : 0;
    }

    /** Extends the owner's order to the full duration from now (free; capped by the longest lifetime). */
    Problem extend(Player owner, long id) {
        Order order = this.engine.book().get(id);
        if (order == null || !order.owner().equals(owner.getUniqueId()) || !order.active() || !this.settings.get().extendEnabled()) {
            return new Problem(OrdersMessages.CANCEL_GONE);
        }
        long end = extendedEnd(order);
        if (end <= 0) {
            return new Problem(OrdersMessages.EXTEND_MAX);
        }
        TransactionResult result = this.engine.extend(owner.getUniqueId(), id, order.expires(), end, owner.getUniqueId().toString());
        if (!result.success()) {
            return result.status() == TransactionStatus.UNAVAILABLE
                ? new Problem(CoreMessages.ECONOMY_UNAVAILABLE) : new Problem(OrdersMessages.EDIT_CHANGED);
        }
        this.services.messenger().send(owner, OrdersMessages.EXTEND_DONE, Arg.time("time", Duration.ofMillis(end - this.engine.now())));
        refreshAll();
        return null;
    }

    // ------------------------------------------------------------------ cancelling

    /**
     * Cancels the owner's order: fires {@link OrderCancelEvent} and refunds what it holds. Delivered items stay
     * collectable. Returns the problem to show, or null. Call on the owner's thread.
     */
    Problem cancel(Player owner, long id) {
        Order order = this.engine.book().get(id);
        if (order == null || !order.owner().equals(owner.getUniqueId()) || !order.active()) {
            return new Problem(OrdersMessages.CANCEL_GONE);
        }
        if (!new OrderCancelEvent(id, order.owner(), order.itemType(), order.escrow(), OrderCancelEvent.Cause.OWNER, null).callEvent()) {
            return new Problem(OrdersMessages.CANCEL_KEPT);
        }
        OrderEngine.Ended ended = this.engine.cancel(id, owner.getUniqueId(), owner.getUniqueId().toString());
        TransactionResult result = ended.result();
        if (!result.success()) {
            return switch (result.status()) {
                case CANCELLED -> new Problem(OrdersMessages.CANCEL_KEPT);
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                case BALANCE_LIMIT -> new Problem(CoreMessages.BALANCE_LIMIT);
                default -> new Problem(OrdersMessages.CANCEL_GONE);
            };
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().send(owner, CoreMessages.ACTION_FAILED);
                return;
            }
            this.services.messenger().send(owner, OrdersMessages.CANCEL_DONE, Arg.number("quantity", order.quantity()),
                item("item", order.key()), exact("refund", ended.refund()));
            new OrderEndEvent(id, order.owner(), order.itemType(), order.variant(), ended.refund(), OrderEndEvent.Reason.CANCELLED).callEvent();
            refreshAll();
        });
        return null;
    }

    /**
     * Staff cancel: refunds the owner, tells them (now, or when they next join) with the reason, and writes the audit
     * log. Delivered items stay collectable.
     */
    void staffCancel(CommandSender staff, long id, String reason) {
        String idText = Long.toString(id);
        String why = reason == null ? "" : reason.strip();
        if (why.length() > 64) {
            why = why.substring(0, 64);
        }
        Order order = this.engine.book().get(id);
        if (order == null || !order.active()) {
            this.services.messenger().chat(staff, OrdersMessages.ADMIN_NOT_FOUND, Arg.text("id", idText));
            return;
        }
        String actor = staff instanceof Player player ? player.getUniqueId().toString() : "console";
        if (!new OrderCancelEvent(id, order.owner(), order.itemType(), order.escrow(), OrderCancelEvent.Cause.STAFF, why).callEvent()) {
            this.services.messenger().chat(staff, OrdersMessages.ADMIN_FAILED, Arg.text("id", idText), Arg.text("reason", "cancelled by a plugin"));
            return;
        }
        OrderEngine.Ended ended = this.engine.cancel(id, null, actor);
        TransactionResult result = ended.result();
        if (!result.success()) {
            String failure = result.reason() == null ? result.status().name() : result.reason();
            this.services.messenger().chat(staff, OrdersMessages.ADMIN_FAILED, Arg.text("id", idText),
                Arg.text("reason", failure.toLowerCase(Locale.ROOT).replace('_', ' ')));
            return;
        }
        String finalReason = why;
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().chat(staff, OrdersMessages.ADMIN_FAILED, Arg.text("id", idText), Arg.text("reason", "storage"));
                return;
            }
            String ownerName = name(order.owner());
            this.services.messenger().chat(staff, OrdersMessages.ADMIN_CANCELLED, Arg.text("id", idText), Arg.text("owner", ownerName),
                exact("refund", ended.refund()));
            this.services.audit().record(actor, "orders.cancel", order.owner().toString(), "#" + id + " refund "
                + this.services.money().get().formatExact(ended.refund()) + (finalReason.isEmpty() ? "" : ": " + finalReason));
            this.notices.staffCancelled(order, ended.refund(), finalReason);
            new OrderEndEvent(id, order.owner(), order.itemType(), order.variant(), ended.refund(), OrderEndEvent.Reason.CANCELLED).callEvent();
            refreshAll();
        });
    }

    // ------------------------------------------------------------------ expiry and warnings

    /**
     * Ends and refunds every order whose time ran out, warns owners of orders that end soon, and drops closed orders
     * from memory. Runs on an async thread; an overlapping run is skipped. Returns how many orders ended.
     */
    int sweep() {
        if (!this.sweeping.compareAndSet(false, true)) {
            return 0;
        }
        int count = 0;
        try {
            this.lastSweep = System.currentTimeMillis();
            for (OrderEngine.Ended ended : this.engine.expireDue()) {
                TransactionResult result = ended.result();
                Order order = ended.before();
                if (!result.success() || order == null) {
                    Refusal refusal = Refusal.from(result.reason());
                    if (refusal != Refusal.NOT_ACTIVE && refusal != Refusal.GONE) {
                        this.logger.warning("Order " + (order == null ? "?" : order.id()) + " could not be ended: " + result.status()
                            + (result.reason() == null ? "" : " " + result.reason()));
                    }
                    continue;
                }
                count++;
                result.committed().whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.logger.log(Level.WARNING, "Ending order " + order.id() + " could not be stored; it is retried", error);
                        return;
                    }
                    this.notices.expired(order, ended.refund());
                    new OrderEndEvent(order.id(), order.owner(), order.itemType(), order.variant(), ended.refund(),
                        OrderEndEvent.Reason.EXPIRED).callEvent();
                    refreshAll();
                });
            }
            warnEnding();
            this.engine.prune(this.handovers::holdsOrder);
            this.notices.sweep();
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "The order expiry check failed", e);
        } finally {
            this.sweeping.set(false);
        }
        return count;
    }

    private void warnEnding() {
        Duration warning = this.settings.get().expiryWarning();
        if (warning.isZero()) {
            return;
        }
        long now = this.engine.now();
        for (Order order : this.engine.book().active()) {
            if (order.warned() || order.expiredAt(now) || order.millisLeft(now) > warning.toMillis()
                || now - order.created() < warning.toMillis() / 2) {
                continue;
            }
            TransactionResult result = this.engine.warn(order.id());
            if (!result.success()) {
                continue;
            }
            result.committed().whenComplete((ignored, error) -> {
                if (error == null) {
                    Order current = this.engine.book().get(order.id());
                    this.notices.ending(current == null ? order : current);
                }
            });
        }
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Settles everything that would otherwise wait on schedulers that no longer run: open delivery menus give their
     * items back, collected items that were not handed over go back into their orders, and deliveries that could not
     * be stored give their items back. Runs on the shutdown thread, which may touch every online player.
     */
    void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof DeliveryMenu menu) {
                    menu.returnAll();
                }
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Returning the delivery menu items of " + player.getName() + " failed", e);
            }
        }
        // Let every queued transaction finish so each pending handover knows how its transaction ended.
        this.services.database().flush();
        for (Handovers.Pending entry : this.handovers.takeAll()) {
            try {
                settle(entry);
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Settling " + entry + " at shutdown failed", e);
            }
        }
        this.services.database().flush();
    }

    private void settle(Handovers.Pending entry) {
        switch (entry.kind()) {
            // The items were never handed over; whether or not the collect was stored, putting them back is exact
            // (the guards of the put-back follow whatever the collect did in storage).
            case COLLECT -> {
                if (entry.committed().isDone() && entry.committed().isCompletedExceptionally()) {
                    return;
                }
                uncollect(entry.player(), entry.orderId(), entry.key(), entry.amount());
            }
            case RETURN -> {
                if (!entry.committed().isDone()) {
                    this.logger.severe("A delivery of " + entry.amount() + " " + entry.key() + " by " + name(entry.player())
                        + " to order " + entry.orderId() + " was still being stored at shutdown. If the ledger has no order_fill"
                        + " for it, give the items back by hand.");
                    return;
                }
                if (!entry.committed().isCompletedExceptionally()) {
                    return;
                }
                Player seller = Bukkit.getPlayer(entry.player());
                if (seller != null) {
                    give(seller, entry.items(), Order.ref(entry.orderId()));
                } else {
                    toClaimBox(entry.player(), entry.items(), Order.ref(entry.orderId()));
                }
            }
        }
    }
}
