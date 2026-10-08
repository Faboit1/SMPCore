package net.siftvanilla.siftcore.feature.stats;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /stats, /top (/leaderboard), /playtime, and the staff tools under /sift stats. */
final class StatsCommands {

    static final String STATS = "siftcore.command.stats";
    static final String STATS_OTHERS = "siftcore.command.stats.others";
    static final String PLAYTIME = "siftcore.command.playtime";
    static final String PLAYTIME_OTHERS = "siftcore.command.playtime.others";
    static final String ADMIN = "siftcore.admin.stats";

    private final Services services;
    private final CommandSupport support;
    private final StatsStore store;
    private final Leaderboards boards;
    private final StatsViews views;

    StatsCommands(Services services, StatsStore store, Leaderboards boards, StatsViews views) {
        this.services = services;
        this.support = services.commands();
        this.store = store;
        this.boards = boards;
        this.views = views;
    }

    List<SiftCommand> all() {
        return List.of(stats(), top(), playtime());
    }

    // ------------------------------------------------------------------ /stats

    private SiftCommand stats() {
        return new SimpleCommand("stats", List.of(), "Shows your stats or another player's", STATS,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(STATS))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.views.openStats(player, player.getUniqueId(), null);
                    }
                    return CommandSupport.OK;
                })
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.permission(STATS_OTHERS))
                    .executes(ctx -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        CommandSender sender = this.support.sender(ctx);
                        target.ifPresent(uuid -> {
                            if (sender instanceof Player player) {
                                this.views.openStats(player, uuid, null);
                            } else {
                                this.views.printStats(sender, uuid);
                            }
                        });
                        return CommandSupport.OK;
                    })));
    }

    // ------------------------------------------------------------------ /top

    private SiftCommand top() {
        return new SimpleCommand("top", List.of("leaderboard", "leaderboards"), "Shows the leaderboards", StatsViews.TOP_PERMISSION,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(StatsViews.TOP_PERMISSION))
                .executes(ctx -> {
                    CommandSender sender = this.support.sender(ctx);
                    if (sender instanceof Player player) {
                        this.views.openPicker(player, null);
                    } else {
                        this.services.messenger().chat(sender, StatsMessages.TOP_LIST, Arg.text("boards", StatsViews.boardIds()));
                    }
                    return CommandSupport.OK;
                })
                .then(boardArgument()
                    .executes(ctx -> showBoard(ctx, 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> showBoard(ctx, IntegerArgumentType.getInteger(ctx, "page"))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> boardArgument() {
        return Commands.argument("board", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Board board : Board.values()) {
                if (board.id().startsWith(remaining)) {
                    builder.suggest(board.id());
                }
            }
            return builder.buildFuture();
        });
    }

    private int showBoard(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = this.support.sender(ctx);
        String input = StringArgumentType.getString(ctx, "board");
        Optional<Board> board = Board.byId(input);
        if (board.isEmpty()) {
            this.services.messenger().send(sender, StatsMessages.TOP_UNKNOWN, Arg.text("input", input),
                Arg.text("boards", StatsViews.boardIds()));
            return CommandSupport.OK;
        }
        if (sender instanceof Player player) {
            this.views.openBoard(player, board.get(), page, null);
        } else {
            this.views.printBoard(sender, board.get(), page);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /playtime

    private SiftCommand playtime() {
        return new SimpleCommand("playtime", List.of(), "Shows how long you have played", PLAYTIME,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(PLAYTIME))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.views.sendPlaytime(player, player.getUniqueId());
                    }
                    return CommandSupport.OK;
                })
                .then(this.support.knownPlayer("player")
                    .requires(CommandSupport.permission(PLAYTIME_OTHERS))
                    .executes(ctx -> {
                        Optional<UUID> target = this.support.known(ctx, "player");
                        target.ifPresent(uuid -> this.views.sendPlaytime(this.support.sender(ctx), uuid));
                        return CommandSupport.OK;
                    })));
    }

    // ------------------------------------------------------------------ /sift stats

    /** The staff subcommand: {@code /sift stats reset|set|add|refresh}. */
    AdminFeature.AdminCommandPart adminPart() {
        return () -> Commands.literal("stats")
            .requires(CommandSupport.permission(ADMIN))
            .then(Commands.literal("reset")
                .then(this.support.knownPlayer("player").executes(this::reset)))
            .then(Commands.literal("set")
                .then(this.support.knownPlayer("player")
                    .then(counterArgument()
                        .then(Commands.argument("value", StringArgumentType.word()).executes(ctx -> change(ctx, true))))))
            .then(Commands.literal("add")
                .then(this.support.knownPlayer("player")
                    .then(counterArgument()
                        .then(Commands.argument("value", StringArgumentType.word()).executes(ctx -> change(ctx, false))))))
            .then(Commands.literal("refresh").executes(this::refresh));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> counterArgument() {
        return Commands.argument("stat", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Counter counter : Counter.values()) {
                if (counter.id().startsWith(remaining)) {
                    builder.suggest(counter.id());
                }
            }
            return builder.buildFuture();
        });
    }

    private int reset(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = this.support.sender(ctx);
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        this.services.audit().record(actor(sender), "stats.reset", uuid.toString(), null);
        reply(sender, name, this.store.reset(uuid), () ->
            this.services.messenger().chat(sender, StatsMessages.ADMIN_RESET, Arg.text("name", name)));
        return CommandSupport.OK;
    }

    private int change(CommandContext<CommandSourceStack> ctx, boolean set) {
        CommandSender sender = this.support.sender(ctx);
        String statInput = StringArgumentType.getString(ctx, "stat");
        Optional<Counter> counter = Counter.byId(statInput);
        if (counter.isEmpty()) {
            this.services.messenger().chat(sender, StatsMessages.ADMIN_UNKNOWN_STAT, Arg.text("input", statInput),
                Arg.text("stats", counterIds()));
            return CommandSupport.OK;
        }
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String input = StringArgumentType.getString(ctx, "value");
        OptionalLong value = parse(counter.get(), input, set);
        if (value.isEmpty()) {
            this.services.messenger().chat(sender, StatsMessages.ADMIN_INVALID_VALUE, Arg.text("input", input),
                Arg.component("stat", this.services.lang().get(StatsMessages.name(counter.get()))));
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        long amount = value.getAsLong();
        Arg stat = Arg.component("stat", this.services.lang().get(StatsMessages.name(counter.get())));
        Arg shown = StatsViews.counterValue(counter.get(), "value", amount);
        this.services.audit().record(actor(sender), set ? "stats.set" : "stats.add", uuid.toString(), counter.get().id() + " " + amount);
        CompletableFuture<Void> saved = set ? this.store.set(uuid, counter.get(), amount) : this.store.give(uuid, counter.get(), amount);
        reply(sender, name, saved, () -> this.services.messenger().chat(sender, set ? StatsMessages.ADMIN_SET : StatsMessages.ADMIN_ADDED,
            Arg.text("name", name), stat, shown));
        return CommandSupport.OK;
    }

    /** Parses a staff value: a duration for playtime, an amount for money, a whole number otherwise. */
    private OptionalLong parse(Counter counter, String input, boolean allowZero) {
        long value;
        switch (counter) {
            case PLAYTIME -> {
                try {
                    value = Durations.parse(input).toSeconds();
                } catch (IllegalArgumentException | ArithmeticException e) {
                    return OptionalLong.empty();
                }
            }
            case EARNED -> {
                MoneyFormat.ParseResult parsed = this.services.money().get().parse(input, allowZero);
                if (!parsed.ok()) {
                    return OptionalLong.empty();
                }
                value = parsed.amount();
            }
            default -> {
                String digits = input.replace(",", "").replace("_", "");
                if (!digits.matches("\\d{1,18}")) {
                    return OptionalLong.empty();
                }
                value = Long.parseLong(digits);
            }
        }
        return value < 0 || (value == 0 && !allowZero) || value > counter.max() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private void reply(CommandSender sender, String name, CompletableFuture<Void> saved, Runnable success) {
        saved.whenComplete((ignored, error) -> {
            if (error == null) {
                success.run();
            } else {
                this.services.messenger().chat(sender, StatsMessages.ADMIN_SAVE_FAILED, Arg.text("name", name));
            }
        });
    }

    private int refresh(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = this.support.sender(ctx);
        if (this.boards.refreshing()) {
            this.services.messenger().chat(sender, StatsMessages.ADMIN_REFRESH_BUSY);
            return CommandSupport.OK;
        }
        long start = System.nanoTime();
        this.services.audit().record(actor(sender), "stats.refresh", null, null);
        this.boards.refresh().whenComplete((rebuilt, error) -> {
            if (error == null && Boolean.TRUE.equals(rebuilt)) {
                this.services.messenger().chat(sender, StatsMessages.ADMIN_REFRESHED,
                    Arg.time("time", Duration.ofNanos(System.nanoTime() - start)));
            } else if (error == null && this.boards.refreshing()) {
                this.services.messenger().chat(sender, StatsMessages.ADMIN_REFRESH_BUSY);
            } else {
                this.services.messenger().chat(sender, StatsMessages.ADMIN_REFRESH_FAILED);
            }
        });
        return CommandSupport.OK;
    }

    private static String counterIds() {
        StringJoiner joiner = new StringJoiner(", ");
        for (Counter counter : Counter.values()) {
            joiner.add(counter.id());
        }
        return joiner.toString();
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
