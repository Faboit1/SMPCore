package net.siftvanilla.siftcore.feature.spawn;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** /spawn [player] and /setspawn [world x y z [yaw pitch]]. */
final class SpawnCommands {

    private static final double MAX_COORDINATE = 29_999_984;

    private final Services services;
    private final CommandSupport support;
    private final SpawnFeature feature;

    SpawnCommands(Services services, SpawnFeature feature) {
        this.services = services;
        this.support = services.commands();
        this.feature = feature;
    }

    List<SiftCommand> all() {
        return List.of(spawn(), setSpawn());
    }

    private SiftCommand spawn() {
        return new SimpleCommand("spawn", List.of(), "Takes you back to spawn", SpawnFeature.COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(SpawnFeature.COMMAND))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.feature.sendToSpawn(player);
                    }
                    return CommandSupport.OK;
                })
                .then(CommandSupport.onlinePlayer("player")
                    .requires(CommandSupport.permission(SpawnFeature.ADMIN_OTHERS))
                    .executes(this::sendOther)));
    }

    private int sendOther(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = this.support.online(ctx, "player");
        if (target == null) {
            return CommandSupport.OK;
        }
        Location spawn = this.feature.location();
        if (spawn == null) {
            this.services.messenger().send(sender, SpawnMessages.NOT_AVAILABLE);
            return CommandSupport.OK;
        }
        target.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((ok, error) -> {
            if (error == null && Boolean.TRUE.equals(ok)) {
                this.services.messenger().chat(sender, SpawnMessages.SENT, Arg.text("name", target.getName()));
                if (!target.equals(sender)) {
                    this.services.messenger().send(target, SpawnMessages.SENT_BY_STAFF);
                }
            } else {
                this.services.messenger().send(sender, TeleportMessages.FAILED);
            }
        });
        this.services.audit().record(actor(sender), "spawn.send", target.getUniqueId().toString(), null);
        return CommandSupport.OK;
    }

    private SiftCommand setSpawn() {
        return new SimpleCommand("setspawn", List.of(), "Sets the server spawn", SpawnFeature.ADMIN_SET,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(SpawnFeature.ADMIN_SET))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        set(player, SpawnPoint.of(player.getLocation()));
                    }
                    return CommandSupport.OK;
                })
                .then(world()
                    .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                        .executes(ctx -> setAt(ctx, false))
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180f, 180f))
                            .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90f, 90f))
                                .executes(ctx -> setAt(ctx, true)))))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> world() {
        return Commands.argument("world", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(world.getName());
                }
            }
            return builder.buildFuture();
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> coordinate(String name) {
        return Commands.argument(name, DoubleArgumentType.doubleArg(-MAX_COORDINATE, MAX_COORDINATE));
    }

    private int setAt(CommandContext<CommandSourceStack> ctx, boolean facing) {
        CommandSender sender = ctx.getSource().getSender();
        String worldName = StringArgumentType.getString(ctx, "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            this.services.messenger().send(sender, SpawnMessages.UNKNOWN_WORLD, Arg.text("name", worldName));
            return CommandSupport.OK;
        }
        float yaw = facing ? FloatArgumentType.getFloat(ctx, "yaw") : 0f;
        float pitch = facing ? FloatArgumentType.getFloat(ctx, "pitch") : 0f;
        set(sender, new SpawnPoint(world.getName(), DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"),
            DoubleArgumentType.getDouble(ctx, "z"), yaw, pitch));
        return CommandSupport.OK;
    }

    private void set(CommandSender sender, SpawnPoint point) {
        boolean outside = this.feature.borders().current(point.world())
            .map(border -> !border.inside(point.x(), point.z(), 0))
            .orElse(false);
        if (outside) {
            this.services.messenger().send(sender, SpawnMessages.OUTSIDE_BORDER, Arg.text("world", point.world()));
            return;
        }
        this.feature.set(point);
        this.services.messenger().chat(sender, SpawnMessages.SET, Arg.text("world", point.world()),
            Arg.number("x", Math.round(Math.floor(point.x()))), Arg.number("y", Math.round(Math.floor(point.y()))),
            Arg.number("z", Math.round(Math.floor(point.z()))));
        this.services.audit().record(actor(sender), "spawn.set", point.world(),
            String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f", point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
