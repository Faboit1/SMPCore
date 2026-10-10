package net.siftvanilla.siftcore.feature.crates;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /crates} (alias {@code /crate}, {@code /keys}): the crates dialog, opening and previewing, and staff tools
 * that also work from the console (give, take, check, log, info, crate blocks). {@code /keyall}: when the next keyall
 * is, and for staff a keyall right now or moving the next one.
 */
final class CrateCommands {

    private static final int LOG_PAGE = 10;
    private static final int REACH = 6;

    private final Services services;
    private final CommandSupport support;
    private final Setting<CratesSettings> settings;
    private final KeyService keys;
    private final CrateDialogs dialogs;
    private final CrateOpener opener;
    private final CrateBlocks blocks;
    private final Keyall keyall;
    private final CrateLog log;
    private final RewardItems items;
    private final CrateText text;
    private final WorthLookup worth;

    CrateCommands(Services services, Setting<CratesSettings> settings, KeyService keys, CrateDialogs dialogs, CrateOpener opener,
                  CrateBlocks blocks, Keyall keyall, CrateLog log, RewardItems items, CrateText text, WorthLookup worth) {
        this.services = services;
        this.support = services.commands();
        this.settings = settings;
        this.keys = keys;
        this.dialogs = dialogs;
        this.opener = opener;
        this.blocks = blocks;
        this.keyall = keyall;
        this.log = log;
        this.items = items;
        this.text = text;
        this.worth = worth;
    }

