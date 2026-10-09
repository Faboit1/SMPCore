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
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * The auction house: players list items for a price, others buy them; expired and taken-down items and purchases
 * go through the claim box. Listings live in memory (loaded at startup) and change only inside economy
 * transactions, so money, listing state, rows and deliveries always move together.
 */
public final class AuctionFeature implements Feature, Listener {

    public static final Toggle SALE_NOTIFICATIONS = new Toggle("auction-sales", true,
        AuctionMessages.SETTING_SALES, AuctionMessages.SETTING_SALES_DESCRIPTION, null);

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

    public AuctionFeature(Services services, List<ConfigProblem> problems, CombatStatus combat) {
        this.services = services;
        this.settings = services.configs().register("features/auction.yml",
            reader -> AuctionSettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(AuctionMessages.class);
        services.settings().register(SALE_NOTIFICATIONS);
        var perms = services.permissions();
        perms.declare(AuctionService.PERMISSION_USE, "Use the auction house with /ah", true);
        perms.declare(AuctionService.PERMISSION_SELL, "List items on the auction house", true);
        perms.declare(AuctionService.PERMISSION_ADMIN, "Remove listings and use /ah admin", false);
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
        this.service = new AuctionService(services, this.settings, this.engine, new AuctionItems(), this.claims, combat, SALE_NOTIFICATIONS);
        this.dialogs = new AuctionDialogs(services, this.service);
        this.menus = new AuctionMenus(services, this.service, this.dialogs);
        this.commands = new AuctionCommands(services, this.service, this.menus, this.dialogs);
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
     */
    private void openFromHub(Player player) {
        Plugin axAuctions = Bukkit.getPluginManager().getPlugin(AXAUCTIONS);
        if (axAuctions != null && axAuctions.isEnabled()) {
            PlayerCommandPreprocessEvent asTyped = new PlayerCommandPreprocessEvent(player, "/ah");
            if (asTyped.callEvent()) {
                String line = asTyped.getMessage();
                player.performCommand(line.startsWith("/") ? line.substring(1) : line);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!this.settings.get().joinReminder()) {
            return;
        }
        this.services.scheduler().entityLater(player, () -> {
            int waiting = this.claims.count(player.getUniqueId());
            if (waiting > 0 && player.isOnline()) {
                this.services.messenger().send(player, AuctionMessages.CLAIM_REMINDER, Arg.number("count", waiting));
            }
        }, null, 60L);
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
