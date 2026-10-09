package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * The auction house: players list items for a price, others buy them; expired and taken-down items and purchases
 * go through the claim box. Listings live in memory (loaded at startup) and change only inside economy
 * transactions, so money, listing state, rows and deliveries always move together.
 * <p>
 * Player settings (group Shop, auction &amp; orders): how sellers are told about sales and expired listings, the
 * summary on join, the low price warning when listing, and hiding one's own listings while browsing. The ones about
 * SiftCore's own auction house are offered only while it is the server's (not while AxAuctions runs).
 */
public final class AuctionFeature implements Feature, Listener {

    /** How a seller is told one of their listings sold (was a switch: on reads as chat, off as off). */
    public static final Choice<AlertStyle> SALE_ALERTS = Choices.alert("auction-sales", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .legacyValue("true", "chat").legacyValue("false", "off")
        .text(AuctionMessages.SETTING_SALES, AuctionMessages.SETTING_SALES_DESCRIPTION).build();
    /** What sold while the player was away and what waits in their claim box, a moment after they join. */
    public static final Toggle JOIN_SUMMARY = new Toggle("auction-join-summary", true,
        AuctionMessages.SETTING_JOIN_SUMMARY, AuctionMessages.SETTING_JOIN_SUMMARY_DESCRIPTION, null);
    /** A warning line in the listing confirmation when the price is far below what the items fetch elsewhere. */
    public static final Toggle PRICE_WARNING = new Toggle("auction-price-warning", true,
        AuctionMessages.SETTING_PRICE_WARNING, AuctionMessages.SETTING_PRICE_WARNING_DESCRIPTION, null);
    /** How a seller is told their listings expired and went to the claim box. */
    public static final Choice<AlertStyle> EXPIRY_ALERTS = Choices.alert("auction-expiry-alerts", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .text(AuctionMessages.SETTING_EXPIRY_ALERTS, AuctionMessages.SETTING_EXPIRY_ALERTS_DESCRIPTION).build();
    /** Leaves the viewer's own listings out of the auction house (they stay in Your listings). */
    public static final Toggle HIDE_OWN = new Toggle("auction-hide-own", false,
        AuctionMessages.SETTING_HIDE_OWN, AuctionMessages.SETTING_HIDE_OWN_DESCRIPTION, null);

    /** Sales listed by name in the join summary at most (the rest are counted). */
    static final int SUMMARY_DETAILS = 3;
    private static final long JOIN_DELAY_TICKS = 60L;

    /** The licensed auction plugin that, when it runs, is the server's auction house (docs/features/auction.md). */
    static final String AXAUCTIONS = "AxAuctions";

    private final Services services;
    private final Setting<AuctionSettings> settings;
    private final AuctionEngine<ItemStack> engine;
    private final AuctionService service;
    private final AuctionDialogs dialogs;
    private final AuctionMenus menus;
    private final AuctionCommands commands;
    private final ClaimBox claims;
    private volatile Task expiryTask = Task.NONE;
    private volatile boolean stopping;
    private volatile long enabledAt;

    /**
     * @param worth the server's sell prices (bound once selling is built), for warning sellers who list far below them
     */
    public AuctionFeature(Services services, List<ConfigProblem> problems, CombatStatus combat, Supplier<WorthLookup> worth) {
        this.services = services;
        this.settings = services.configs().register("features/auction.yml",
            reader -> AuctionSettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(AuctionMessages.class);
        registerSettings(services.settings(), this.settings::get, () -> !axAuctionsRunning());
        var perms = services.permissions();
        perms.declare(AuctionService.PERMISSION_USE, "Use the auction house with /ah", true);
        perms.declare(AuctionService.PERMISSION_SELL, "List items on the auction house", true);
        perms.declare(AuctionService.PERMISSION_ADMIN, "Remove listings and use /ah admin", false);
        perms.declare(AuctionService.PERMISSION_CLAIMS, "Open your claim box with /claims", true);
        // Rank limits are numeric nodes (siftcore.auction.listings.<n>, highest wins) given by LuckPerms groups.
        // The unlimited node is declared so it is never granted implicitly (undeclared nodes default to op).
        perms.declare(AuctionService.SLOTS_PREFIX + ".unlimited", "No limit on auction listings", PermissionDefault.FALSE);
        this.engine = new AuctionEngine<>(services.ledger(), services.database(), new AuctionEngine.Codec<>() {
            @Override
            public byte[] encode(ItemStack item) {
                return item.serializeAsBytes();
            }

            @Override
            public ItemStack decode(byte[] bytes) {
                return ItemStack.deserializeBytes(bytes);
            }
        }, (tx, owner, ref, item) -> services.deliveries().add(tx, owner, AuctionEngine.SOURCE, ref, item),
            System::currentTimeMillis, services.plugin().getLogger());
        this.claims = new ClaimBox(services);
        this.service = new AuctionService(services, this.settings, this.engine, new AuctionItems(), this.claims, combat);
        this.dialogs = new AuctionDialogs(services, this.service, worth);
        this.menus = new AuctionMenus(services, this.service, this.dialogs);
        this.commands = new AuctionCommands(services, this.service, this.menus, this.dialogs);
    }

    /**
     * Registers the auction settings in the Shop, auction &amp; orders group, in the catalog's order. The ones about
     * SiftCore's own auction house are offered while {@code ownHouse} says it is the server's; the join summary also
     * while the claim box reminder is on (the claim box holds items from every feature).
     */
    static void registerSettings(PlayerSettings prefs, Supplier<AuctionSettings> config, BooleanSupplier ownHouse) {
        prefs.register(SettingCategories.MARKET, SALE_ALERTS, SettingOptions.<AlertStyle>builder().order(1)
            .availableWhen(ownHouse).build());
        prefs.register(SettingCategories.MARKET, JOIN_SUMMARY, SettingOptions.<Boolean>builder().order(4)
            .availableWhen(() -> ownHouse.getAsBoolean() || config.get().joinReminder()).build());
        prefs.register(SettingCategories.MARKET, PRICE_WARNING, SettingOptions.<Boolean>builder().order(8)
            .availableWhen(ownHouse).build());
        prefs.register(SettingCategories.MARKET, EXPIRY_ALERTS, SettingOptions.<AlertStyle>builder().order(11)
            .availableWhen(ownHouse).build());
        prefs.register(SettingCategories.MARKET, HIDE_OWN, SettingOptions.<Boolean>builder().order(12)
            .availableWhen(ownHouse).build());
    }

    /** Whether AxAuctions runs and so is the server's auction house (SiftCore's own then yields /ah to it). */
    static boolean axAuctionsRunning() {
        Plugin axAuctions = Bukkit.getPluginManager().getPlugin(AXAUCTIONS);
        return axAuctions != null && axAuctions.isEnabled();
    }

    @Override
    public String id() {
        return "auction";
    }

    /** The auction engine (listings and their transactions). */
    public AuctionEngine<ItemStack> engine() {
        return this.engine;
    }

    @Override
    public void enable() throws Exception {
        this.enabledAt = System.currentTimeMillis();
        AuctionEngine.LoadResult loaded = this.engine.load();
        if (loaded.unreadable() > 0) {
            this.services.plugin().getLogger().severe(loaded.unreadable()
                + " auction listing(s) have unreadable items and were not loaded; they stay in storage");
        }
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        startExpiry(this.settings.get());
        this.settings.onReload(this::startExpiry);
        this.services.hub().register(new HubEntry("auction", 30, AuctionMessages.HUB_LABEL, AuctionMessages.HUB_DESCRIPTION,
            AuctionService.PERMISSION_USE, this::openFromHub));
        // Its own entry, not only a button in SiftCore's auction house: that one is out of reach while AxAuctions
        // runs, and the claim box holds items from every feature.
        this.services.hub().register(new HubEntry("claims", 32, AuctionMessages.HUB_CLAIMS_LABEL, AuctionMessages.HUB_CLAIMS_DESCRIPTION,
            AuctionService.PERMISSION_CLAIMS, this.menus::openClaimBox));
        this.services.placeholders().register("auction_listings", "Your active auction listings",
            player -> Integer.toString(this.engine.book().count(player.getUniqueId())));
        this.services.placeholders().register("auction_claims", "Items waiting in your claim box",
            player -> Integer.toString(this.claims.count(player.getUniqueId())));
    }

    /**
     * The main menu's (and pause menu's) auction house button. When AxAuctions runs, it is the server's auction house
     * (SiftCore's own /ah yields to it through {@code yield-to: AxAuctions} in commands.yml), so the button runs /ah
     * for the player; otherwise it opens this feature's menu. Checked on every click, so a failed AxAuctions start
     * falls back to SiftCore's menu. The command goes through {@link PlayerCommandPreprocessEvent} first, like a typed
     * one ({@link Player#performCommand} alone skips it), so the server's command guards (combat, freeze) apply.
     * <p>
     * AxAuctions' menu opens without the dialog router knowing, so when the command opened a container it is marked
     * as shown: the router then leaves it alone instead of closing the dialog (and with it the new menu) after its
     * grace. When nothing opened (AxAuctions or a guard refused), the router closes the dialog as usual.
     */
    private void openFromHub(Player player) {
        if (axAuctionsRunning()) {
            PlayerCommandPreprocessEvent asTyped = new PlayerCommandPreprocessEvent(player, "/ah");
            if (asTyped.callEvent()) {
                String line = asTyped.getMessage();
                Inventory before = player.getOpenInventory().getTopInventory();
                player.performCommand(line.startsWith("/") ? line.substring(1) : line);
                if (!before.equals(player.getOpenInventory().getTopInventory())) {
                    this.services.dialogs().markShown(player);
                }
            }
            return;
        }
        this.menus.openMain(player);
    }

    private synchronized void startExpiry(AuctionSettings settings) {
        this.expiryTask.cancel();
        if (this.stopping) {
            return;
        }
        this.expiryTask = this.services.scheduler().asyncTimer(() -> {
            if (!this.stopping) {
                this.service.sweep();
            }
        }, Duration.ofSeconds(5), settings.expiryCheck());
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    /**
     * A moment after joining (so the lines are not lost among the join messages), players who keep the join summary
     * on hear what sold while they were away (since they last left, up to the moment they joined: later sales were
     * told live) and what waits in their claim box (while
     * {@code join-reminder} is on).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Sales from now on are told live (the seller is online), so the summary stops at the join.
        long joined = this.engine.now();
        this.services.scheduler().entityLater(player, () -> joinSummary(player, joined), null, JOIN_DELAY_TICKS);
    }

    private void joinSummary(Player player, long joined) {
        UUID id = player.getUniqueId();
        if (!player.isOnline() || !this.services.settings().get(id, JOIN_SUMMARY)) {
            return;
        }
        long since = this.services.directory().previousSeen(id);
        CompletableFuture<AuctionEngine.SalesSince<ItemStack>> sales = since <= 0
            ? CompletableFuture.completedFuture(AuctionEngine.SalesSince.none())
            : this.engine.salesSince(id, since, joined, Math.min(SUMMARY_DETAILS, this.settings.get().historySize()));
        sales.whenComplete((summary, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Reading the auction sales of " + player.getName() + " failed", error);
            }
            AuctionEngine.SalesSince<ItemStack> found = error == null ? summary : AuctionEngine.SalesSince.none();
            this.services.scheduler().entity(player, () -> showJoinSummary(player, found), null);
        });
    }

    private void showJoinSummary(Player player, AuctionEngine.SalesSince<ItemStack> sales) {
        if (!player.isOnline()) {
            return;
        }
        if (sales.count() > 0) {
            player.sendMessage(Component.join(JoinConfiguration.newlines(), soldLines(sales)));
            this.services.messenger().feedback(player, Feedback.NOTIFY);
        }
        int waiting = this.claims.count(player.getUniqueId());
        if (waiting > 0 && this.settings.get().joinReminder()) {
            this.services.messenger().send(player, AuctionMessages.CLAIM_REMINDER, Arg.number("count", waiting));
        }
    }

    /** The "while you were away" lines: one sale by name, or the count and earnings with the latest few. */
    private List<Component> soldLines(AuctionEngine.SalesSince<ItemStack> sales) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>();
        if (sales.count() == 1 && sales.latest().size() == 1) {
            AuctionEngine.HistoryEntry<ItemStack> sale = sales.latest().getFirst();
            lines.add(lang.get(AuctionMessages.AWAY_SOLD_ONE, Arg.text("name", buyerName(sale)), Arg.number("amount", sale.amount()),
                Arg.text("item", AuctionItems.plainName(sale.item())), this.service.price("price", sale.price()),
                this.service.price("earned", sale.price() - sale.tax())));
            return lines;
        }
        lines.add(lang.get(AuctionMessages.AWAY_SOLD_MANY, Arg.number("count", sales.count()), this.service.price("earned", sales.earned())));
        for (AuctionEngine.HistoryEntry<ItemStack> sale : sales.latest()) {
            lines.add(lang.get(AuctionMessages.AWAY_SOLD_LINE, Arg.number("amount", sale.amount()),
                Arg.text("item", AuctionItems.plainName(sale.item())), Arg.text("name", buyerName(sale)),
                this.service.price("price", sale.price())));
        }
        long more = sales.count() - sales.latest().size();
        if (more > 0) {
            lines.add(lang.get(AuctionMessages.AWAY_SOLD_MORE, Arg.number("count", more)));
        }
        return lines;
    }

    private String buyerName(AuctionEngine.HistoryEntry<ItemStack> sale) {
        return sale.counterparty() == null ? "-" : this.service.name(sale.counterparty());
    }

    @Override
    public void disable() {
        this.stopping = true;
        this.expiryTask.cancel();
        try {
            // Let every queued transaction commit and its callback register its hand-over (players can no longer
            // receive items: the region threads have stopped), then put every undelivered item back into the claim
            // box and commit that too, all before storage closes.
            this.services.database().flush();
            if (!this.claims.awaitIdle(Duration.ofSeconds(10))) {
                this.services.plugin().getLogger().warning("Some auction claims were still waiting for storage at shutdown");
            }
            this.claims.drain();
            this.services.database().flush();
        } catch (RuntimeException e) {
            this.services.plugin().getLogger().log(Level.SEVERE, "Returning pending auction items on shutdown failed", e);
        }
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "tax math", () -> {
            if (AuctionMath.tax(1_000, 500) != 50 || AuctionMath.tax(19, 500) != 0
                || AuctionMath.tax(Long.MAX_VALUE, 10_000) != Long.MAX_VALUE) {
                return "tax rounding is wrong";
            }
            return AuctionMath.parsePercent("2.5%") == 250 ? null : "2.5% did not parse to 250 basis points";
        });
        test.check(id(), "listing closes exactly once under races", AuctionFeature::raceCheck);
        test.check(id(), "item categories", () -> {
            AuctionItems items = this.service.items();
            Object[][] expected = {
                {Material.STONE, ItemCategory.BLOCKS}, {Material.DIAMOND_PICKAXE, ItemCategory.TOOLS},
                {Material.DIAMOND_SWORD, ItemCategory.COMBAT}, {Material.NETHERITE_CHESTPLATE, ItemCategory.COMBAT},
                {Material.BREAD, ItemCategory.FOOD}, {Material.GOLDEN_APPLE, ItemCategory.FOOD}, {Material.POTION, ItemCategory.POTIONS},
                {Material.ENCHANTED_BOOK, ItemCategory.BOOKS}, {Material.SPAWNER, ItemCategory.SPAWNERS}, {Material.STICK, ItemCategory.MISC}};
            for (Object[] row : expected) {
                ItemCategory actual = items.category(ItemStack.of((Material) row[0]));
                if (actual != row[1]) {
                    return row[0] + " is " + actual + ", expected " + row[1];
                }
            }
            return null;
        });
        test.check(id(), "search text", () -> {
            String text = AuctionItems.searchText(ItemStack.of(Material.DIAMOND_SWORD));
            return text.contains("diamond sword") ? null : "unexpected search text '" + text + "'";
        });
        test.check(id(), "every active listing loaded", () -> this.engine.unreadable() == 0 ? null
            : this.engine.unreadable() + " active listing(s) have unreadable items");
        test.checkAsync(id(), "memory matches storage", this.engine::verify);
        test.check(id(), "expiry timer runs", () -> {
            long now = System.currentTimeMillis();
            long limit = this.settings.get().expiryCheck().toMillis() * 2 + 10_000;
            long last = Math.max(this.service.lastSweep(), this.enabledAt);
            return now - last <= limit ? null : "no expiry sweep in the last " + limit / 1000 + "s";
        });
        test.check(id(), "no overdue listings", () -> {
            long overdue = this.engine.book().due(this.engine.now() - this.settings.get().expiryCheck().toMillis() * 2 - 10_000).size();
            return overdue == 0 ? null : overdue + " listing(s) expired long ago but are still active";
        });
    }

    /**
     * Races a buyer, the seller and the expiry against each other on fresh listings in a private book, using the same
     * check-then-close sequence the transactions run under the economy lock. Exactly one may win each listing.
     */
    static String raceCheck() {
        ListingBook<String> book = new ListingBook<>();
        ReentrantLock lock = new ReentrantLock();
        UUID seller = new UUID(1, 1);
        UUID buyer = new UUID(2, 2);
        int rounds = 50;
        for (int round = 1; round <= rounds; round++) {
            long created = 1_000;
            long expires = 2_000;
            book.open(new Listing<>(round, seller, "item", "minecraft:stone", "stone", ItemCategory.BLOCKS, 1, 10, created, expires), true);
            long now = expires + ThreadLocalRandom.current().nextInt(-1, 2);
            List<ListingBook.Closing> attempts = List.of(new ListingBook.Sale(buyer, 10, now),
                new ListingBook.Cancellation(seller), new ListingBook.Expiry(now), new ListingBook.Sale(buyer, 10, now));
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            long id = round;
            for (ListingBook.Closing attempt : attempts) {
                runs.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    lock.lock();
                    try {
                        if (book.check(id, attempt) == null && book.close(id) != null) {
                            winners.incrementAndGet();
                        }
                    } finally {
                        lock.unlock();
                    }
                }));
            }
            start.countDown();
            CompletableFuture.allOf(runs.toArray(new CompletableFuture<?>[0])).join();
            if (winners.get() != 1) {
                return "round " + round + " had " + winners.get() + " winners";
            }
            if (book.get(id) != null) {
                return "round " + round + " left the listing open";
            }
        }
        return book.size() == 0 ? null : book.size() + " listings left open";
    }
}
