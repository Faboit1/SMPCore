package net.siftvanilla.siftcore.feature.auction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /ah}: browse (optionally searching), sell the held item, your listings, the claim box, history, and staff
 * tools that also work from the console.
 */
final class AuctionCommands {

    private final Services services;
    private final CommandSupport support;
    private final AuctionService service;
    private final AuctionMenus menus;
    private final AuctionDialogs dialogs;

    AuctionCommands(Services services, AuctionService service, AuctionMenus menus, AuctionDialogs dialogs) {
        this.services = services;
        this.support = services.commands();
        this.service = service;
        this.menus = menus;
        this.dialogs = dialogs;
    }

    List<SiftCommand> all() {
        return List.of(new SimpleCommand("ah", List.of("auction", "auctionhouse"), "Opens the auction house",
            AuctionService.PERMISSION_USE, this::tree));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree(String label) {
        String use = AuctionService.PERMISSION_USE;
        return Commands.literal(label)
            .requires(CommandSupport.permission(use))
            .executes(ctx -> player(ctx, player -> this.menus.openMain(player)))
            .then(Commands.literal("search")
                .requires(CommandSupport.playerPermission(use))
                .then(Commands.argument("query", StringArgumentType.greedyString())
                    .executes(ctx -> player(ctx, player -> this.menus.openMain(player, clip(StringArgumentType.getString(ctx, "query")))))))
            .then(Commands.literal("sell")
                .requires(CommandSupport.playerPermission(AuctionService.PERMISSION_SELL))
                .then(CommandSupport.amount("price")
                    .executes(ctx -> sell(ctx, 0))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 99))
                        .executes(ctx -> sell(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
            .then(Commands.literal("listings")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.menus.openMine(player, null))))
            .then(Commands.literal("claims")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.menus.openClaims(player, null))))
            .then(Commands.literal("history")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> player(ctx, player -> this.dialogs.history(player, null))))
            .then(admin());
    }

    private static String clip(String query) {
        String trimmed = query.strip();
        return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
    }

    /** Runs an action for a player sender after the command cooldown from commands.yml. */
    private int player(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null && this.support.cooldown(player, "ah")) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    private int sell(CommandContext<CommandSourceStack> ctx, int amount) {
        Player player = this.support.player(ctx);
        if (player == null || !this.support.cooldown(player, "ah")) {
            return CommandSupport.OK;
        }
        OptionalLong price = this.support.money(ctx, "price");
        if (price.isEmpty()) {
            return CommandSupport.OK;
        }
        switch (this.service.prepareSale(player, price.getAsLong(), amount)) {
            case AuctionService.Problem problem -> this.services.messenger().send(player, problem.key(), problem.args());
            case AuctionService.SaleDraft draft -> this.dialogs.sellConfirm(player, draft, null);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ staff

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
            .requires(CommandSupport.permission(AuctionService.PERMISSION_ADMIN))
            .then(Commands.literal("info").executes(ctx -> info(ctx.getSource().getSender())))
            .then(Commands.literal("list")
                .then(this.support.knownPlayer("player").executes(this::list)))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                    .executes(ctx -> {
                        this.service.remove(ctx.getSource().getSender(), LongArgumentType.getLong(ctx, "id"));
                        return CommandSupport.OK;
                    })))
            .then(Commands.literal("expire").executes(ctx -> expire(ctx.getSource().getSender())));
    }

    private int info(CommandSender sender) {
        ListingBook<ItemStack> book = this.service.engine().book();
        var lang = this.services.lang();
        Duration next = this.service.nextExpiry();
        this.services.messenger().chat(sender, AuctionMessages.ADMIN_INFO,
            Arg.number("active", book.size()), Arg.number("sellers", book.sellers()), Arg.number("pending", book.unsavedCount()),
            Arg.number("unreadable", this.service.engine().unreadable()), Arg.number("claims", this.services.deliveries().totalPending()),
            Arg.component("next", next == null ? lang.get(AuctionMessages.ADMIN_NO_EXPIRY)
                : lang.get(AuctionMessages.ADMIN_NEXT_EXPIRY, Arg.time("time", next))));
        return CommandSupport.OK;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.service.name(target.get());
        List<Listing<ItemStack>> listings = new ArrayList<>(this.service.engine().book().of(target.get()));
        listings.sort(SortOrder.NEWEST.comparator());
        var messenger = this.services.messenger();
        if (listings.isEmpty()) {
            messenger.chat(sender, AuctionMessages.ADMIN_LIST_EMPTY, Arg.text("name", name));
            return CommandSupport.OK;
        }
        messenger.chat(sender, AuctionMessages.ADMIN_LIST_HEADER, Arg.text("name", name), Arg.number("count", listings.size()));
        long now = this.service.engine().now();
        for (Listing<ItemStack> listing : listings) {
            messenger.chat(sender, AuctionMessages.ADMIN_LIST_LINE, Arg.text("id", Long.toString(listing.id())),
                Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
                this.service.price("price", listing.price()), Arg.time("time", Duration.ofMillis(listing.millisLeft(now))));
        }
        return CommandSupport.OK;
    }

    private int expire(CommandSender sender) {
        this.services.scheduler().async(() -> this.service.sweep().whenComplete((count, error) ->
            this.services.messenger().chat(sender, AuctionMessages.ADMIN_EXPIRED, Arg.number("count", error == null ? count : 0))));
        return CommandSupport.OK;
    }
}
