package net.siftvanilla.siftcore.feature.shop;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.economy.ClaimHandouts;
import net.siftvanilla.siftcore.feature.sell.Pricing;
import net.siftvanilla.siftcore.feature.sell.SellLink;
import net.siftvanilla.siftcore.feature.sell.ShopOffers;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The server shop: categories of items and spawners for money, configured in {@code features/shop.yml}. Every price
 * is validated against the worth table so buying something and selling it back (or crafting it into something
 * that sells) can never pay out, even with the best rank multiplier.
 * <p>
 * Player settings (group Shop, auction &amp; orders): from which total a purchase asks for confirmation, how many the
 * purchase dialog starts with, and where the receipt shows.
 */
public final class ShopFeature implements Feature, Listener {

    /** From which total buying asks once more; "server default" follows {@code confirm-above} in shop.yml. */
    public static final Choice<ConfirmAbove> CONFIRM_ABOVE = Choices.confirmAbove("shop-confirm-above", Currency.MONEY, true,
            "10k", "100k", "1m")
        .text(ShopMessages.SETTING_CONFIRM_ABOVE, ShopMessages.SETTING_CONFIRM_ABOVE_DESCRIPTION).build();
    /** How many the purchase dialog starts with when it opens from the shop or a search. */
    public static final Choice<StartAmount> DEFAULT_AMOUNT = Choice.ofEnum("shop-default-amount", StartAmount.class,
            StartAmount::id, StartAmount.STACK)
        .option(StartAmount.STACK, ShopMessages.SETTING_DEFAULT_AMOUNT_STACK)
        .option(StartAmount.ONE, ShopMessages.SETTING_DEFAULT_AMOUNT_ONE)
        .option(StartAmount.LAST, ShopMessages.SETTING_DEFAULT_AMOUNT_LAST)
        .option(StartAmount.FILL, ShopMessages.SETTING_DEFAULT_AMOUNT_FILL)
        .text(ShopMessages.SETTING_DEFAULT_AMOUNT, ShopMessages.SETTING_DEFAULT_AMOUNT_DESCRIPTION).build();
    /** Where the purchase receipt shows; items sent to the claim box are always told in chat. */
    public static final Choice<AlertStyle> RECEIPTS = Choices.alert("shop-receipts", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.ACTIONBAR)
        .text(ShopMessages.SETTING_RECEIPTS, ShopMessages.SETTING_RECEIPTS_DESCRIPTION).build();

    static final String PERMISSION = "siftcore.command.shop";

    private final Services services;
    private final Logger logger;
    private final Pricing.Source pricing;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final RecentPurchases recent;
    private final ClaimHandouts handouts;
    private final ShopMenus menus;

