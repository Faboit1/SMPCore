package net.siftvanilla.siftcore.feature.spawners;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /spawners} for players (the list dialog) and its staff subcommands, which all work from the console:
 * {@code give <player> <mob> [amount]}, {@code list <player>}, {@code cycle} and {@code info}.
 */
final class SpawnersCommands {

    static final String COMMAND = "siftcore.command.spawners";
    static final String ADMIN = "siftcore.admin.spawners";
    static final int MAX_GIVE = 6400;

    private final Services services;
    private final SpawnerService service;
    private final SpawnerDialogs dialogs;
    private final LootCycle cycle;
    private final WriteBehind writeBehind;

    SpawnersCommands(Services services, SpawnerService service, SpawnerDialogs dialogs, LootCycle cycle, WriteBehind writeBehind) {
        this.services = services;
        this.service = service;
        this.dialogs = dialogs;
        this.cycle = cycle;
        this.writeBehind = writeBehind;
    }

    List<SiftCommand> all() {
        CommandSupport support = this.services.commands();
        return List.of(new SimpleCommand("spawners", List.of("spawner"), "Lists your spawners and their loot", COMMAND,
            label -> Commands.literal(label)
                .requires(source -> source.getSender().hasPermission(COMMAND) || source.getSender().hasPermission(ADMIN))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null && player.hasPermission(COMMAND) && support.cooldown(player, "spawners")) {
                        this.dialogs.openList(player, 1, null);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("give").requires(CommandSupport.permission(ADMIN))
                    .then(support.knownPlayer("player")
                        .then(Commands.argument("mob", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                String remaining = builder.getRemainingLowerCase();
                                for (String mob : this.service.items().mobs()) {
                                    if (mob.startsWith(remaining)) {
                                        builder.suggest(mob);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> give(ctx, 1))
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_GIVE))
                                .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(Commands.literal("list").requires(CommandSupport.permission(ADMIN))
                    .then(support.knownPlayer("player").executes(this::list)))
                .then(Commands.literal("cycle").requires(CommandSupport.permission(ADMIN)).executes(ctx -> {
                    int chunks = this.cycle.runAllNow();
                    messenger().chat(ctx.getSource().getSender(), SpawnersMessages.ADMIN_CYCLE, Arg.number("chunks", chunks));
                    return CommandSupport.OK;
                }))
                .then(Commands.literal("info").requires(CommandSupport.permission(ADMIN)).executes(this::info))));
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private int give(CommandContext<CommandSourceStack> ctx, int amount) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.services.commands().known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String mob = StringArgumentType.getString(ctx, "mob").toLowerCase(Locale.ROOT);
        if (mob.startsWith("minecraft:")) {
            mob = mob.substring("minecraft:".length());
        }
        if (!this.service.items().mobs().contains(mob)) {
            messenger().chat(sender, SpawnersMessages.UNKNOWN_MOB, Arg.text("mob", mob),
                Arg.text("mobs", String.join(", ", this.service.items().mobs())));
            return CommandSupport.OK;
        }
        this.service.give(sender, target.get(), mob, amount);
        return CommandSupport.OK;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.services.commands().known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.service.ownerName(target.get());
        List<ManagedSpawner> owned = this.service.registry().ownedBy(target.get());
        if (owned.isEmpty()) {
            messenger().chat(sender, SpawnersMessages.ADMIN_LIST_EMPTY, Arg.text("name", name));
            return CommandSupport.OK;
        }
        long stacked = 0;
        for (ManagedSpawner spawner : owned) {
            stacked += spawner.stack();
        }
        messenger().chat(sender, SpawnersMessages.ADMIN_LIST_HEADER, Arg.text("name", name), Arg.number("count", owned.size()),
            Arg.number("stacked", stacked));
        for (ManagedSpawner spawner : owned) {
            ManagedSpawner.State state = this.service.state(spawner);
            messenger().chat(sender, SpawnersMessages.ADMIN_LIST_LINE,
                Arg.number("id", spawner.id),
                Arg.text("name", this.service.name(spawner.mob)),
                Arg.number("stack", state.stack()),
                Arg.text("location", spawner.pos.coordinates()),
                Arg.text("world", spawner.pos.world()),
                Arg.number("used", state.used()),
                Arg.number("capacity", this.service.capacity(spawner, state.stack())),
                Arg.number("xp", state.xp()));
        }
        return CommandSupport.OK;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        SpawnerRegistry registry = this.service.registry();
        long stacked = 0;
        long items = 0;
        long xp = 0;
        for (ManagedSpawner spawner : registry.all()) {
            stacked += spawner.stack();
            items += spawner.storage.used();
            xp += spawner.xp();
        }
        messenger().chat(ctx.getSource().getSender(), SpawnersMessages.ADMIN_INFO,
            Arg.number("spawners", registry.size()),
            Arg.number("stacked", stacked),
            Arg.number("owners", registry.owners()),
            Arg.number("chunks", registry.chunks().size()),
            Arg.number("loaded", registry.loadedCount()),
            Arg.number("dirty", this.writeBehind.pending(registry.all())),
            Arg.number("items", items),
            Arg.number("xp", xp));
        return CommandSupport.OK;
    }
}