    List<SiftCommand> all() {
        return List.of(
            new SimpleCommand("crates", List.of("crate", "keys"), "Your crate keys and crates", CratesFeature.PERMISSION_USE, this::crates),
            new SimpleCommand("keyall", List.of(), "When the next keyall is", CratesFeature.PERMISSION_KEYALL, this::keyallTree));
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> crateArgument() {
        return Commands.argument("crate", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Crate crate : this.settings.get().crates()) {
                if (crate.id().startsWith(remaining)) {
                    builder.suggest(crate.id());
                }
            }
            return builder.buildFuture();
        });
    }

    /** The crate named by the argument, or null after telling the sender. */
    private Crate crate(CommandContext<CommandSourceStack> ctx) {
        String input = StringArgumentType.getString(ctx, "crate").toLowerCase(Locale.ROOT);
        Crate crate = this.settings.get().crate(input);
        if (crate == null) {
            messenger().send(ctx.getSource().getSender(), CratesMessages.UNKNOWN_CRATE, Arg.text("input", input));
        }
        return crate;
    }

    private int asPlayer(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null && this.support.cooldown(player, "crates")) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /crates

    private LiteralArgumentBuilder<CommandSourceStack> crates(String label) {
        String use = CratesFeature.PERMISSION_USE;
        String admin = CratesFeature.PERMISSION_ADMIN;
        return Commands.literal(label)
            .requires(CommandSupport.permission(use))
            .executes(ctx -> {
                if (ctx.getSource().getSender() instanceof Player) {
                    return asPlayer(ctx, player -> this.dialogs.list(player, null));
                }
                listInChat(ctx.getSource().getSender());
                return CommandSupport.OK;
            })
            .then(Commands.literal("open").requires(CommandSupport.playerPermission(use))
                .then(crateArgument()
                    .executes(ctx -> asPlayer(ctx, player -> openCommand(ctx, player, 1)))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, CratesSettings.MAX_BULK_OPEN))
                        .executes(ctx -> asPlayer(ctx, player -> openCommand(ctx, player, IntegerArgumentType.getInteger(ctx, "amount")))))))
            .then(Commands.literal("preview").requires(CommandSupport.playerPermission(use))
                .then(crateArgument().executes(ctx -> asPlayer(ctx, player -> {
                    Crate crate = crate(ctx);
                    if (crate != null) {
                        this.dialogs.preview(player, crate.id(), null, null);
                    }
                }))))
            .then(Commands.literal("give").requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player")
                    .then(crateArgument()
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000))
                            .executes(ctx -> give(ctx, null))
                            .then(Commands.argument("ref", StringArgumentType.word())
                                .executes(ctx -> give(ctx, StringArgumentType.getString(ctx, "ref"))))))))
            .then(Commands.literal("take").requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player")
                    .then(crateArgument()
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000)).executes(this::take)))))
            .then(Commands.literal("check").requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player").executes(this::check)))
            .then(Commands.literal("log").requires(CommandSupport.permission(admin))
                .then(this.support.knownPlayer("player")
                    .executes(ctx -> log(ctx, 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
                        .executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
            .then(Commands.literal("info").requires(CommandSupport.permission(admin))
                .then(crateArgument().executes(this::info)))
            .then(Commands.literal("block").requires(CommandSupport.permission(admin))
                .then(Commands.literal("add")
                    .then(crateArgument()
                        .executes(ctx -> addLooking(ctx))
                        .then(position(ctx -> {
                            Crate crate = crate(ctx);
                            BlockKey block = position(ctx);
                            if (crate != null && block != null) {
                                add(ctx.getSource().getSender(), block, crate);
                            }
                            return CommandSupport.OK;
                        }))))
                .then(Commands.literal("remove")
                    .executes(this::removeLooking)
                    .then(position(ctx -> {
                        BlockKey block = position(ctx);
                        if (block != null) {
                            remove(ctx.getSource().getSender(), block);
                        }
                        return CommandSupport.OK;
                    })))
                .then(Commands.literal("list").executes(ctx -> blockList(ctx.getSource().getSender()))));
    }

    /** {@code /crates open <crate> [amount]}: one key, or several in a row with one receipt. Player's thread. */
    private void openCommand(CommandContext<CommandSourceStack> ctx, Player player, int amount) {
        Crate crate = crate(ctx);
        if (crate == null) {
            return;
        }
        if (amount == 1) {
            this.opener.open(player, crate.id(), true, result -> {
                if (result instanceof CrateOpener.Refused refused) {
                    this.opener.report(player, refused);
                }
            });
            return;
        }
        int max = Math.max(1, this.settings.get().bulkOpen());
        if (amount > max) {
            messenger().send(player, CratesMessages.BULK_LIMIT, Arg.number("max", max));
            return;
        }
        this.opener.openMany(player, crate.id(), amount, true, batch -> this.opener.receipt(player, batch));
    }

    private void listInChat(CommandSender sender) {
        List<Crate> crates = this.settings.get().crates();
        messenger().chat(sender, CratesMessages.CRATES_HEADER, Arg.number("count", crates.size()));
        for (Crate crate : crates) {
            messenger().chat(sender, CratesMessages.CRATES_LINE, Arg.text("id", crate.id()), Arg.text("name", crate.name()),
                Arg.number("rewards", this.items.available(crate).size()), Arg.number("keys", this.keys.book().total(crate.id())));
        }
    }

    private int give(CommandContext<CommandSourceStack> ctx, String ref) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        Crate crate = target.isPresent() ? crate(ctx) : null;
        if (target.isEmpty() || crate == null) {
            return CommandSupport.OK;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        UUID uuid = target.get();
        String actor = actor(sender);
        TransactionResult result = this.keys.give(uuid, crate.id(), amount, actor, ref);
        String name = this.services.directory().name(uuid);
        Component given = this.text.keys(amount, crate);
        if (!result.success()) {
            reportFailure(sender, result, ref, uuid, crate);
            return CommandSupport.OK;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                messenger().chat(sender, CratesMessages.FAILED, Arg.text("reason", "storage"));
                return;
            }
            messenger().chat(sender, CratesMessages.GIVEN, Arg.text("player", name), Arg.component("keys", given),
                Arg.number("total", this.keys.keys(uuid, crate.id())));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && !online.equals(sender)) {
                messenger().send(online, CratesMessages.KEYS_RECEIVED, Arg.component("keys", given));
            }
            this.services.audit().record(actor, "crates.give", uuid.toString(),
                "crate=" + crate.id() + " amount=" + amount + (ref == null ? "" : " ref=" + ref));
        });
        return CommandSupport.OK;
    }

    private void reportFailure(CommandSender sender, TransactionResult result, String ref, UUID player, Crate crate) {
        String reason = result.reason() == null ? result.status().name().toLowerCase(Locale.ROOT) : result.reason();
        switch (reason) {
            case KeyService.DUPLICATE -> messenger().chat(sender, CratesMessages.DUPLICATE, Arg.text("ref", ref == null ? "" : ref));
            case KeyService.NOT_ENOUGH -> messenger().chat(sender, CratesMessages.NOT_ENOUGH,
                Arg.text("player", this.services.directory().name(player)),
                Arg.component("keys", this.text.keys(this.keys.keys(player, crate.id()), crate)));
            default -> messenger().chat(sender, CratesMessages.FAILED, Arg.text("reason", reason.replace('_', ' ')));
        }
    }

    private int take(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        Crate crate = target.isPresent() ? crate(ctx) : null;
        if (target.isEmpty() || crate == null) {
            return CommandSupport.OK;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        UUID uuid = target.get();
        String actor = actor(sender);
        String name = this.services.directory().name(uuid);
        TransactionResult result = this.keys.take(uuid, crate.id(), amount, actor);
        if (!result.success()) {
            reportFailure(sender, result, null, uuid, crate);
            return CommandSupport.OK;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                messenger().chat(sender, CratesMessages.FAILED, Arg.text("reason", "storage"));
                return;
            }
            messenger().chat(sender, CratesMessages.TAKEN, Arg.text("player", name),
                Arg.component("keys", this.text.keys(amount, crate)), Arg.number("total", this.keys.keys(uuid, crate.id())));
            this.services.audit().record(actor, "crates.take", uuid.toString(), "crate=" + crate.id() + " amount=" + amount);
        });
        return CommandSupport.OK;
    }

    private int check(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.services.directory().name(target.get());
        Map<String, Integer> owned = this.keys.keysOf(target.get());
        if (owned.isEmpty()) {
            messenger().chat(sender, CratesMessages.CHECK_EMPTY, Arg.text("player", name));
            return CommandSupport.OK;
        }
        messenger().chat(sender, CratesMessages.CHECK_HEADER, Arg.text("player", name));
        CratesSettings settings = this.settings.get();
        List<String> order = new ArrayList<>(settings.crateIds().size());
        for (Crate crate : settings.crates()) {
            order.add(crate.id());
        }
        for (String id : owned.keySet()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        for (String id : order) {
            Integer count = owned.get(id);
            if (count != null && count > 0) {
                Crate crate = settings.crate(id);
                messenger().chat(sender, CratesMessages.CHECK_LINE, Arg.text("name", crate == null ? id : crate.name()),
                    Arg.number("count", count));
            }
        }
        return CommandSupport.OK;
    }

    private int log(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        CompletableFuture<List<CrateLog.Entry>> rows = this.log.recent(uuid, LOG_PAGE, (page - 1) * LOG_PAGE);
        this.log.count(uuid).thenCombine(rows, (count, entries) -> {
            if (entries.isEmpty()) {
                messenger().chat(sender, CratesMessages.LOG_EMPTY, Arg.text("player", name));
                return null;
            }
            messenger().chat(sender, CratesMessages.LOG_HEADER, Arg.text("player", name), Arg.number("count", count));
            long now = System.currentTimeMillis();
            CratesSettings settings = this.settings.get();
            for (CrateLog.Entry entry : entries) {
                Crate crate = settings.crate(entry.crate());
                String detail = entry.detail() == null ? entry.reward() : entry.detail();
                int cut = detail.lastIndexOf(" | ");
                messenger().chat(sender, CratesMessages.LOG_LINE, Arg.time("ago", Duration.ofMillis(Math.max(0, now - entry.timestamp()))),
                    Arg.text("name", crate == null ? entry.crate() : crate.name()), Arg.text("reward", cut < 0 ? detail : detail.substring(0, cut)));
            }
            return null;
        }).exceptionally(error -> {
            messenger().chat(sender, CoreMessages.ACTION_FAILED);
            return null;
        });
        return CommandSupport.OK;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Crate crate = crate(ctx);
        if (crate == null) {
            return CommandSupport.OK;
        }
        CratesSettings settings = this.settings.get();
        List<Reward> available = this.items.available(crate);
        messenger().chat(sender, CratesMessages.INFO_HEADER, Arg.text("name", crate.name()), Arg.number("count", available.size()));
        if (!available.isEmpty()) {
            double[] weights = new double[available.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = available.get(i).weight();
            }
            long[] chances = Chances.hundredths(weights);
            for (int i = 0; i < available.size(); i++) {
                Reward reward = available.get(i);
                messenger().chat(sender, CratesMessages.INFO_LINE, Arg.text("chance", Chances.format(chances[i])),
                    Arg.component("reward", this.text.reward(reward)), Arg.component("rarity", CrateText.rarity(settings.rarity(reward.rarity()))));
            }
        }
        ExpectedValue value = ExpectedValue.of(available, reward -> this.items.build(reward).map(this.worth::price).orElse(0L));
        messenger().chat(sender, CratesMessages.INFO_VALUE, Arg.money("money", Math.round(value.money())),
            Arg.decimal("shards", value.shards()), Arg.money("items", Math.round(value.itemWorth())));
        value.keys().forEach((id, amount) -> {
            Crate target = settings.crate(id);
            messenger().chat(sender, CratesMessages.INFO_KEYS, Arg.decimal("amount", amount), Arg.text("name", target == null ? id : target.name()));
        });
        if (value.commandShare() > 0) {
            messenger().chat(sender, CratesMessages.INFO_COMMANDS,
                Arg.text("chance", Chances.format(Math.round(value.commandShare() * Chances.WHOLE))));
        }
        int leftOut = crate.rewards().size() - available.size();
        if (leftOut > 0) {
            messenger().chat(sender, CratesMessages.INFO_LEFT_OUT, Arg.number("count", leftOut));
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ crate blocks

    /** {@code <world> <x> <y> <z>}, running {@code command} once all four are given. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> position(Command<CommandSourceStack> command) {
        return Commands.argument("world", StringArgumentType.word()).suggests((context, builder) -> {
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(world.getName());
                }
            }
            return builder.buildFuture();
        }).then(Commands.argument("x", IntegerArgumentType.integer())
            .then(Commands.argument("y", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(command))));
    }

    /** The typed position, or null after telling the sender the world is unknown. */
    private BlockKey position(CommandContext<CommandSourceStack> ctx) {
        String world = StringArgumentType.getString(ctx, "world");
        if (Bukkit.getWorld(world) == null) {
            messenger().send(ctx.getSource().getSender(), CratesMessages.BLOCK_NO_WORLD, Arg.text("input", world));
            return null;
        }
        return new BlockKey(world, IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"),
            IntegerArgumentType.getInteger(ctx, "z"));
    }

    /** The block the player looks at, up to {@link #REACH} blocks away, or null after telling them. Player's thread. */
    private BlockKey looking(Player player) {
        Block block = player.getTargetBlockExact(REACH);
        if (block == null || block.getType().isAir() || !this.services.scheduler().owns(block.getLocation())) {
            messenger().send(player, CratesMessages.BLOCK_LOOK);
            return null;
        }
        return CrateBlocks.key(block);
    }

    private int addLooking(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        Crate crate = player == null ? null : crate(ctx);
        if (crate != null) {
            BlockKey block = looking(player);
            if (block != null) {
                add(player, block, crate);
            }
        }
        return CommandSupport.OK;
    }

    private int removeLooking(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player != null) {
            BlockKey block = looking(player);
            if (block != null) {
                remove(player, block);
            }
        }
        return CommandSupport.OK;
    }

    private Arg positionArg(BlockKey block) {
        return Arg.text("position", block.toString());
    }

    private void add(CommandSender sender, BlockKey block, Crate crate) {
        CrateBlocks.Entry existing = this.blocks.at(block);
        if (existing != null) {
            Crate owner = this.settings.get().crate(existing.crate());
            messenger().send(sender, CratesMessages.BLOCK_TAKEN, Arg.text("name", owner == null ? existing.crate() : owner.name()));
            return;
        }
        this.blocks.add(block, crate.id(), actor(sender)).thenAccept(change -> {
            switch (change) {
                case DONE -> messenger().chat(sender, CratesMessages.BLOCK_ADDED, positionArg(block), Arg.text("name", crate.name()));
                case BUSY -> messenger().send(sender, CratesMessages.BLOCK_BUSY);
                case TAKEN -> messenger().send(sender, CratesMessages.BLOCK_TAKEN, Arg.text("name", crate.name()));
                default -> messenger().send(sender, CratesMessages.BLOCK_FAILED);
            }
        });
    }

    private void remove(CommandSender sender, BlockKey block) {
        this.blocks.remove(block, actor(sender)).thenAccept(change -> {
            switch (change) {
                case DONE -> messenger().chat(sender, CratesMessages.BLOCK_REMOVED, positionArg(block));
                case NOT_CRATE -> messenger().send(sender, CratesMessages.BLOCK_NOT_CRATE);
                case IN_FILE -> messenger().chat(sender, CratesMessages.BLOCK_IN_FILE, positionArg(block));
                case BUSY -> messenger().send(sender, CratesMessages.BLOCK_BUSY);
                default -> messenger().send(sender, CratesMessages.BLOCK_FAILED);
            }
        });
    }

    private int blockList(CommandSender sender) {
        Map<BlockKey, CrateBlocks.Entry> all = this.blocks.all();
        if (all.isEmpty()) {
            messenger().chat(sender, CratesMessages.BLOCK_LIST_EMPTY);
            return CommandSupport.OK;
        }
        messenger().chat(sender, CratesMessages.BLOCK_LIST_HEADER, Arg.number("count", all.size()));
        var lang = this.services.lang();
        all.forEach((block, entry) -> {
            Crate crate = this.settings.get().crate(entry.crate());
            messenger().chat(sender, CratesMessages.BLOCK_LIST_LINE, Arg.text("name", crate == null ? entry.crate() : crate.name()),
                positionArg(block), Arg.component("source",
                    lang.get(entry.fromFile() ? CratesMessages.BLOCK_SOURCE_FILE : CratesMessages.BLOCK_SOURCE_PLACED)));
        });
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /keyall

    private LiteralArgumentBuilder<CommandSourceStack> keyallTree(String label) {
        String admin = CratesFeature.PERMISSION_ADMIN;
        return Commands.literal(label)
            .requires(CommandSupport.permission(CratesFeature.PERMISSION_KEYALL))
            .executes(ctx -> keyallInfo(ctx.getSource().getSender()))
            .then(Commands.literal("in").requires(CommandSupport.permission(admin))
                .then(Commands.argument("time", StringArgumentType.word()).executes(this::keyallIn)))
            .then(crateArgument().requires(CommandSupport.permission(admin))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, CratesSettings.MAX_KEY_REWARD))
                    .executes(this::keyallNow)));
    }

    private int keyallInfo(CommandSender sender) {
        Component reward = this.keyall.reward();
        if (!this.keyall.enabled() || reward == null) {
            messenger().chat(sender, CratesMessages.KEYALL_OFF);
            return CommandSupport.OK;
        }
        messenger().chat(sender, CratesMessages.KEYALL_INFO, Arg.time("time", this.keyall.remaining()), Arg.component("keys", reward));
        return CommandSupport.OK;
    }

    private int keyallIn(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String input = StringArgumentType.getString(ctx, "time");
        Duration delay;
        try {
            delay = Durations.parse(input);
        } catch (IllegalArgumentException | ArithmeticException e) {
            delay = Duration.ZERO;
        }
        if (delay.compareTo(Duration.ofSeconds(10)) < 0 || delay.compareTo(Duration.ofDays(7)) > 0) {
            messenger().send(sender, CratesMessages.KEYALL_BAD_TIME, Arg.text("input", input));
            return CommandSupport.OK;
        }
        if (!this.keyall.enabled()) {
            messenger().chat(sender, CratesMessages.KEYALL_OFF);
            return CommandSupport.OK;
        }
        this.keyall.schedule(delay);
        messenger().chat(sender, CratesMessages.KEYALL_SET, Arg.time("time", delay));
        this.services.audit().record(actor(sender), "crates.keyall.schedule", null, "in=" + Durations.format(delay));
        return CommandSupport.OK;
    }

    private int keyallNow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Crate crate = crate(ctx);
        if (crate == null) {
            return CommandSupport.OK;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Keyall.Run run = this.keyall.run(crate.id(), amount, false, actor(sender), Keyall.manualRunId());
        if (run.cancelled()) {
            messenger().chat(sender, CratesMessages.KEYALL_STOPPED);
        } else if (run.given() == 0) {
            messenger().chat(sender, CratesMessages.KEYALL_NOBODY);
        } else {
            messenger().chat(sender, CratesMessages.KEYALL_RAN, Arg.number("count", run.given()),
                Arg.component("keys", this.text.keys(amount, crate)));
        }
        return CommandSupport.OK;
    }
}