    /**
     * @param pricing  the worth table and multipliers to validate prices against (the sell feature)
     * @param sell     selling as the shop shows it: sell-back prices, carried counts, right-click selling
     * @param spawners makes spawner items for spawner entries (the spawners feature)
     * @param combat   what keeps combat-tagged players out of the shop
     */
    public ShopFeature(Services services, List<ConfigProblem> problems, Pricing.Source pricing, SellLink sell,
                       SpawnerItems spawners, CombatStatus combat) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.pricing = pricing;
        ShopSettings.Catalog catalog = ShopItems.catalog();
        this.settings = services.configs().register("features/shop.yml",
            reader -> ShopSettings.parse(reader, catalog, pricing.latest(), services.core().get().money()), problems);
        services.lang().register(ShopMessages.class);
        registerSettings(services.settings());
        services.permissions().declare(PERMISSION, "Open the shop with /shop", true);
        this.items = new ShopItems(spawners, services.lang());
        this.recent = new RecentPurchases(services.database(), this.logger);
        this.handouts = new ClaimHandouts(services.deliveries(), services.scheduler(), this.logger,
            () -> services.core().get().savePlayerAfterTrade());
        PurchaseFlow purchases = new PurchaseFlow(services, this.settings, this.items, this.handouts, sell, combat, this.recent);
        this.menus = new ShopMenus(services, this.settings, this.items, purchases, sell, this.recent);
    }

    /** The shop as other features see it: shop prices of plain items and opening their purchase dialog. */
    public ShopOffers offers() {
        return this.menus.offers();
    }

    /** Registers the shop settings in the Shop, auction &amp; orders group, in the catalog's order. */
    static void registerSettings(PlayerSettings prefs) {
        prefs.register(SettingCategories.MARKET, CONFIRM_ABOVE, SettingOptions.<ConfirmAbove>builder().order(3).build());
        prefs.register(SettingCategories.MARKET, DEFAULT_AMOUNT, SettingOptions.<StartAmount>builder().order(6).build());
        prefs.register(SettingCategories.MARKET, RECEIPTS, SettingOptions.<AlertStyle>builder().order(9).build());
    }

    @Override
    public String id() {
        return "shop";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.recent.load(online.getUniqueId());
        }
        this.services.hub().register(new HubEntry("shop", 20, ShopMessages.HUB_LABEL, ShopMessages.HUB_DESCRIPTION,
            PERMISSION, this.menus::openShop));
        summary(this.settings.get());
        this.settings.onReload(this::summary);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.recent.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.recent.forget(event.getPlayer().getUniqueId());
    }

    private void summary(ShopSettings settings) {
        int entries = 0;
        int spawnerEntries = 0;
        for (ShopSettings.Category category : settings.categories()) {
            for (ShopSettings.Entry entry : category.entries()) {
                entries++;
                if (entry.spawner()) {
                    spawnerEntries++;
                }
            }
        }
        String spawners = spawnerEntries == 0 ? ""
            : this.items.spawnerProvider() ? ", " + spawnerEntries + " of them spawners"
            : "; its " + spawnerEntries + " spawner entries are hidden until spawner items are available";
        this.logger.info("Shop: " + settings.categories().size() + " categories with " + entries + " items" + spawners + ".");
    }

    @Override
    public List<SiftCommand> commands() {
        CommandSupport support = this.services.commands();
        return List.of(new SimpleCommand("shop", List.of(), "Opens the server shop", PERMISSION,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(PERMISSION))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.menus.openShop(player);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("search")
                    .executes(ctx -> {
                        Player player = support.player(ctx);
                        if (player != null) {
                            this.menus.openSearch(player, null);
                        }
                        return CommandSupport.OK;
                    })
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            Player player = support.player(ctx);
                            if (player != null) {
                                this.menus.openSearch(player, StringArgumentType.getString(ctx, "text"));
                            }
                            return CommandSupport.OK;
                        })))
                .then(Commands.argument("category", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        String typed = builder.getRemainingLowerCase();
                        if ("search".startsWith(typed)) {
                            builder.suggest("search");
                        }
                        for (ShopSettings.Category category : this.menus.visibleCategories()) {
                            if (category.id().startsWith(typed)) {
                                builder.suggest(category.id());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        Player player = support.player(ctx);
                        if (player != null) {
                            this.menus.openCategory(player, StringArgumentType.getString(ctx, "category").toLowerCase(Locale.ROOT));
                        }
                        return CommandSupport.OK;
                    }))));
    }

    /**
     * Purchases are stored with their items in the claim box; only the claim of the part that fits the inventory waits
     * for storage and the buyer's thread. Let those claims finish and put whatever was not handed over back into the
     * claim box before storage closes (players can no longer receive items, the region threads have stopped).
     */
    @Override
    public void disable() {
        this.handouts.shutdown(this.services.database()::flush, "shop purchases");
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "prices stay above what items sell for", () -> {
            Pricing current = this.pricing.current();
            ShopValidator.Analysis analysis = ShopValidator.analyze(current);
            for (ShopSettings.Entry entry : this.settings.get().entriesByRef().values()) {
                if (entry.spawner()) {
                    continue;
                }
                String problem = ShopValidator.check(entry.item(), entry.price(), current, analysis);
                if (problem != null) {
                    return entry.ref() + " " + problem;
                }
            }
            return null;
        });
        test.check(id(), "every entry can be handed out", () -> {
            long limit = this.services.money().get().maxAmount();
            for (ShopSettings.Entry entry : this.settings.get().entriesByRef().values()) {
                if (PurchaseMath.total(entry.price(), entry.max(), limit).isEmpty()) {
                    return entry.ref() + ": buying " + entry.max() + " costs more than the money limit";
                }
                if (entry.spawner()) {
                    continue;
                }
                Material material = Material.matchMaterial(entry.item());
                if (material == null || !material.isItem() || material.isAir()) {
                    return entry.ref() + ": " + entry.item() + " is not an item";
                }
            }
            return null;
        });
        test.check(id(), "spawner entries match the spawner provider", () -> {
            for (ShopSettings.Entry entry : this.settings.get().entriesByRef().values()) {
                if (!entry.spawner() || !this.items.available(entry)) {
                    continue;
                }
                Optional<ItemStack> unit = this.items.unit(entry);
                if (unit.isEmpty()) {
                    return entry.ref() + ": the spawner provider lists " + ShopItems.mobId(entry) + " but makes no item for it";
                }
            }
            return null;
        });
    }
}
