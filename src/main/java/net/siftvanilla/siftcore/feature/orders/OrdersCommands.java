package net.siftvanilla.siftcore.feature.orders;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /orders}: the browser (optionally searching), a new order (form, or straight to the confirmation with every
 * argument), your orders, history, one order by id (from clickable messages), and staff tools that also work from the
 * console: list (by player or item), info, cancel with a reason, the consistency check, a player's history and an
 * immediate expiry run.
 */
final class OrdersCommands {

    /** Admin list lines at most. */
    private static final int LIST_LIMIT = 20;

    private final Services services;
    private final CommandSupport support;
    private final OrderService service;
    private final OrderMenus menus;

    OrdersCommands(Services services, OrderService service, OrderMenus menus) {
        this.services = services;
        this.support = services.commands();
        this.service = service;
        this.menus = menus;
    }

    List<SiftCommand> all() {
        return List.of(new SimpleCommand("orders", List.of("order", "buyorders"), "Buy orders: ask players for items or fill their orders",
            OrderService.PERMISSION_USE, this::tree));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree(String label) {
        String use = OrderService.PERMISSION_USE;
        return Commands.literal(label)
            .requires(CommandSupport.permission(use))
            .executes(ctx -> player(ctx, player -> this.menus.browser(player, null)))
            .then(Commands.literal("create")
                .requires(CommandSupport.playerPermission(OrderService.PERMISSION_CREATE))
                .executes(ctx -> player(ctx, player -> this.menus.dialogs().createForm(player, null)))
                .then(itemArgument()
                    .then(Commands.argument("quantity", StringArgumentType.word())
                        .then(CommandSupport.amount("price").executes(this::createWithArguments)))))
            .then(Commands.literal("mine")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.menus.own(player, null, null))))
            .then(Commands.literal("history")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.menus.past(player, null, null)))
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.playerPermission(OrderService.PERMISSION_ADMIN))
                    .executes(ctx -> player(ctx, player -> this.support.known(ctx, "player")
                        .ifPresent(target -> this.menus.past(player, target, null))))))
            .then(Commands.literal("deliveries")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.menus.deliveries(player, null, null))))
            .then(Commands.literal("order")
                .requires(CommandSupport.playerPermission(use))
                .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::order)))
            .then(admin())
            .then(Commands.argument("search", StringArgumentType.greedyString())
                .requires(CommandSupport.playerPermission(use))
                .suggests((context, builder) -> {
                    String remaining = builder.getRemainingLowerCase();
                    for (String path : activeItemPaths()) {
                        if (path.startsWith(remaining)) {
                            builder.suggest(path);
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> player(ctx, player -> this.menus.browser(player, searchQuery(StringArgumentType.getString(ctx, "search"))))));
    }

    /** The item paths that have active orders (for search suggestions), sorted. */
    private List<String> activeItemPaths() {
        TreeSet<String> paths = new TreeSet<>();
        for (String key : this.service.book().bidsByKey().keySet()) {
            paths.add(OrderItems.path(OrderKeys.itemType(key)));
        }
        return new ArrayList<>(paths);
    }

    /** What a typed search becomes: underscores as spaces (item keys match names), at most 48 characters. */
    static String searchQuery(String raw) {
        String text = raw.strip().replace('_', ' ');
        return text.length() > 48 ? text.substring(0, 48) : text;
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> itemArgument() {
        return Commands.argument("item", StringArgumentType.word()).suggests((context, builder) -> {
            for (String path : this.service.items().suggest(builder.getRemainingLowerCase(), 50)) {
                builder.suggest(path);
            }
            return builder.buildFuture();
        });
    }

    /** Runs an action for a player sender after the command cooldown from commands.yml. */
    private int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null && this.support.cooldown(player, "orders")) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    private int createWithArguments(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player == null || !this.support.cooldown(player, "orders")) {
            return CommandSupport.OK;
        }
        String item = StringArgumentType.getString(ctx, "item").replace('_', ' ');
        OrderService.Prepared prepared = this.service.prepare(player, item, null, StringArgumentType.getString(ctx, "quantity"),
            StringArgumentType.getString(ctx, "price"));
        switch (prepared) {
            case OrderService.Problem problem -> this.service.send(player, problem);
            case OrderService.Draft draft -> this.menus.dialogs().confirm(player, draft);
        }
        return CommandSupport.OK;
    }

    private int order(CommandContext<CommandSourceStack> ctx) {
        return player(ctx, player -> {
            Order order = this.service.book().get(LongArgumentType.getLong(ctx, "id"));
            if (order == null) {
                this.services.messenger().send(player, OrdersMessages.CANCEL_GONE);
            } else if (order.owner().equals(player.getUniqueId())) {
                if (this.service.usable(player)) {
                    this.menus.dialogs().own(player, order, null);
                }
            } else if (player.hasPermission(OrderService.PERMISSION_ADMIN)) {
                this.menus.dialogs().staff(player, order, null);
            } else {
                this.services.messenger().send(player, OrdersMessages.NOT_YOURS);
            }
        });
    }

    // ------------------------------------------------------------------ staff

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
            .requires(CommandSupport.permission(OrderService.PERMISSION_ADMIN))
            .then(Commands.literal("list")
                .executes(ctx -> list(ctx.getSource().getSender(), null))
                .then(Commands.argument("filter", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        String remaining = builder.getRemainingLowerCase();
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                builder.suggest(online.getName());
                            }
                        }
                        for (String path : activeItemPaths()) {
                            if (path.startsWith(remaining)) {
                                builder.suggest(path);
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> list(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "filter")))))
            .then(Commands.literal("info")
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                    .executes(ctx -> info(ctx.getSource().getSender(), LongArgumentType.getLong(ctx, "id")))))
            .then(Commands.literal("cancel")
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                    .executes(ctx -> {
                        this.service.staffCancel(ctx.getSource().getSender(), LongArgumentType.getLong(ctx, "id"), "");
                        return CommandSupport.OK;
                    })
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            this.service.staffCancel(ctx.getSource().getSender(), LongArgumentType.getLong(ctx, "id"),
                                StringArgumentType.getString(ctx, "reason"));
                            return CommandSupport.OK;
                        }))))
            .then(Commands.literal("check").executes(ctx -> check(ctx.getSource().getSender())))
            .then(Commands.literal("history")
                .then(this.support.knownPlayer("player").executes(ctx -> {
                    Optional<UUID> target = this.support.known(ctx, "player");
                    target.ifPresent(uuid -> history(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })))
            .then(Commands.literal("expire").executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                this.services.scheduler().async(() -> {
                    int count = this.service.sweep();
                    this.services.messenger().chat(sender, OrdersMessages.ADMIN_EXPIRED, Arg.number("count", count));
                });
                return CommandSupport.OK;
            }));
    }

    private int list(CommandSender sender, String filter) {
        List<Order> orders = new ArrayList<>(this.service.book().all());
        if (filter != null && !filter.isBlank()) {
            Optional<UUID> owner = Optional.ofNullable(Bukkit.getPlayerExact(filter)).map(Player::getUniqueId)
                .or(() -> this.services.directory().uuid(filter));
            if (owner.isPresent()) {
                orders.removeIf(order -> !order.owner().equals(owner.get()));
            } else {
                Optional<String> key = this.service.items().find(filter.replace('_', ' '));
                if (key.isEmpty()) {
                    this.services.messenger().chat(sender, CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", filter));
                    return CommandSupport.OK;
                }
                String itemType = OrderKeys.itemType(key.get());
                orders.removeIf(order -> !order.itemType().equals(itemType));
            }
        }
        orders.sort(Comparator.comparing(Order::active).reversed().thenComparing(Comparator.comparingLong(Order::created).reversed()));
        var messenger = this.services.messenger();
        if (orders.isEmpty()) {
            messenger.chat(sender, OrdersMessages.ADMIN_LIST_EMPTY);
            return CommandSupport.OK;
        }
        messenger.chat(sender, OrdersMessages.ADMIN_LIST_HEADER, Arg.number("count", Math.min(orders.size(), LIST_LIMIT)));
        for (Order order : orders.subList(0, Math.min(orders.size(), LIST_LIMIT))) {
            messenger.chat(sender, OrdersMessages.ADMIN_LIST_LINE, Arg.text("id", Long.toString(order.id())),
                Arg.text("owner", this.service.name(order.owner())), Arg.number("filled", order.filled()),
                Arg.number("quantity", order.quantity()), this.service.item("item", order.key()), Arg.money("price", order.priceEach()),
                Arg.component("state", this.services.lang().get(this.service.stateLabel(order.state()))));
        }
        if (orders.size() > LIST_LIMIT) {
            messenger.chat(sender, OrdersMessages.ADMIN_LIST_MORE, Arg.number("count", orders.size() - LIST_LIMIT));
        }
        return CommandSupport.OK;
    }

    private int info(CommandSender sender, long id) {
        Order inMemory = this.service.book().get(id);
        if (inMemory != null) {
            sendInfo(sender, inMemory);
            return CommandSupport.OK;
        }
        this.service.store().find(id).whenComplete((order, error) -> {
            if (error != null || order == null) {
                this.services.messenger().chat(sender, OrdersMessages.ADMIN_NOT_FOUND, Arg.text("id", Long.toString(id)));
                return;
            }
            sendInfo(sender, order);
        });
        return CommandSupport.OK;
    }

    private void sendInfo(CommandSender sender, Order order) {
        this.services.messenger().chat(sender, OrdersMessages.ADMIN_INFO, Arg.text("id", Long.toString(order.id())),
            Arg.text("owner", this.service.name(order.owner())), this.service.item("item", order.key()),
            Arg.number("filled", order.filled()), Arg.number("quantity", order.quantity()), Arg.number("collected", order.collected()),
            Arg.money("price", order.priceEach()), Arg.money("held", order.escrow()),
            Arg.component("state", this.services.lang().get(this.service.stateLabel(order.state()))),
            Arg.time("time", Duration.ofMillis(order.millisLeft(this.service.engine().now()))));
    }

    /**
     * The consistency check: the orders escrow account equals what open orders hold, the book follows its own rules,
     * the bid index matches the book, memory matches storage, and every variant order can be built.
     */
    private int check(CommandSender sender) {
        var messenger = this.services.messenger();
        for (Order order : this.service.book().all()) {
            if (this.service.items().of(order) == null) {
                messenger.chat(sender, OrdersMessages.ADMIN_CHECK_UNAVAILABLE, Arg.text("id", Long.toString(order.id())),
                    Arg.text("item", order.key()));
            }
        }
        String problem = this.service.engine().verifyEscrow();
        if (problem == null) {
            problem = this.service.engine().verifyIndex();
        }
        if (problem != null) {
            messenger.chat(sender, OrdersMessages.ADMIN_CHECK_FAILED, Arg.text("problem", problem));
            return CommandSupport.OK;
        }
        long[] memory = this.services.ledger().locked(() -> new long[] {this.service.book().activeTotal(), this.service.book().escrowTotal()});
        this.service.store().totalsInOrder().whenComplete((stored, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "The orders check could not read storage", error);
                messenger.chat(sender, OrdersMessages.ADMIN_CHECK_FAILED, Arg.text("problem", "storage could not be read"));
                return;
            }
            long account = this.services.ledger().balance(SystemAccounts.ORDERS_ESCROW, Currency.MONEY);
            if (stored[1] != account) {
                messenger.chat(sender, OrdersMessages.ADMIN_CHECK_FAILED, Arg.text("problem",
                    "stored orders hold " + stored[1] + " but the escrow account holds " + account));
                return;
            }
            messenger.chat(sender, OrdersMessages.ADMIN_CHECK, Arg.number("orders", memory[0]), Arg.money("held", memory[1]),
                Arg.money("account", account));
        });
        return CommandSupport.OK;
    }

    private void history(CommandSender sender, UUID owner) {
        String name = this.service.name(owner);
        this.service.store().history(owner, LIST_LIMIT).whenComplete((rows, error) -> {
            var messenger = this.services.messenger();
            if (error != null) {
                messenger.chat(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            if (rows.isEmpty()) {
                messenger.chat(sender, OrdersMessages.ADMIN_HISTORY_EMPTY, Arg.text("name", name));
                return;
            }
            messenger.chat(sender, OrdersMessages.ADMIN_HISTORY_HEADER, Arg.text("name", name), Arg.number("count", rows.size()));
            long now = System.currentTimeMillis();
            for (OrderStore.Past past : rows) {
                Order order = past.order();
                long endedAt = order.ended() > 0 ? order.ended() : order.expires();
                messenger.chat(sender, OrdersMessages.ADMIN_HISTORY_LINE, Arg.text("id", Long.toString(order.id())),
                    Arg.component("state", this.services.lang().get(this.service.stateLabel(order.state()))),
                    Arg.number("filled", order.filled()), Arg.number("quantity", order.quantity()), this.service.item("item", order.key()),
                    Arg.money("price", order.priceEach()), Arg.money("refunded", order.refunded()),
                    Arg.time("ago", Duration.ofMillis(Math.max(0, now - endedAt))));
            }
        });
    }
}
