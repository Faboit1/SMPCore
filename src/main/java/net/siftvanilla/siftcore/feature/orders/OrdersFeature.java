package net.siftvanilla.siftcore.feature.orders;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Buy orders: a player asks for a number of items at a price each and the money is held by the server right away;
 * other players deliver matching items (through the order's delivery menu, quick deliver from their inventory, or a
 * sale routed through the {@link OrderMarket}) and are paid from that money; the buyer collects the items. Orders live
 * in memory (loaded at startup) and change only inside economy transactions, so money, order state, rows and items
 * always move together.
 */
public final class OrdersFeature implements Feature, Listener {

    public static final Toggle NOTIFICATIONS = new Toggle("order-notices", true,
        OrdersMessages.SETTING_NOTIFICATIONS, OrdersMessages.SETTING_NOTIFICATIONS_DESCRIPTION, null);
    public static final Toggle ANNOUNCEMENTS = new Toggle("orders_announce", true,
        OrdersMessages.SETTING_ANNOUNCE, OrdersMessages.SETTING_ANNOUNCE_DESCRIPTION, null);

    /** How many orders the top-orders placeholders list. */
    private static final int TOP_SIZE = 10;
    /** How far back the item picker's "most ordered" sort looks. */
    private static final Duration POPULAR_WINDOW = Duration.ofDays(30);

    private final Services services;
    private final Logger logger;
    private final Setting<OrdersSettings> settings;
    private final OrderItems items;
    private final SpawnerItems spawners;
    private final OrderBook book = new OrderBook();
    private final OrderStore store;
    private final OrderEngine engine;
    private final OrderService service;
    private final OrdersMarket market;
    private final OrderMenus menus;
    private final OrderDialogs dialogs;
    private final OrdersCommands commands;
    private final AtomicReference<IdSequence> ids = new AtomicReference<>();
    private final List<Task> timers = new ArrayList<>();
    private volatile Task sweepTask = Task.NONE;
    private volatile List<Order> top = List.of();
    private volatile boolean stopping;
    private volatile long enabledAt;
    private volatile int unreadable;

