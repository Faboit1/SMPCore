package net.siftvanilla.siftcore.feature.sell;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

/** {@code /sell}, {@code /sell hand}, {@code /sell all} and {@code /worth}. */
final class SellCommands {

    static final String SELL = "siftcore.command.sell";
    static final String SELL_HAND = "siftcore.command.sell.hand";
    static final String SELL_ALL = "siftcore.command.sell.all";
    static final String WORTH = "siftcore.command.worth";
    static final String WORTH_DETAILS = "siftcore.worth.details";

    private final Services services;
    private final CommandSupport support;
    private final WorthService worth;
    private final SellService sales;
    private final SellMenus menus;

    SellCommands(Services services, WorthService worth, SellService sales, SellMenus menus) {
        this.services = services;
        this.support = services.commands();
        this.worth = worth;
        this.sales = sales;
        this.menus = menus;
    }

    List<SiftCommand> all() {
        return List.of(sell(), worth());
    }

    private SiftCommand sell() {
        return new SimpleCommand("sell", List.of(), "Sells items to the server", SELL,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(SELL))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null && this.support.cooldown(player, "sell")) {
                        this.menus.open(player);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("hand")
                    .requires(CommandSupport.playerPermission(SELL_HAND))
                    .executes(ctx -> {
                        Player player = this.support.player(ctx);
                        if (player != null && this.support.cooldown(player, "sell")) {
                            this.sales.sellHand(player);
                        }
                        return CommandSupport.OK;
                    }))
                .then(Commands.literal("all")
                    .requires(CommandSupport.playerPermission(SELL_ALL))
                    .executes(ctx -> {
                        Player player = this.support.player(ctx);
                        if (player != null && this.support.cooldown(player, "sell")) {
                            this.sales.sellAll(player);
                        }
                        return CommandSupport.OK;
                    })));
    }

    private SiftCommand worth() {
        return new SimpleCommand("worth", List.of(), "Shows what items sell for", WORTH,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(WORTH))
                .executes(this::worthHeld)
                .then(Commands.argument("item", ArgumentTypes.resource(RegistryKey.ITEM))
                    .executes(this::worthNamed)));
    }

    private int worthHeld(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Messenger messenger = this.services.messenger();
        if (!(sender instanceof Player player)) {
            messenger.send(sender, SellMessages.WORTH_HOLD);
            return CommandSupport.OK;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            messenger.send(player, SellMessages.WORTH_HOLD);
            return CommandSupport.OK;
        }
        String key = WorthService.key(item.getType());
        WorthTable.Entry entry = this.worth.table().entry(key);
        if (entry == null) {
            unsellable(player, key);
            return CommandSupport.OK;
        }
        String name = ItemKeys.name(key);
        if (!this.worth.pristine(item)) {
            messenger.send(player, SellMessages.WORTH_MODIFIED, Arg.text("item", name), Arg.money("price", entry.price()));
            return CommandSupport.OK;
        }
        int amount = item.getAmount();
        long stack = SaleMath.add(0, entry.price(), amount);
        if (amount > 1) {
            messenger.send(player, SellMessages.WORTH_STACK, Arg.text("item", name), Arg.money("price", entry.price()),
                Arg.number("amount", amount), Arg.money("total", stack));
        } else {
            messenger.send(player, SellMessages.WORTH_EACH, Arg.text("item", name), Arg.money("price", entry.price()));
        }
        bonus(player, stack);
        details(player, entry);
        return CommandSupport.OK;
    }

    private int worthNamed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemType type = ctx.getArgument("item", ItemType.class);
        String key = Registry.ITEM.getKeyOrThrow(type).asString();
        WorthTable.Entry entry = this.worth.table().entry(key);
        if (entry == null) {
            unsellable(sender, key);
            return CommandSupport.OK;
        }
        this.services.messenger().send(sender, SellMessages.WORTH_EACH, Arg.text("item", ItemKeys.name(key)),
            Arg.money("price", entry.price()));
        if (sender instanceof Player player) {
            bonus(player, entry.price());
        }
        details(sender, entry);
        return CommandSupport.OK;
    }

    private void unsellable(CommandSender sender, String key) {
        boolean belowOne = this.worth.table().belowOne().containsKey(key);
        this.services.messenger().send(sender, belowOne ? SellMessages.WORTH_BELOW_ONE : SellMessages.WORTH_NONE,
            Arg.text("item", ItemKeys.name(key)));
    }

    /** The player's rank bonus applied to {@code base}, when they have one. */
    private void bonus(Player player, long base) {
        double multiplier = this.worth.multiplier(player);
        if (multiplier > 1.0) {
            this.services.messenger().send(player, SellMessages.WORTH_BONUS, Arg.text("multiplier", Multipliers.format(multiplier)),
                Arg.money("total", SaleMath.withMultiplier(base, multiplier)));
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
}
