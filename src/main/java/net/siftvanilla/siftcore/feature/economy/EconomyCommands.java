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
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /balance, /pay, /baltop and /eco. */
final class EconomyCommands {

    /** Staff money tools; also sees every balance whatever its owner's privacy. */
    static final String ECO_ADMIN = "siftcore.admin.eco";

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
                            Arg.shards("shard-count", this.economy.balance(player.getUniqueId(), Currency.SHARDS)));
                    }
                    return CommandSupport.OK;
                })
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.permission("siftcore.command.balance.others"))
                    .executes(ctx -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> balanceOf(this.support.sender(ctx), uuid));
                        return CommandSupport.OK;
                    })));
    }

    /**
     * {@code /balance <name>}: shown when the target's {@code balance-privacy} lets the viewer see it (read from the
     * database when they are offline). The console, staff with {@code siftcore.admin.eco} and the target themselves
     * always see it.
     */
    private void balanceOf(CommandSender sender, UUID target) {
        if (!(sender instanceof Player viewer) || viewer.getUniqueId().equals(target) || viewer.hasPermission(ECO_ADMIN)) {
            showBalance(sender, target);
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        this.services.settings().lookup(target, SharedSettings.BALANCE_PRIVACY).whenComplete((audience, error) ->
            this.services.scheduler().entity(viewer, () -> {
                if (error != null) {
                    this.services.messenger().send(viewer, CoreMessages.ACTION_FAILED);
                } else if (this.services.relations().allows(audience, target, viewerId)) {
                    showBalance(viewer, target);
                } else {
                    this.services.messenger().send(viewer, EconomyMessages.BALANCE_PRIVATE,
                        Arg.text("name", this.services.directory().name(target)));
                }
            }, null));
    }

    private void showBalance(CommandSender sender, UUID target) {
        this.services.messenger().chat(sender, EconomyMessages.BALANCE_OTHER,
            Arg.text("name", this.services.directory().name(target)),
            Arg.money("amount", this.economy.balance(target, Currency.MONEY)),
            Arg.shards("shard-count", this.economy.balance(target, Currency.SHARDS)));
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
        if (target.isPresent() && amount.isPresent()) {
            this.pay.pay(player, target.get(), amount.getAsLong());
        }
        return CommandSupport.OK;
    }

    /** The pay form from /pay: Cancel closes it. */
    void openPayForm(Player player, String name, String amount) {
        openPayForm(player, name, amount, null);
    }

    /**
     * The pay form. {@code back} (the Money page) turns Cancel into Back. Every check that needs no storage read
     * runs before the form goes away, and a refusal comes back in the form with what was typed.
     */
    void openPayForm(Player player, String name, String amount, Button.Handler back) {
        var lang = this.services.lang();
        View form = this.services.templates().form(
            lang.get(EconomyMessages.PAY_FORM_TITLE),
            List.of(),
            List.of(Templates.text("player", lang.get(EconomyMessages.PAY_FORM_PLAYER), name, 16),
                Templates.text("amount", lang.get(EconomyMessages.PAY_FORM_AMOUNT), amount, 24)),
            this::submitPayForm,
            back);
        List<Button> buttons = new ArrayList<>(form.buttons());
        buttons.set(0, buttons.getFirst().tooltip(lang.get(EconomyMessages.PAY_FORM_SUBMIT_TOOLTIP)));
        this.services.dialogs().show(player, new View(form.kind(), form.title(), form.body(), form.inputs(), buttons, form.exit(),
            form.columns(), form.escapable()));
    }

    private void submitPayForm(Submission submission) {
        var lang = this.services.lang();
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
        PayService.Refusal refusal = this.pay.refusal(submission.player(), target.get(), parsed.amount());
        if (refusal != null) {
            submission.error(lang.get(refusal.key(), refusal.args()));
            return;
        }
        Duration wait = this.pay.tryCooldown(submission.player());
        if (!wait.isZero()) {
            submission.error(lang.get(CoreMessages.COOLDOWN, Arg.time("time", wait)));
            return;
        }
        this.pay.payFromForm(submission, target.get(), parsed.amount());
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

    /** Players get the whole leaderboard in a dialog (the page is for the console's chat lines). */
    int showTop(CommandSender sender, int page) {
        if (sender instanceof Player player) {
            openTopDialog(player, null);
            return CommandSupport.OK;
        }
        int pageSize = this.settings.get().pageSize();
        List<EconomyApi.TopEntry> all = this.economy.top(Currency.MONEY, this.settings.get().topSize());
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        List<EconomyApi.TopEntry> slice = all.subList(Math.min(all.size(), (current - 1) * pageSize), Math.min(all.size(), current * pageSize));
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

    /**
     * The leaderboard dialog, the same way every leaderboard looks (the stats boards): one or two short lines (the
     * player's own place, how many are listed), then every listed player as a button, "1. Alex $5,000", the player's own
     * one highlighted. No pages: the top {@code baltop.size} (100) show and the dialog scrolls. {@code back} (the Money
     * page) adds Back, null a Close button.
     */
    void openTopDialog(Player player, Button.Handler back) {
        var lang = this.services.lang();
        int size = this.settings.get().topSize();
        List<EconomyApi.TopEntry> all = this.economy.top(Currency.MONEY, size);
        List<Component> lines = new ArrayList<>(2);
        long own = this.economy.balance(player.getUniqueId(), Currency.MONEY);
        int rank = this.economy.leaderboard().rankOf(Currency.MONEY, player.getUniqueId(), own);
        if (rank > 0) {
            lines.add(lang.get(EconomyMessages.TOP_YOU, Arg.text("rank", Lang.number(rank)), Arg.money("amount", own)));
        } else if (!all.isEmpty()) {
            lines.add(lang.get(EconomyMessages.TOP_NOT_LISTED));
        }
        lines.add(all.isEmpty() ? lang.get(EconomyMessages.TOP_EMPTY) : lang.get(EconomyMessages.TOP_SHOWN, Arg.value("count", all.size())));
        List<Button> buttons = new ArrayList<>(all.size());
        Button.Handler stay = s -> openTopDialog(s.player(), back);
        for (EconomyApi.TopEntry entry : all) {
            boolean you = entry.account().equals(player.getUniqueId());
            buttons.add(Button.of(lang.get(you ? EconomyMessages.TOP_ENTRY_YOU : EconomyMessages.TOP_ENTRY, Arg.value("rank", entry.rank()),
                Arg.text("name", entry.name()), Arg.money("amount", entry.value())), null, stay));
        }
        this.services.dialogs().show(player, this.services.templates().column(lang.get(EconomyMessages.TOP_TITLE), lines, buttons, back));
    }

    // ------------------------------------------------------------------ /eco

    private SiftCommand eco() {
        String perm = ECO_ADMIN;
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
                    this.services.messenger().chat(sender, reply(action, currency), Arg.text("name", this.services.directory().name(uuid)),
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
            case "give", "take" -> this.services.messenger().chat(sender, reply(action, currency), Arg.text("name", name),
                Arg.amount("amount", currency, amount), Arg.amount("balance", currency, balance));
            default -> this.services.messenger().chat(sender, reply(action, currency), Arg.text("name", name),
                Arg.amount("amount", currency, amount));
        }
        this.services.audit().record(actor, "eco." + action, uuid.toString(), currency.id() + " " + amount);
        return CommandSupport.OK;
    }

    /**
     * The reply of {@code /eco give|take|set}. Shards have their own lines that name the unit: a shard amount alone is
     * a bare number, which staff could take for money.
     */
    static MessageKey reply(String action, Currency currency) {
        boolean shards = currency == Currency.SHARDS;
        return switch (action) {
            case "give" -> shards ? EconomyMessages.ECO_GIVEN_SHARDS : EconomyMessages.ECO_GIVEN;
            case "take" -> shards ? EconomyMessages.ECO_TAKEN_SHARDS : EconomyMessages.ECO_TAKEN;
            default -> shards ? EconomyMessages.ECO_SET_SHARDS : EconomyMessages.ECO_SET;
        };
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
