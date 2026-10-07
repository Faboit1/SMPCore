package net.siftvanilla.siftcore.feature.economy;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /balance, /pay, /baltop and /eco. */
final class EconomyCommands {

    private final Services services;
    private final CommandSupport support;
    private final EconomyService economy;
    private final PayService pay;
    private final Setting<EconomySettings> settings;

    EconomyCommands(Services services, EconomyService economy, PayService pay, Setting<EconomySettings> settings) {
        this.services = services;
        this.support = services.commands();
        this.economy = economy;
        this.pay = pay;
        this.settings = settings;
    }

    List<SiftCommand> all() {
        return List.of(balance(), payCommand(), baltop(), eco());
    }

    // ------------------------------------------------------------------ /balance

    private SiftCommand balance() {
        return new SimpleCommand("balance", List.of("bal", "money"), "Shows your balance", "siftcore.command.balance",
            label -> Commands.literal(label)
                .requires(CommandSupport.permission("siftcore.command.balance"))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.services.messenger().chat(player, EconomyMessages.BALANCE_SELF,
                            Arg.money("amount", this.economy.balance(player.getUniqueId(), Currency.MONEY)),
                            Arg.number("shards", this.economy.balance(player.getUniqueId(), Currency.SHARDS)));
                    }
                    return CommandSupport.OK;
                })
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.permission("siftcore.command.balance.others"))
                    .executes(ctx -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> this.services.messenger().chat(this.support.sender(ctx), EconomyMessages.BALANCE_OTHER,
                            Arg.text("name", this.services.directory().name(uuid)),
                            Arg.money("amount", this.economy.balance(uuid, Currency.MONEY)),
                            Arg.number("shards", this.economy.balance(uuid, Currency.SHARDS))));
                        return CommandSupport.OK;
                    })));
    }

    // ------------------------------------------------------------------ /pay

    private SiftCommand payCommand() {
        return new SimpleCommand("pay", List.of(), "Sends money to a player", "siftcore.command.pay",
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission("siftcore.command.pay"))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        openPayForm(player, "", "");
                    }
                    return CommandSupport.OK;
                })
                .then(this.support.knownPlayer("player")
                    .executes(ctx -> {
                        Player player = this.support.player(ctx);
                        if (player != null) {
                            openPayForm(player, StringArgumentType.getString(ctx, "player"), "");
                        }
                        return CommandSupport.OK;
                    })
                    .then(CommandSupport.amount("amount").executes(this::payNow))));
    }

    private int payNow(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player == null) {
            return CommandSupport.OK;
        }
        Optional<UUID> target = this.support.known(ctx, "player");
        OptionalLong amount = target.isPresent() ? this.support.money(ctx, "amount") : OptionalLong.empty();
        if (target.isPresent() && amount.isPresent()
            && this.support.cooldown(player, "pay", this.settings.get().payCooldown())) {
            this.pay.pay(player, target.get(), amount.getAsLong());
        }
        return CommandSupport.OK;
    }

    /** The pay form, also opened from the hub. */
    void openPayForm(Player player, String name, String amount) {
        var lang = this.services.lang();
        this.services.dialogs().show(player, this.services.templates().form(
            lang.get(EconomyMessages.PAY_FORM_TITLE),
            List.of(),
            List.of(Templates.text("player", lang.get(EconomyMessages.PAY_FORM_PLAYER), name, 16),
                Templates.text("amount", lang.get(EconomyMessages.PAY_FORM_AMOUNT), amount, 24)),
            submission -> {
                String targetName = submission.values().text("player");
                String amountText = submission.values().text("amount");
                Player online = Bukkit.getPlayerExact(targetName);
                Optional<UUID> target = online != null ? Optional.of(online.getUniqueId()) : this.services.directory().uuid(targetName);
                if (target.isEmpty()) {
                    submission.error(lang.get(CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", targetName)));
                    return;
                }
                var parsed = this.services.money().get().parse(amountText);
                if (!parsed.ok()) {
                    submission.error(lang.get(CoreMessages.INVALID_AMOUNT, Arg.text("input", amountText)));
                    return;
                }
                if (!this.support.cooldown(submission.player(), "pay", this.settings.get().payCooldown())) {
                    submission.close();
                    return;
                }
                submission.close();
                this.pay.pay(submission.player(), target.get(), parsed.amount());
            },
            null));
    }

    // ------------------------------------------------------------------ /baltop

    private SiftCommand baltop() {
        return new SimpleCommand("baltop", List.of("balancetop", "moneytop"), "Shows the richest players", "siftcore.command.baltop",
            label -> Commands.literal(label)
                .requires(CommandSupport.permission("siftcore.command.baltop"))
                .executes(ctx -> showTop(ctx.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> showTop(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page")))));
    }

    int showTop(CommandSender sender, int page) {
        int pageSize = this.settings.get().pageSize();
        List<EconomyApi.TopEntry> all = this.economy.top(Currency.MONEY, this.settings.get().topSize());
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        List<EconomyApi.TopEntry> slice = all.subList(Math.min(all.size(), (current - 1) * pageSize), Math.min(all.size(), current * pageSize));
        if (sender instanceof Player player) {
            openTopDialog(player, current);
            return CommandSupport.OK;
        }
        var messenger = this.services.messenger();
        messenger.chat(sender, EconomyMessages.TOP_HEADER, Arg.number("page", current), Arg.number("pages", pages));
        if (slice.isEmpty()) {
            messenger.chat(sender, EconomyMessages.TOP_EMPTY);
        }
        for (EconomyApi.TopEntry entry : slice) {
            messenger.chat(sender, EconomyMessages.TOP_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                Arg.money("amount", entry.value()));
        }
        return CommandSupport.OK;
    }

    /** The leaderboard dialog with page buttons. */
    void openTopDialog(Player player, int page) {
        var lang = this.services.lang();
        int pageSize = this.settings.get().pageSize();
        List<EconomyApi.TopEntry> all = this.economy.top(Currency.MONEY, this.settings.get().topSize());
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(EconomyMessages.TOP_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        List<EconomyApi.TopEntry> slice = all.subList(Math.min(all.size(), (current - 1) * pageSize), Math.min(all.size(), current * pageSize));
        if (slice.isEmpty()) {
            lines.add(lang.get(EconomyMessages.TOP_EMPTY));
        }
        for (EconomyApi.TopEntry entry : slice) {
            lines.add(lang.get(EconomyMessages.TOP_LINE, Arg.number("rank", entry.rank()), Arg.text("name", entry.name()),
                Arg.money("amount", entry.value())));
        }
        long own = this.economy.balance(player.getUniqueId(), Currency.MONEY);
        int rank = this.economy.leaderboard().rankOf(Currency.MONEY, own);
        if (rank > 0) {
            lines.add(Component.empty());
            lines.add(lang.get(EconomyMessages.TOP_YOU, Arg.number("rank", rank), Arg.money("amount", own)));
        }
        List<Button> buttons = new ArrayList<>();
        if (current > 1) {
            buttons.add(Button.of(lang.get(EconomyMessages.TOP_PREVIOUS), s -> openTopDialog(s.player(), current - 1)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(EconomyMessages.TOP_NEXT), s -> openTopDialog(s.player(), current + 1)).width(150));
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(EconomyMessages.TOP_TITLE), lines,
            buttons, 2, null));
    }

    // ------------------------------------------------------------------ /eco

    private SiftCommand eco() {
        String perm = "siftcore.admin.eco";
        return new SimpleCommand("eco", List.of("economy"), "Economy administration", perm,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(perm))
                .then(adminChange("give"))
                .then(adminChange("take"))
                .then(adminChange("set"))
                .then(Commands.literal("history")
                    .then(this.support.knownPlayer("player")
                        .executes(ctx -> history(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 10000))
                            .executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("resume").executes(ctx -> {
                    this.services.ledger().resume();
                    this.services.messenger().chat(ctx.getSource().getSender(), EconomyMessages.ECO_RESUMED);
                    this.services.audit().record(actor(ctx.getSource().getSender()), "eco.resume", null, null);
                    return CommandSupport.OK;
                })));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminChange(String action) {
        return Commands.literal(action)
            .then(this.support.knownPlayer("player")
                .then(CommandSupport.amount("amount")
                    .executes(ctx -> change(ctx, action, Currency.MONEY))
                    .then(Commands.literal("money").executes(ctx -> change(ctx, action, Currency.MONEY)))
                    .then(Commands.literal("shards").executes(ctx -> change(ctx, action, Currency.SHARDS)))));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private int change(CommandContext<CommandSourceStack> ctx, String action, Currency currency) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String input = StringArgumentType.getString(ctx, "amount");
        var parsed = this.services.money().get().parse(input, action.equals("set"));
        if (!parsed.ok()) {
            this.support.money(sender, input);
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        long amount = parsed.amount();
        String actor = actor(sender);
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).note("admin " + action);
        switch (action) {
            case "give" -> tx.source(uuid, currency, amount, "admin_give", null);
            case "take" -> tx.sink(uuid, currency, amount, "admin_take", null);
            default -> {
                long current = this.services.ledger().balance(uuid, currency);
                long delta = amount - current;
                if (delta == 0) {
                    this.services.messenger().chat(sender, EconomyMessages.ECO_SET, Arg.text("name", this.services.directory().name(uuid)),
                        Arg.amount("amount", currency, amount));
                    return CommandSupport.OK;
                }
                if (delta > 0) {
                    tx.source(uuid, currency, delta, "admin_set", null);
                } else {
                    tx.sink(uuid, currency, -delta, "admin_set", null);
                }
                tx.check(() -> this.services.ledger().balance(uuid, currency) == current ? null : "changed");
            }
        }
        TransactionResult result = this.services.ledger().execute(tx.build());
        String name = this.services.directory().name(uuid);
        if (!result.success()) {
            this.services.messenger().chat(sender, EconomyMessages.ECO_FAILED, Arg.text("reason", result.status().name().toLowerCase(java.util.Locale.ROOT)));
            return CommandSupport.OK;
        }
        long balance = this.services.ledger().balance(uuid, currency);
        switch (action) {
            case "give" -> this.services.messenger().chat(sender, EconomyMessages.ECO_GIVEN, Arg.text("name", name),
                Arg.amount("amount", currency, amount), Arg.amount("balance", currency, balance));
            case "take" -> this.services.messenger().chat(sender, EconomyMessages.ECO_TAKEN, Arg.text("name", name),
                Arg.amount("amount", currency, amount), Arg.amount("balance", currency, balance));
            default -> this.services.messenger().chat(sender, EconomyMessages.ECO_SET, Arg.text("name", name),
                Arg.amount("amount", currency, amount));
        }
        this.services.audit().record(actor, "eco." + action, uuid.toString(), currency.id() + " " + amount);
        return CommandSupport.OK;
    }

    private int history(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        int pageSize = this.settings.get().pageSize();
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        this.services.ledger().history(uuid, pageSize, (page - 1) * pageSize).whenComplete((rows, error) -> {
            var messenger = this.services.messenger();
            if (error != null) {
                messenger.chat(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            messenger.chat(sender, EconomyMessages.ECO_HISTORY_HEADER, Arg.text("name", name), Arg.number("page", page));
            if (rows.isEmpty()) {
                messenger.chat(sender, EconomyMessages.ECO_HISTORY_EMPTY);
            }
            long now = System.currentTimeMillis();
            for (EconomyApi.LedgerEntry row : rows) {
                messenger.chat(sender, EconomyMessages.ECO_HISTORY_LINE,
                    Arg.number("id", row.id()),
                    Arg.text("sign", row.delta() < 0 ? "-" : "+"),
                    Arg.amount("amount", row.currency(), Math.abs(row.delta())),
                    Arg.text("kind", row.kind()),
                    Arg.time("ago", Duration.ofMillis(Math.max(0, now - row.timestamp()))),
                    Arg.amount("balance", row.currency(), row.balanceAfter()));
            }
        });
        return CommandSupport.OK;
    }
}
