package net.siftvanilla.siftcore.feature.sell;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

/**
 * {@code /sell} (menu, {@code hand}, {@code hand all}, {@code all}, {@code mastery}, {@code top}, {@code history},
 * {@code admin mastery}) and {@code /worth} (held item, named item, {@code list}).
 */
final class SellCommands {

    static final String SELL = "siftcore.command.sell";
    static final String SELL_HAND = "siftcore.command.sell.hand";
    static final String SELL_ALL = "siftcore.command.sell.all";
    static final String WORTH = "siftcore.command.worth";
    static final String WORTH_DETAILS = "siftcore.worth.details";
    static final String ADMIN = "siftcore.admin.sell";

    /** Opens the price list. */
    interface Browser {
        void open(Player player, String category, String query);
    }

    private final Services services;
    private final CommandSupport support;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final SellService sales;
    private final SellMenus menus;
    private final SellDialogs dialogs;
    private final SellHistory history;
    private final MasteryBook mastery;
    private final OrderBids bids;
    private final Supplier<ShopOffers> shop;
    private final Browser browser;

    SellCommands(Services services, WorthService worth, Setting<SellSettings> settings, SellService sales, SellMenus menus,
                 SellDialogs dialogs, SellHistory history, MasteryBook mastery, OrderBids bids, Supplier<ShopOffers> shop,
                 Browser browser) {
        this.services = services;
        this.support = services.commands();
        this.worth = worth;
        this.settings = settings;
        this.sales = sales;
        this.menus = menus;
        this.dialogs = dialogs;
        this.history = history;
        this.mastery = mastery;
        this.bids = bids;
        this.shop = shop;
        this.browser = browser;
    }

    List<SiftCommand> all() {
        return List.of(sell(), worth());
    }

