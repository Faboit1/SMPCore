package net.siftvanilla.siftcore.feature.bounties;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /bounties (alias /bounty) for players and /bountyadmin for staff and the console. */
final class BountyCommands {

    static final String USE = "siftcore.command.bounties";
    static final String PLACE = "siftcore.bounties.place";
    static final String ADMIN = "siftcore.admin.bounties";

    private static final int SUGGESTIONS = 20;

    private final Services services;
    private final CommandSupport support;
    private final Setting<BountiesSettings> settings;
    private final BountyActions actions;
    private final BountyViews views;

    BountyCommands(Services services, Setting<BountiesSettings> settings, BountyActions actions, BountyViews views) {
        this.services = services;
        this.support = services.commands();
        this.settings = settings;
        this.actions = actions;
        this.views = views;
    }

    List<SiftCommand> all() {
        return List.of(bounties(), admin());
    }

    // ------------------------------------------------------------------ /bounties

    private SiftCommand bounties() {
        return new SimpleCommand("bounties", List.of("bounty"), "Shows the biggest bounties or puts one on a player", USE,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(USE))
                .executes(this::list)
                .then(target("player")
                    .executes(this::details)
                    .then(CommandSupport.amount("amount")
                        .requires(CommandSupport.playerPermission(PLACE))
                        .executes(this::place))));
    }

    /**
     * A player-name word for anyone who ever joined. Suggests the online players the sender can see first (a vanished
     * staff member never shows up as online), then known names once two letters are typed.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> target(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            Set<String> suggested = new HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String lower = online.getName().toLowerCase(Locale.ROOT);
                if (lower.startsWith(remaining) && (!(sender instanceof Player viewer) || viewer.canSee(online))
                    && suggested.size() < SUGGESTIONS && suggested.add(lower)) {
                    builder.suggest(online.getName());
                }
            }
            if (remaining.length() >= 2) {
                for (String known : this.services.directory().namesStartingWith(remaining, SUGGESTIONS)) {
                    if (suggested.size() >= SUGGESTIONS) {
                        break;
                    }
                    if (suggested.add(known.toLowerCase(Locale.ROOT))) {
                        builder.suggest(known);
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            this.views.openList(player, false);
            return CommandSupport.OK;
        }
        List<BountyBook.Bounty> top = this.actions.service().book().top(this.settings.get().listSize());
        this.services.messenger().chat(sender, BountiesMessages.LIST_HEADER);
        if (top.isEmpty()) {
            this.services.messenger().chat(sender, BountiesMessages.LIST_EMPTY);
        }
        for (Component line : this.views.ranking(top)) {
            sender.sendMessage(line);
        }
        return CommandSupport.OK;
    }

    private int details(CommandContext<CommandSourceStack> ctx) {
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            this.views.openDetails(player, target.get(), false);
        } else {
            info(sender, target.get());
        }
        return CommandSupport.OK;
    }

    private int place(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player == null) {
            return CommandSupport.OK;
        }
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        OptionalLong amount = this.support.money(ctx, "amount");
        if (amount.isPresent()) {
            this.actions.request(player, target.get(), amount.getAsLong(), null);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /bountyadmin

    private SiftCommand admin() {
        return new SimpleCommand("bountyadmin", List.of(), "Bounty administration", ADMIN,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(ADMIN))
                .executes(this::summary)
                .then(Commands.literal("info").then(this.support.knownPlayer("player").executes(ctx -> {
                    this.support.known(ctx, "player").ifPresent(target -> info(ctx.getSource().getSender(), target));
                    return CommandSupport.OK;
                })))
                .then(Commands.literal("remove").then(this.support.knownPlayer("player").executes(this::remove)))
                .then(Commands.literal("expire").executes(this::expire)));
    }

    private int summary(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        BountyService service = this.actions.service();
        int targets = service.book().targets();
        long total = service.book().totalActive();
        long escrow = this.services.ledger().balance(BountyService.ESCROW, Currency.MONEY);
        service.storedSummary().whenComplete((rows, error) -> this.services.messenger().chat(sender, BountiesMessages.ADMIN_SUMMARY,
            Arg.number("targets", targets), Arg.money("total", total), Arg.money("escrow", escrow),
            Arg.text("rows", error == null ? rows : "unreadable")));
        return CommandSupport.OK;
    }

    /** Every contribution on a player, with sponsors (staff and console). */
    private void info(CommandSender sender, UUID target) {
        String name = this.services.directory().name(target);
        BountyBook.Bounty bounty = this.actions.service().book().get(target);
        if (bounty == null) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_NONE, Arg.text("name", name));
            return;
        }
        this.services.messenger().chat(sender, BountiesMessages.ADMIN_INFO_HEADER, Arg.text("name", name), Arg.money("total", bounty.total()));
        long now = System.currentTimeMillis();
        Duration expireAfter = this.settings.get().expireAfter();
        for (BountyBook.Contribution contribution : bounty.contributions()) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_INFO_LINE,
                Arg.text("id", Long.toString(contribution.id())),
                Arg.money("amount", contribution.amount()),
                Arg.text("sponsor", this.services.directory().name(contribution.sponsor())),
                Arg.time("ago", Duration.ofMillis(Math.max(0, now - contribution.created()))),
                Arg.time("left", Duration.ofMillis(Math.max(0, BountyMath.expiresAt(contribution.created(), expireAfter) - now))));
        }
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.services.directory().name(target.get());
        BountyActions.RefundRun run = this.actions.remove(target.get(), actor(sender));
        if (run.refunded() == 0 && run.failed() == 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_NONE, Arg.text("name", name));
            return CommandSupport.OK;
        }
        if (run.refunded() > 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_REMOVED, Arg.text("name", name), Arg.money("amount", run.amount()),
                Arg.number("count", run.refunded()));
        }
        if (run.failed() > 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_FAILED, Arg.number("count", run.failed()));
        }
        return CommandSupport.OK;
    }

    private int expire(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        BountyActions.RefundRun run = this.actions.expire();
        if (run.refunded() > 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_EXPIRED, Arg.number("count", run.refunded()),
                Arg.money("amount", run.amount()));
        } else if (run.failed() == 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_NOTHING_EXPIRED);
        }
        if (run.failed() > 0) {
            this.services.messenger().chat(sender, BountiesMessages.ADMIN_FAILED, Arg.number("count", run.failed()));
        }
        if (run.refunded() > 0 || run.failed() > 0) {
            this.services.audit().record(actor(sender), "bounties.expire", null,
                "refunded " + run.amount() + " in " + run.refunded() + " part(s), " + run.failed() + " failed");
        }
        return CommandSupport.OK;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
