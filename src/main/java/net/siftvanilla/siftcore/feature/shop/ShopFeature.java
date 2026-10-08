package net.siftvanilla.siftcore.feature.shop;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.feature.sell.ItemHandout;
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
 */
public final class ShopFeature implements Feature, Listener {

    static final String PERMISSION = "siftcore.command.shop";

    private final Services services;
    private final Logger logger;
    private final Pricing.Source pricing;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final RecentPurchases recent;
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
        services.permissions().declare(PERMISSION, "Open the shop with /shop", true);
        this.items = new ShopItems(spawners, services.lang());
        this.recent = new RecentPurchases(services.database(), this.logger);
        PurchaseFlow purchases = new PurchaseFlow(services, this.settings, this.items,
            new ItemHandout(services.deliveries(), this.logger), sell, combat, this.recent);
        this.menus = new ShopMenus(services, this.settings, this.items, purchases, sell, this.recent);
    }

    /** The shop as other features see it: shop prices of plain items and opening their purchase dialog. */
    public ShopOffers offers() {
        return this.menus.offers();
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