    /** Runs a player-only action after the shared sell cooldown. */
    private int run(CommandContext<CommandSourceStack> ctx, boolean cooldown, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null && (!cooldown || this.support.cooldown(player, "sell"))) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    private SiftCommand sell() {
        return new SimpleCommand("sell", List.of(), "Sells items to the server", SELL,
            label -> Commands.literal(label)
                .requires(source -> CommandSupport.playerPermission(SELL).test(source) || CommandSupport.permission(ADMIN).test(source))
                .executes(ctx -> run(ctx, true, player -> {
                    if (!this.sales.blocked(player)) {
                        this.menus.open(player);
                    }
                }))
                .then(Commands.literal("hand")
                    .requires(CommandSupport.playerPermission(SELL_HAND))
                    .executes(ctx -> run(ctx, true, this.sales::sellHand))
                    .then(Commands.literal("all")
                        .executes(ctx -> run(ctx, true, this.sales::sellHeldType))))
                .then(Commands.literal("all")
                    .requires(CommandSupport.playerPermission(SELL_ALL))
                    .executes(ctx -> run(ctx, true, this.sales::sellAll)))
                .then(Commands.literal("mastery")
                    .requires(CommandSupport.playerPermission(SELL))
                    .executes(ctx -> run(ctx, false, this.dialogs::mastery)))
                .then(Commands.literal("top")
                    .requires(CommandSupport.playerPermission(SELL))
                    .executes(ctx -> run(ctx, false, this.dialogs::top)))
                .then(Commands.literal("history")
                    .requires(CommandSupport.playerPermission(SELL))
                    .executes(ctx -> run(ctx, false, this.history::open)))
                .then(Commands.literal("admin")
                    .requires(CommandSupport.permission(ADMIN))
                    .then(Commands.literal("mastery")
                        .then(this.support.knownPlayer("player")
                            .executes(ctx -> adminShow(ctx, null))
                            .then(Commands.literal("reset")
                                .executes(this::adminResetAll))
                            .then(Commands.argument("category", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (String id : this.worth.categories().ids()) {
                                        if (id.startsWith(typed)) {
                                            builder.suggest(id);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> adminShow(ctx, StringArgumentType.getString(ctx, "category")))
                                .then(Commands.literal("reset")
                                    .executes(ctx -> adminSet(ctx, 0)))
                                .then(Commands.literal("set")
                                    .then(Commands.argument("level", IntegerArgumentType.integer(0, Mastery.MAX_LEVELS))
                                        .executes(ctx -> adminSet(ctx, IntegerArgumentType.getInteger(ctx, "level"))))))))));
    }

    private SiftCommand worth() {
        return new SimpleCommand("worth", List.of(), "Shows what items sell for", WORTH,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(WORTH))
                .executes(this::worthHeld)
                .then(Commands.literal("list")
                    .executes(ctx -> list(ctx, null))
                    .then(Commands.argument("search", StringArgumentType.greedyString())
                        .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "search")))))
                .then(Commands.argument("item", ArgumentTypes.resource(RegistryKey.ITEM))
                    .executes(this::worthNamed)));
    }

    private int list(CommandContext<CommandSourceStack> ctx, String search) {
        Player player = this.support.player(ctx);
        if (player != null) {
            this.browser.open(player, search == null ? null : "all", search);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /worth

    private int worthHeld(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Messenger messenger = this.services.messenger();
        if (!(sender instanceof Player player)) {
            messenger.send(sender, SellMessages.WORTH_HOLD);
            return CommandSupport.OK;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            this.browser.open(player, null, null);
            return CommandSupport.OK;
        }
        String key = WorthService.key(item.getType());
        if (ContainerItems.isShulker(item) && !ContainerItems.contents(item).isEmpty()) {
            box(player, item, key);
            return CommandSupport.OK;
        }
        WorthTable.Entry entry = this.worth.table().entry(key);
        if (entry == null) {
            unsellable(player, key);
            orderLine(player, key);
            return CommandSupport.OK;
        }
        if (!this.worth.pristine(item)) {
            messenger.send(player, TradeGuard.marked(item) ? SellMessages.WORTH_TRADED : SellMessages.WORTH_MODIFIED,
                Arg.component("item", itemName(player, key)), Arg.money("price", entry.price()));
            return CommandSupport.OK;
        }
        int amount = item.getAmount();
        long stack = SaleMath.add(0, entry.price(), amount);
        if (amount > 1) {
            messenger.send(player, SellMessages.WORTH_STACK, Arg.component("item", itemName(player, key)),
                Arg.money("price", entry.price()), Arg.number("amount", amount), Arg.money("total", stack));
        } else {
            messenger.send(player, SellMessages.WORTH_EACH, Arg.component("item", itemName(player, key)),
                Arg.money("price", entry.price()));
        }
        bonus(player, entry, stack);
        extras(player, key);
        details(player, entry);
        return CommandSupport.OK;
    }

    /** A shulker box with something in it: what its contents sell for, and what the empty box would. */
    private void box(Player player, ItemStack box, String key) {
        Messenger messenger = this.services.messenger();
        SaleBuilder.Result preview = this.sales.preview(player, player.getInventory(), SaleRequest.hand());
        if (preview.draft() == null || preview.draft().innerCount() == 0) {
            messenger.send(player, SellMessages.WORTH_BOX_NOTHING);
        } else {
            messenger.send(player, SellMessages.WORTH_BOX, Arg.money("total", preview.draft().total()),
                Arg.number("count", preview.draft().innerCount()));
        }
        WorthTable.Entry entry = this.worth.table().entry(key);
        if (entry != null && this.worth.pristine(ContainerItems.rebuild(box, List.of()))) {
            messenger.send(player, SellMessages.WORTH_BOX_EMPTY, Arg.money("price", entry.price()));
        }
    }

    private int worthNamed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemType type = ctx.getArgument("item", ItemType.class);
        String key = Registry.ITEM.getKeyOrThrow(type).asString();
        WorthTable.Entry entry = this.worth.table().entry(key);
        if (entry == null) {
            unsellable(sender, key);
            if (sender instanceof Player player) {
                orderLine(player, key);
            }
            return CommandSupport.OK;
        }
        Component name = sender instanceof Player player ? itemName(player, key) : Component.text(ItemKeys.name(key));
        this.services.messenger().send(sender, SellMessages.WORTH_EACH, Arg.component("item", name),
            Arg.money("price", entry.price()));
        if (sender instanceof Player player) {
            bonus(player, entry, entry.price());
            extras(player, key);
        }
        details(sender, entry);
        return CommandSupport.OK;
    }

    /** The plain item name; for players it opens the price list filtered to it when clicked. */
    private Component itemName(Player player, String key) {
        String name = ItemKeys.name(key);
        if (!player.hasPermission(WORTH)) {
            return Component.text(name);
        }
        return Component.text(name)
            .clickEvent(ClickEvent.runCommand("/worth list " + name))
            .hoverEvent(HoverEvent.showText(this.services.lang().get(SellMessages.WORTH_CLICK)));
    }

    private void unsellable(CommandSender sender, String key) {
        boolean belowOne = this.worth.table().belowOne().containsKey(key);
        this.services.messenger().send(sender, belowOne ? SellMessages.WORTH_BELOW_ONE : SellMessages.WORTH_NONE,
            Arg.text("item", ItemKeys.name(key)));
    }

    /**
     * What the player really gets for {@code base}: with their bonus for the item's category (rank plus mastery) and
     * the running sell booster, when either applies.
     */
    private void bonus(Player player, WorthTable.Entry entry, long base) {
        WorthService.Rates rates = this.worth.rates(player);
        BigDecimal own = rates.own(entry.category());
        long total = SaleMath.withMultiplier(base, rates.multiplier(entry.category()));
        if (own.compareTo(BigDecimal.ONE) > 0) {
            this.services.messenger().send(player, SellMessages.WORTH_BONUS,
                Arg.text("multiplier", Multipliers.format(own.doubleValue())), Arg.money("total", total),
                Arg.component("booster", rates.boost() > 0
                    ? this.services.lang().get(SellMessages.BOOSTER_NOTE, Arg.number("percent", rates.boost())) : Component.empty()));
        } else if (rates.boost() > 0) {
            this.services.messenger().send(player, SellMessages.WORTH_BOOSTED, Arg.number("percent", rates.boost()),
                Arg.money("total", total));
        }
    }

    /** What the shop asks for the item and what the best buy order pays, when they exist. */
    private void extras(Player player, String key) {
        OptionalLong shopPrice = this.shop.get().price(key);
        if (shopPrice.isPresent()) {
            this.services.messenger().send(player, SellMessages.WORTH_SHOP, Arg.money("price", shopPrice.getAsLong()));
        }
        orderLine(player, key);
    }

    private void orderLine(Player player, String key) {
        OrderMarket.Bid best = this.bids.best(player.getUniqueId(), key);
        if (best != null) {
            this.services.messenger().send(player, SellMessages.WORTH_ORDER, Arg.money("price", best.priceEach()));
        }
    }

    /** Where the price came from, for staff. */
    private void details(CommandSender sender, WorthTable.Entry entry) {
        if (!sender.hasPermission(WORTH_DETAILS)) {
            return;
        }
        Messenger messenger = this.services.messenger();
        switch (entry.origin()) {
            case BASE -> messenger.send(sender, SellMessages.WORTH_SOURCE_BASE);
            case OVERRIDE -> messenger.send(sender, SellMessages.WORTH_SOURCE_OVERRIDE);
            case DERIVED -> messenger.send(sender, SellMessages.WORTH_SOURCE_DERIVED, Arg.text("recipe", entry.recipe()));
        }
    }

    // ------------------------------------------------------------------ /sell admin mastery

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    /** Shows a player's mastery (every category, or one). Online players from memory, offline from storage. */
    private int adminShow(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        if (category != null && this.worth.categories().category(category) == null) {
            this.services.messenger().send(sender, SellMessages.ADMIN_UNKNOWN_CATEGORY, Arg.text("name", category));
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = displayName(uuid, StringArgumentType.getString(ctx, "player"));
        totals(uuid).whenComplete((totals, error) -> {
            if (error != null) {
                this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            Map<String, Long> shown = new TreeMap<>();
            if (category == null) {
                shown.putAll(totals);
            } else {
                shown.put(category, totals.getOrDefault(category, 0L));
            }
            if (shown.isEmpty() || shown.values().stream().allMatch(value -> value <= 0) && category == null) {
                this.services.messenger().send(sender, SellMessages.ADMIN_NONE, Arg.text("player", name));
                return;
            }
            this.services.messenger().send(sender, SellMessages.ADMIN_HEADER, Arg.text("player", name));
            // Read off-thread, outside the command: written for whoever asked (their money format).
            for (Component line : this.services.lang().viewing(sender, () -> this.dialogs.adminLines(shown))) {
                sender.sendMessage(line);
            }
        });
        return CommandSupport.OK;
    }

    /** Sets a category to the start of a level (0 resets it), audited. */
    private int adminSet(CommandContext<CommandSourceStack> ctx, int level) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String category = StringArgumentType.getString(ctx, "category");
        if (this.worth.categories().category(category) == null) {
            this.services.messenger().send(sender, SellMessages.ADMIN_UNKNOWN_CATEGORY, Arg.text("name", category));
            return CommandSupport.OK;
        }
        Mastery rules = this.settings.get().mastery();
        if (level > rules.maxLevel()) {
            this.services.messenger().send(sender, SellMessages.ADMIN_LEVEL_RANGE, Arg.number("max", rules.maxLevel()));
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        if (Bukkit.getPlayer(uuid) != null && !this.mastery.loaded(uuid)) {
            this.services.messenger().send(sender, SellMessages.ADMIN_LOADING);
            return CommandSupport.OK;
        }
        long value = level == 0 ? 0 : rules.threshold(level);
        LedgerTx tx = this.mastery.setTotal(uuid, category, value);
        String name = displayName(uuid, StringArgumentType.getString(ctx, "player"));
        if (!this.services.ledger().executeDomain(tx).success()) {
            this.services.messenger().send(sender, CoreMessages.ECONOMY_UNAVAILABLE);
            return CommandSupport.OK;
        }
        this.services.audit().record(actor(sender), "sell.mastery", uuid.toString(),
            category + (level == 0 ? " reset" : " set level " + level + " (" + value + " sold)"));
        if (level == 0) {
            this.services.messenger().send(sender, SellMessages.ADMIN_RESET, Arg.text("player", name),
                Arg.text("category", this.worth.categories().name(category)));
        } else {
            this.services.messenger().send(sender, SellMessages.ADMIN_SET, Arg.text("player", name),
                Arg.text("category", this.worth.categories().name(category)), Arg.number("level", level));
        }
        return CommandSupport.OK;
    }

    /** Resets every category of a player, audited. */
    private int adminResetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        if (Bukkit.getPlayer(uuid) != null && !this.mastery.loaded(uuid)) {
            this.services.messenger().send(sender, SellMessages.ADMIN_LOADING);
            return CommandSupport.OK;
        }
        String name = displayName(uuid, StringArgumentType.getString(ctx, "player"));
        if (!this.services.ledger().executeDomain(this.mastery.resetAll(uuid)).success()) {
            this.services.messenger().send(sender, CoreMessages.ECONOMY_UNAVAILABLE);
            return CommandSupport.OK;
        }
        this.services.audit().record(actor(sender), "sell.mastery", uuid.toString(), "reset every category");
        this.services.messenger().send(sender, SellMessages.ADMIN_RESET_ALL, Arg.text("player", name));
        return CommandSupport.OK;
    }

    private CompletableFuture<Map<String, Long>> totals(UUID player) {
        if (Bukkit.getPlayer(player) != null && this.mastery.loaded(player)) {
            return CompletableFuture.completedFuture(this.mastery.totals(player));
        }
        return this.mastery.read(player);
    }

    private String displayName(UUID uuid, String typed) {
        String known = this.services.directory().name(uuid);
        return known == null ? typed : known;
    }
}