    public OrdersFeature(Services services, List<ConfigProblem> problems, CombatStatus combat, WorthLookup worth,
                         DoubleSupplier highestSellMultiplier, SpawnerItems spawners, IgnoreLookup ignores, VanishStatus vanish) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        var knownItems = OrderItems.registryKeys();
        this.settings = services.configs().register("features/orders.yml",
            reader -> OrdersSettings.parse(reader, services.core().get().money(), knownItems), problems);
        services.lang().register(OrdersMessages.class);
        services.settings().register(NOTIFICATIONS);
        services.settings().register(ANNOUNCEMENTS);
        Permissions perms = services.permissions();
        perms.declare(OrderService.PERMISSION_USE, "Use buy orders with /orders (browse, deliver, your orders)", true);
        perms.declare(OrderService.PERMISSION_CREATE, "Place buy orders", true);
        perms.declare(OrderService.PERMISSION_ADMIN, "Cancel any order, staff actions and /orders admin", false);
        // Rank limits are numeric nodes (siftcore.orders.limit.<n>, highest wins) given by LuckPerms groups. The
        // unlimited node is declared so it is never granted implicitly (undeclared nodes default to op).
        perms.declare(OrderService.LIMIT_PREFIX + ".unlimited", "No limit on active buy orders", PermissionDefault.FALSE);
        this.spawners = spawners;
        this.items = OrderItems.load(services.lang(), spawners, this.settings::get);
        this.store = new OrderStore(services.database());
        OrderEngine.Rules rules = new OrderEngine.Rules() {
            @Override
            public boolean related(UUID owner, UUID seller) {
                return OrdersFeature.this.settings.get().refuseSameIp() && services.directory().sameIp(owner, seller);
            }

            @Override
            public boolean deliverable(Order order) {
                return OrdersFeature.this.items.of(order) != null;
            }
        };
        this.engine = new OrderEngine(services.ledger(), this.book, System::currentTimeMillis, () -> this.ids.get().next(), rules);
        OwnerNotices notices = new OwnerNotices(services, this.store, this.items, this.settings::get, NOTIFICATIONS, ANNOUNCEMENTS,
            vanish, ignores);
        this.service = new OrderService(services, this.settings, this.engine, this.store, this.items, combat, worth,
            highestSellMultiplier, notices);
        this.menus = new OrderMenus(this.service);
        this.dialogs = new OrderDialogs(services, this.service, this.menus);
        this.menus.dialogs(this.dialogs);
        this.market = new OrdersMarket(this.service, this.dialogs);
        this.commands = new OrdersCommands(services, this.service, this.menus);
    }

    @Override
    public String id() {
        return "orders";
    }

    /** Buy orders as a market sales can be routed into (for the sell feature). */
    public OrderMarket market() {
        return this.market;
    }

    @Override
    public void enable() throws Exception {
        this.enabledAt = System.currentTimeMillis();
        this.ids.set(IdSequence.forTable(this.services.database(), "orders"));
        OrderStore.Loaded loaded = this.store.loadOpen().get();
        this.unreadable = loaded.unreadable().size();
        for (String problem : loaded.unreadable()) {
            this.logger.severe("An order could not be loaded and was skipped (it stays in storage): " + problem);
        }
        this.book.load(loaded.orders());
        String escrow = this.engine.verifyEscrow();
        if (escrow != null) {
            this.logger.severe("Buy orders are not consistent at startup: " + escrow + ". Run /orders admin check.");
        }
        for (Order order : loaded.orders()) {
            if (this.items.of(order) == null) {
                this.logger.warning("Order " + order.id() + " wants " + order.key() + ", which can't be built now; it takes no deliveries "
                    + "until that item exists again (it can still be cancelled)");
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        startSweep(this.settings.get());
        this.settings.onReload(this::startSweep);
        this.timers.add(this.services.scheduler().asyncTimer(this::refreshPopularity, Duration.ofSeconds(5), Duration.ofHours(1)));
        this.timers.add(this.services.scheduler().asyncTimer(this::rebuildTop, Duration.ofSeconds(2), Duration.ofMinutes(1)));
        this.timers.add(this.services.scheduler().asyncTimer(this::purge, Duration.ofMinutes(10), Duration.ofDays(1)));
        this.services.hub().register(new HubEntry("orders", 35, OrdersMessages.HUB_LABEL, OrdersMessages.HUB_DESCRIPTION,
            OrderService.PERMISSION_USE, player -> this.menus.browser(player, null)));
        registerPlaceholders();
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.services.scheduler().entity(online, () -> this.service.limit(online), null);
        }
    }

    private synchronized void startSweep(OrdersSettings settings) {
        this.sweepTask.cancel();
        if (this.stopping) {
            return;
        }
        this.sweepTask = this.services.scheduler().asyncTimer(() -> {
            if (!this.stopping) {
                this.service.sweep();
                this.dialogs.drafts().sweep();
            }
        }, Duration.ofSeconds(5), settings.expiryCheck());
    }

    private void refreshPopularity() {
        if (this.stopping) {
            return;
        }
        this.store.popularity(System.currentTimeMillis() - POPULAR_WINDOW.toMillis()).whenComplete((counts, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not count recent orders for the item picker", error);
                return;
            }
            this.service.popularity(counts);
        });
    }

    /** The orders holding the most money, for the top-orders placeholders (rebuilt every minute). */
    private void rebuildTop() {
        long now = this.engine.now();
        List<Order> active = new ArrayList<>();
        for (Order order : this.book.active()) {
            if (!order.expiredAt(now)) {
                active.add(order);
            }
        }
        active.sort(Comparator.comparingLong(Order::escrow).reversed().thenComparingLong(Order::created).thenComparingLong(Order::id));
        this.top = List.copyOf(active.subList(0, Math.min(TOP_SIZE, active.size())));
    }

    /** Deletes closed orders older than history.keep (daily; off by default). */
    private void purge() {
        Duration keep = this.settings.get().historyKeep();
        if (this.stopping || keep.isZero()) {
            return;
        }
        this.store.purge(System.currentTimeMillis() - keep.toMillis()).whenComplete((count, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Purging old orders failed", error);
            } else if (count > 0) {
                this.logger.info("Purged " + count + " closed buy orders older than " + Durations.format(keep));
            }
        });
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("orders_active", "Your active buy orders",
            player -> Integer.toString(this.book.activeCount(player.getUniqueId())));
        placeholders.register("orders_limit", "How many buy orders you may have at once (a number or unlimited)", player -> {
            Integer limit = this.service.cachedLimit(player.getUniqueId());
            int value = limit == null ? this.settings.get().defaultLimit() : limit;
            return value == Limits.UNLIMITED ? "unlimited" : Integer.toString(value);
        });
        placeholders.register("orders_waiting", "Delivered items waiting for you in your orders",
            player -> Long.toString(this.book.waiting(player.getUniqueId())));
        placeholders.register("orders_held", "Money your active orders hold",
            player -> this.services.lang().money(this.book.held(player.getUniqueId())));
        placeholders.register("orders_open", "Active buy orders on the server", player -> Integer.toString(this.book.activeTotal()));
        placeholders.registerPrefix("orders_best_", "orders_best_<item>", "The best price each of open orders for an item, like "
            + "orders_best_diamond (empty when none)", (player, item) -> {
                Order best = bestBid(item);
                return best == null ? "" : this.services.lang().money(best.priceEach());
            });
        placeholders.registerPrefix("orders_wanted_", "orders_wanted_<item>", "Items still wanted by open orders for an item, like "
            + "orders_wanted_diamond", (player, item) -> {
                long wanted = 0;
                long now = this.engine.now();
                for (Order order : this.book.bids(itemKey(item))) {
                    if (!order.expiredAt(now)) {
                        wanted += order.remaining();
                    }
                }
                return Long.toString(wanted);
            });
        placeholders.registerPrefix("orders_top_item_", "orders_top_item_<n>", "The item of the n-th biggest open order (1-10)",
            (player, rank) -> top(rank, order -> this.items.plainName(order.key())));
        placeholders.registerPrefix("orders_top_price_", "orders_top_price_<n>", "The price each of the n-th biggest open order",
            (player, rank) -> top(rank, order -> this.services.lang().money(order.priceEach())));
        placeholders.registerPrefix("orders_top_left_", "orders_top_left_<n>", "Items the n-th biggest open order still wants",
            (player, rank) -> top(rank, order -> Integer.toString(order.remaining())));
        placeholders.registerPrefix("orders_top_owner_", "orders_top_owner_<n>", "Who placed the n-th biggest open order",
            (player, rank) -> top(rank, order -> this.service.name(order.owner())));
    }

    /** The order key of a placeholder item argument ({@code diamond}, {@code minecraft:diamond}). */
    private static String itemKey(String item) {
        String text = item.strip().toLowerCase(java.util.Locale.ROOT);
        return text.contains(":") ? text : "minecraft:" + text;
    }

    private Order bestBid(String item) {
        long now = this.engine.now();
        for (Order order : this.book.bids(itemKey(item))) {
            if (!order.expiredAt(now)) {
                return order;
            }
        }
        return null;
    }

    private String top(String rank, java.util.function.Function<Order, String> value) {
        int index;
        try {
            index = Integer.parseInt(rank.strip()) - 1;
        } catch (NumberFormatException e) {
            return "";
        }
        List<Order> list = this.top;
        return index < 0 || index >= list.size() ? "" : value.apply(list.get(index));
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.service.restoreGrid(player);
        this.service.limit(player);
        this.service.notices().joined(player, this.book);
    }

    /** A delivery grid open at death becomes part of the death drops (see {@link DeliveryMenu#drainForDeath()}). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!event.getKeepInventory()
            && event.getEntity().getOpenInventory().getTopInventory().getHolder(false) instanceof DeliveryMenu menu) {
            event.getDrops().addAll(menu.drainForDeath());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        this.dialogs.drafts().forget(player);
        this.service.forget(player);
    }

    @Override
    public void disable() {
        this.stopping = true;
        this.sweepTask.cancel();
        for (Task timer : this.timers) {
            timer.cancel();
        }
        try {
            this.service.shutdown();
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Settling pending order items on shutdown failed", e);
        }
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "orders escrow equals open orders", this.engine::verifyEscrow);
        test.check(id(), "bid index matches book", this.engine::verifyIndex);
        test.check(id(), "no variant order points at an unknown enchantment", () -> {
            for (Order order : this.book.all()) {
                if (order.variant() != null && this.items.of(order) == null) {
                    return "order " + order.id() + " wants " + order.key() + ", which does not exist";
                }
            }
            return null;
        });
        test.check(id(), "every open order loaded", () -> this.unreadable == 0 ? null
            : this.unreadable + " open order(s) could not be read from storage");
        test.checkAsync(id(), "memory matches storage", () -> memoryMatchesStorage(2));
        test.check(id(), "tax math", () -> {
            if (OrderMath.tax(1_000, 200) != 20 || OrderMath.tax(49, 200) != 0 || OrderMath.tax(Long.MAX_VALUE, 10_000) != Long.MAX_VALUE) {
                return "tax rounding is wrong";
            }
            return OrderMath.suggestedPrice(100, 1_000, 200) == 113 ? null : "the suggested price of $100 at 10% over 2% tax is not $113";
        });
        test.check(id(), "expiry timer runs", () -> {
            long now = System.currentTimeMillis();
            long limit = this.settings.get().expiryCheck().toMillis() * 2 + 10_000;
            long last = Math.max(this.service.lastSweep(), this.enabledAt);
            return now - last <= limit ? null : "no expiry sweep in the last " + limit / 1000 + "s";
        });
        test.check(id(), "only exact items match", this::checkPlainRule);
        test.check(id(), "book orders match exactly", this::checkBookRule);
        test.check(id(), "spawner orders match only SiftCore spawners", this::checkSpawnerRule);
        test.check(id(), "shulker rebuild keeps other contents", OrdersFeature::checkShulkerRebuild);
    }

    /** Compares counts and money in memory and storage; retried a few times since live trades may be in flight. */
    private CompletableFuture<String> memoryMatchesStorage(int attempts) {
        long[] memory = this.services.ledger().locked(() -> new long[] {this.book.activeTotal(), this.book.escrowTotal()});
        return this.store.totalsInOrder().thenCompose(stored -> {
            if (stored[0] == memory[0] && stored[1] == memory[1]) {
                return CompletableFuture.completedFuture(null);
            }
            if (attempts > 1) {
                return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> memoryMatchesStorage(attempts - 1));
            }
            return CompletableFuture.completedFuture("memory has " + memory[0] + " active orders holding " + memory[1]
                + ", storage " + stored[0] + " holding " + stored[1]);
        });
    }

    /** A plain stack matches its type's order; renamed, enchanted, damaged and custom-data copies don't. */
    private String checkPlainRule() {
        OrderItem diamond = this.items.resolve("minecraft:diamond");
        OrderItem sword = this.items.resolve("minecraft:diamond_sword");
        if (diamond == null || sword == null) {
            return "plain items can't be built";
        }
        ItemStack plain = ItemStack.of(Material.DIAMOND, 5);
        if (!diamond.matches(plain) || !"minecraft:diamond".equals(this.items.key(plain))) {
            return "a plain diamond does not match";
        }
        ItemStack renamed = plain.clone();
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Renamed"));
        if (diamond.matches(renamed) || this.items.key(renamed) != null) {
            return "a renamed diamond matches";
        }
        ItemStack damaged = ItemStack.of(Material.DIAMOND_SWORD);
        damaged.setData(DataComponentTypes.DAMAGE, 3);
        if (sword.matches(damaged)) {
            return "a damaged sword matches";
        }
        ItemStack enchanted = ItemStack.of(Material.DIAMOND_SWORD);
        enchanted.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
        if (sword.matches(enchanted)) {
            return "an enchanted sword matches";
        }
        ItemStack tagged = plain.clone();
        tagged.editPersistentDataContainer(pdc -> pdc.set(new org.bukkit.NamespacedKey(this.services.plugin(), "selftest"),
            org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        return diamond.matches(tagged) ? "a diamond with custom data matches" : null;
    }

    /**
     * The book rule as the server applies it ({@code isSimilar} against the canonical book) agrees with the documented
     * rule ({@link Variant.Enchant#accepts}): the exact book matches; extra enchantments, a repair cost or another level
     * don't.
     */
    private String checkBookRule() {
        Variant.Enchant mending = new Variant.Enchant("minecraft:mending", 1);
        OrderItem item = this.items.resolve(OrderItems.ENCHANTED_BOOK, mending.id());
        if (item == null) {
            return "a Mending book order can't be built";
        }
        Map<String, ItemStack> cases = new HashMap<>();
        cases.put("exact", book(Map.of(Enchantment.MENDING, 1), 0));
        cases.put("extra enchantment", book(Map.of(Enchantment.MENDING, 1, Enchantment.UNBREAKING, 3), 0));
        cases.put("repair cost", book(Map.of(Enchantment.MENDING, 1), 1));
        Variant.Enchant sharpness = new Variant.Enchant("minecraft:sharpness", 4);
        OrderItem sharp = this.items.resolve(OrderItems.ENCHANTED_BOOK, sharpness.id());
        if (sharp == null) {
            return "a Sharpness IV book order can't be built";
        }
        for (Map.Entry<String, ItemStack> entry : cases.entrySet()) {
            boolean server = item.matches(entry.getValue());
            boolean rule = mending.accepts(facts(entry.getValue()));
            if (server != rule || server != entry.getKey().equals("exact")) {
                return "the " + entry.getKey() + " book: the server says " + server + ", the rule says " + rule;
            }
        }
        if (sharp.matches(book(Map.of(Enchantment.SHARPNESS, 5), 0))) {
            return "a Sharpness V book matches a Sharpness IV order";
        }
        String key = this.items.key(book(Map.of(Enchantment.MENDING, 1), 0));
        return item.key().equals(key) || !this.settings.get().booksEnabled() ? null : "a Mending book resolves to " + key;
    }

    /**
     * A spawner order takes exactly the spawner feature's own spawner item of its mob: a vanilla spawner, a renamed one
     * and another mob's don't match, and the item resolves to the order's key. Nothing to check without spawners.
     */
    private String checkSpawnerRule() {
        List<String> mobs = this.items.spawnerMobs();
        if (mobs.isEmpty()) {
            return null;
        }
        String mob = mobs.getFirst();
        OrderItem item = this.items.resolve(OrderItems.SPAWNER, new Variant.Spawner(mob).id());
        ItemStack real = this.spawners.create(mob, 1).orElse(null);
        if (item == null || real == null) {
            return "a " + mob + " spawner order can't be built";
        }
        if (!item.matches(real)) {
            return "the spawner feature's " + mob + " spawner does not match its order";
        }
        ItemStack vanilla = ItemStack.of(Material.SPAWNER);
        ItemStack renamed = vanilla.clone();
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Items.name(real));
        if (item.matches(vanilla) || item.matches(renamed)) {
            return "a vanilla spawner matches a " + mob + " spawner order";
        }
        if (mobs.size() > 1) {
            ItemStack other = this.spawners.create(mobs.get(1), 1).orElse(null);
            if (other != null && item.matches(other)) {
                return "a " + mobs.get(1) + " spawner matches a " + mob + " spawner order";
            }
        }
        String key = this.items.key(real);
        return !this.settings.get().spawnersEnabled() || item.key().equals(key) ? null : "a " + mob + " spawner resolves to " + key;
    }

    private static ItemStack book(Map<Enchantment, Integer> enchantments, int repairCost) {
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.setData(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantments.itemEnchantments(enchantments));
        if (repairCost > 0) {
            book.setData(DataComponentTypes.REPAIR_COST, repairCost);
        }
        return book;
    }

    private static Variant.BookFacts facts(ItemStack book) {
        Map<String, Integer> stored = new HashMap<>();
        ItemEnchantments enchantments = book.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        if (enchantments != null) {
            enchantments.enchantments().forEach((enchantment, level) -> stored.put(enchantment.getKey().asString(), level));
        }
        Integer repair = book.getData(DataComponentTypes.REPAIR_COST);
        ItemStack bare = book.clone();
        bare.resetData(DataComponentTypes.STORED_ENCHANTMENTS);
        bare.resetData(DataComponentTypes.REPAIR_COST);
        return new Variant.BookFacts(stored, repair == null ? 0 : repair, !bare.isSimilar(ItemStack.of(Material.ENCHANTED_BOOK)));
    }

    /** Taking diamonds out of a shulker box leaves its other contents in their slots; an emptied box is plain again. */
    private static String checkShulkerRebuild() {
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        List<ItemStack> contents = new ArrayList<>();
        contents.add(ItemStack.of(Material.DIAMOND, 10));
        contents.add(ItemStack.empty());
        contents.add(ItemStack.of(Material.DIRT, 5));
        contents.add(ItemStack.of(Material.DIAMOND, 7));
        box.setData(DataComponentTypes.CONTAINER, io.papermc.paper.datacomponent.item.ItemContainerContents.containerContents(contents));
        ContainerItems.Extraction<ItemStack> extraction = ContainerItems.extract(ContainerItems.contents(box),
            stack -> stack.getType() == Material.DIAMOND, 12);
        if (extraction.units() != 12) {
            return "took " + extraction.units() + " instead of 12";
        }
        ItemStack rebuilt = ContainerItems.rebuild(box, extraction.remaining());
        List<ItemStack> after = ContainerItems.contents(rebuilt);
        if (after.size() < 4 || !after.get(0).isEmpty() || after.get(2).getType() != Material.DIRT || after.get(2).getAmount() != 5
            || after.get(3).getType() != Material.DIAMOND || after.get(3).getAmount() != 5) {
            return "the rebuilt box holds " + after;
        }
        ContainerItems.Extraction<ItemStack> rest = ContainerItems.extract(after, stack -> true, Long.MAX_VALUE);
        ItemStack emptied = ContainerItems.rebuild(rebuilt, rest.remaining());
        return emptied.isSimilar(ItemStack.of(Material.SHULKER_BOX)) ? null : "an emptied box is not a plain box";
    }
}
