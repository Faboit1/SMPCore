package net.siftvanilla.siftcore.feature.shards;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /shards (balance, shop, staff changes, waiting key purchases) and /shardshop. */
final class ShardsCommands {

    static final String COMMAND = "siftcore.command.shards";
    static final String OTHERS = "siftcore.command.shards.others";
    static final String SHOP = "siftcore.command.shardshop";
    static final String ADMIN = "siftcore.admin.shards";

    private final Services services;
    private final ShardShop shop;
    private final ShardsFeature feature;
    private final CommandSupport support;

    ShardsCommands(Services services, ShardShop shop, ShardsFeature feature) {
        this.services = services;
        this.shop = shop;
        this.feature = feature;
        this.support = services.commands();
    }

    List<SiftCommand> all() {
        return List.of(shards(), shardShop());
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /shards

    private SiftCommand shards() {
        return new SimpleCommand("shards", List.of("shard"), "Shows your shards", COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(COMMAND))
                .executes(ctx -> player(ctx, this::showOwn))
                .then(Commands.literal("shop")
                    .requires(CommandSupport.playerPermission(SHOP))
                    .executes(ctx -> player(ctx, p -> this.shop.open(p, null))))
                .then(adminChange("give"))
                .then(adminChange("take"))
                .then(adminChange("set"))
                .then(Commands.literal("pending")
                    .requires(CommandSupport.permission(ADMIN))
                    .executes(ctx -> pending(ctx.getSource().getSender(), false))
                    .then(Commands.literal("retry").executes(ctx -> pending(ctx.getSource().getSender(), true))))
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.permission(OTHERS))
                    .executes(ctx -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> messenger().chat(ctx.getSource().getSender(), ShardsMessages.BALANCE_OTHER,
                            Arg.text("name", this.services.directory().name(uuid)),
                            Arg.shards("amount", this.services.ledger().balance(uuid, Currency.SHARDS))));
                        return CommandSupport.OK;
                    })));
    }

    private void showOwn(Player player) {
        messenger().chat(player, ShardsMessages.BALANCE_SELF,
            Arg.shards("amount", this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS)));
        messenger().chat(player, ShardsMessages.BALANCE_EARN);
    }

    private SiftCommand shardShop() {
        return new SimpleCommand("shardshop", List.of("sshop"), "Opens the shard shop", SHOP,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(SHOP))
                .executes(ctx -> player(ctx, p -> this.shop.open(p, null))));
    }

    // ------------------------------------------------------------------ staff

    private LiteralArgumentBuilder<CommandSourceStack> adminChange(String action) {
        return Commands.literal(action)
            .requires(CommandSupport.permission(ADMIN))
            .then(this.support.knownPlayer("player")
                .then(CommandSupport.amount("amount").executes(ctx -> change(ctx, action))));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private int change(CommandContext<CommandSourceStack> ctx, String action) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String input = StringArgumentType.getString(ctx, "amount");
        MoneyFormat format = this.services.money().get();
        MoneyFormat.ParseResult parsed = format.parse(input, action.equals("set"));
        if (!parsed.ok()) {
            this.support.money(sender, input);
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        long amount = parsed.amount();
        String name = this.services.directory().name(uuid);
        String actor = actor(sender);
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).note("shards " + action);
        long current = this.services.ledger().balance(uuid, Currency.SHARDS);
        switch (action) {
            case "give" -> tx.source(uuid, Currency.SHARDS, amount, "admin_give", null);
            case "take" -> {
                if (current < amount) {
                    messenger().chat(sender, ShardsMessages.ADMIN_NOT_ENOUGH, Arg.text("name", name), Arg.shards("balance", current));
                    return CommandSupport.OK;
                }
                tx.sink(uuid, Currency.SHARDS, amount, "admin_take", null);
            }
            default -> {
                long delta = amount - current;
                if (delta == 0) {
                    messenger().chat(sender, ShardsMessages.ADMIN_SET, Arg.text("name", name), Arg.shards("amount", amount));
                    return CommandSupport.OK;
                }
                if (delta > 0) {
                    tx.source(uuid, Currency.SHARDS, delta, "admin_set", null);
                } else {
                    tx.sink(uuid, Currency.SHARDS, -delta, "admin_set", null);
                }
                tx.check(() -> this.services.ledger().balance(uuid, Currency.SHARDS) == current ? null : "changed");
            }
        }
        TransactionResult result = this.services.ledger().execute(tx.build());
        if (!result.success()) {
            String reason = switch (result.status()) {
                case INSUFFICIENT_FUNDS -> "not enough shards";
                case BALANCE_LIMIT -> "over the balance limit";
                case REJECTED -> "the balance changed meanwhile, try again";
                case CANCELLED -> "cancelled by another plugin";
                case UNAVAILABLE -> "the economy is paused";
                case SUCCESS -> "";
            };
            messenger().chat(sender, ShardsMessages.ADMIN_FAILED, Arg.text("reason", reason));
            return CommandSupport.OK;
        }
        long balance = this.services.ledger().balance(uuid, Currency.SHARDS);
        switch (action) {
            case "give" -> {
                messenger().chat(sender, ShardsMessages.ADMIN_GIVEN, Arg.text("name", name), Arg.shards("amount", amount),
                    Arg.shards("balance", balance));
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online != sender) {
                    messenger().send(online, ShardsMessages.RECEIVED, Arg.shards("amount", amount), Arg.shards("balance", balance));
                }
            }
            case "take" -> messenger().chat(sender, ShardsMessages.ADMIN_TAKEN, Arg.text("name", name), Arg.shards("amount", amount),
                Arg.shards("balance", balance));
            default -> messenger().chat(sender, ShardsMessages.ADMIN_SET, Arg.text("name", name), Arg.shards("amount", amount));
        }
        this.services.audit().record(actor, "shards." + action, uuid.toString(), Long.toString(amount));
        return CommandSupport.OK;
    }

    private int pending(CommandSender sender, boolean retry) {
        List<KeyGrants.Purchase> pending = this.feature.grants().pending();
        messenger().chat(sender, ShardsMessages.ADMIN_PENDING_HEADER, Arg.number("count", pending.size()));
        long now = System.currentTimeMillis();
        for (KeyGrants.Purchase purchase : pending) {
            messenger().chat(sender, ShardsMessages.ADMIN_PENDING_LINE, Arg.text("name", this.services.directory().name(purchase.player())),
                Arg.number("keys", purchase.keys()), Arg.text("crate", purchase.crate().toLowerCase(Locale.ROOT)),
                Arg.shards("cost", purchase.cost()), Arg.time("ago", Duration.ofMillis(Math.max(0, now - purchase.created()))));
        }
        if (retry && !pending.isEmpty()) {
            messenger().chat(sender, ShardsMessages.ADMIN_PENDING_RETRY);
            this.services.audit().record(actor(sender), "shards.pending.retry", null, pending.size() + " purchase(s)");
            this.feature.resume();
        }
        return CommandSupport.OK;
    }
}
