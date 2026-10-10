package net.siftvanilla.siftcore.feature.kits;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /kits} (alias {@code /kit}): the kits dialog, {@code /kit <name>} to claim straight away, and the staff
 * subcommands give, reset and check (console friendly). Plus one command per rank perk.
 */
final class KitCommands {

    private final Services services;
    private final CommandSupport support;
    private final KitService kits;
    private final KitDialogs dialogs;
    private final PerkService perks;

    KitCommands(Services services, KitService kits, KitDialogs dialogs, PerkService perks) {
        this.services = services;
        this.support = services.commands();
        this.kits = kits;
        this.dialogs = dialogs;
        this.perks = perks;
    }

    List<SiftCommand> all() {
        List<SiftCommand> commands = new ArrayList<>();
        commands.add(new SimpleCommand("kits", List.of("kit"), "Shows your kits, or claims one with /kit <name>", KitsFeature.PERMISSION_USE,
            this::tree));
        for (Perk perk : Perk.values()) {
            commands.add(perk(perk));
        }
        return List.copyOf(commands);
    }

    // ------------------------------------------------------------------ /kits

    private LiteralArgumentBuilder<CommandSourceStack> tree(String label) {
        String use = KitsFeature.PERMISSION_USE;
        String admin = KitsFeature.PERMISSION_ADMIN;
        return Commands.literal(label)
            .requires(CommandSupport.permission(use))
            .executes(this::open)
            .then(Commands.literal("give")
                .requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player")
                    .then(anyKit("kit").executes(ctx -> withKit(ctx, kit -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> this.kits.give(ctx.getSource().getSender(), uuid, kit));
                    })))))
            .then(Commands.literal("reset")
                .requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player")
                    .executes(ctx -> {
                        this.support.known(ctx, "player").ifPresent(uuid -> this.kits.reset(ctx.getSource().getSender(), uuid, null));
                        return CommandSupport.OK;
                    })
                    .then(anyKit("kit").executes(ctx -> withKit(ctx, kit -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> this.kits.reset(ctx.getSource().getSender(), uuid, kit));
                    })))))
            .then(Commands.literal("check")
                .requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player").executes(this::check)))
            .then(ownKit("kit")
                .requires(CommandSupport.playerPermission(use))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        KitService.Refusal refusal = this.kits.claim(player, StringArgumentType.getString(ctx, "kit"));
                        if (refusal != null) {
                            this.services.messenger().send(player, refusal.key(), refusal.argArray());
                        }
                    }
                    return CommandSupport.OK;
                }));
    }

    /** A kit id argument suggesting every kit (staff). */
    private RequiredArgumentBuilder<CommandSourceStack, String> anyKit(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Kit kit : this.kits.settings().kits()) {
                if (kit.id().startsWith(remaining)) {
                    builder.suggest(kit.id());
                }
            }
            return builder.buildFuture();
        });
    }

    /** A kit id argument suggesting the kits the player may claim. */
    private RequiredArgumentBuilder<CommandSourceStack, String> ownKit(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            for (Kit kit : this.kits.settings().kits()) {
                if (kit.id().startsWith(remaining) && sender.hasPermission(kit.permission())) {
                    builder.suggest(kit.id());
                }
            }
            return builder.buildFuture();
        });
    }

    private int withKit(CommandContext<CommandSourceStack> ctx, Consumer<Kit> action) {
        String input = StringArgumentType.getString(ctx, "kit");
        Kit kit = this.kits.settings().kit(input);
        if (kit == null) {
            this.services.messenger().send(ctx.getSource().getSender(), KitsMessages.UNKNOWN, Arg.text("input", input));
            return CommandSupport.OK;
        }
        action.accept(kit);
        return CommandSupport.OK;
    }

    private int open(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (ctx.getSource().getExecutor() instanceof Player player) {
            this.dialogs.list(player, null);
        } else if (sender instanceof Player player) {
            this.dialogs.list(player, null);
        } else {
            listKits(sender);
        }
        return CommandSupport.OK;
    }

    /** Every kit with its cooldown, size and who has it (the console's /kits). */
    private void listKits(CommandSender sender) {
        var messenger = this.services.messenger();
        var lang = this.services.lang();
        List<Kit> all = this.kits.settings().kits();
        messenger.chat(sender, KitsMessages.ADMIN_LIST_HEADER, Arg.number("count", all.size()));
        for (Kit kit : all) {
            messenger.chat(sender, KitsMessages.ADMIN_LIST_LINE,
                Arg.text("name", kit.name()),
                Arg.text("id", kit.id()),
                Arg.component("cooldown", kit.cooldown().once() ? lang.get(KitsMessages.KIT_ONCE)
                    : lang.get(KitsMessages.KIT_EVERY, Arg.time("time", kit.cooldown().every()))),
                Arg.number("items", kit.items().size()),
                Arg.component("access", kit.everyone() ? lang.get(KitsMessages.ADMIN_EVERYONE)
                    : lang.get(KitsMessages.ADMIN_NODE, Arg.text("node", kit.permission()))));
        }
    }

    /** Every kit's status for a player; for online players, kits they lack the permission for read "locked". */
    private int check(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        Player online = Bukkit.getPlayer(uuid);
        var messenger = this.services.messenger();
        messenger.chat(sender, KitsMessages.ADMIN_CHECK_HEADER, Arg.text("player", this.services.directory().name(uuid)));
        for (Kit kit : this.kits.settings().kits()) {
            boolean permitted = online == null || online.hasPermission(kit.permission());
            messenger.chat(sender, KitsMessages.ADMIN_CHECK_LINE, Arg.text("name", kit.name()), Arg.text("id", kit.id()),
                Arg.component("status", this.kits.text().status(this.kits.claims().status(uuid, kit), permitted)));
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ perks

    private SiftCommand perk(Perk perk) {
        return new SimpleCommand(perk.id(), perk.aliases(), perk.description(), perk.node(), label -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(label);
            if (perk == Perk.EC) {
                root.requires(source -> source.getSender() instanceof Player
                    && (source.getSender().hasPermission(perk.node()) || source.getSender().hasPermission(Perk.EC_OTHERS)));
                root.then(CommandSupport.onlinePlayer("player")
                    .requires(CommandSupport.playerPermission(Perk.EC_OTHERS))
                    .executes(ctx -> {
                        Player viewer = this.support.player(ctx);
                        Player target = viewer == null ? null : this.support.online(ctx, "player");
                        if (target != null) {
                            this.perks.peek(viewer, target);
                        }
                        return CommandSupport.OK;
                    }));
            } else {
                root.requires(CommandSupport.playerPermission(perk.node()));
            }
            return root.executes(ctx -> {
                Player player = this.support.player(ctx);
                if (player != null) {
                    this.perks.use(player, perk);
                }
                return CommandSupport.OK;
            });
        });
    }
}
