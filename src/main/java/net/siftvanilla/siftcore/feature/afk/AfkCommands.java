package net.siftvanilla.siftcore.feature.afk;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /afk (toggle, zone, list) and /afkzone (teleport and the zone tools for staff). */
final class AfkCommands {

    private static final int COORDINATE = 30_000_000;

    private final Services services;
    private final AfkService service;
    private final Setting<AfkSettings> settings;
    private final CommandSupport support;

    AfkCommands(Services services, AfkService service, Setting<AfkSettings> settings) {
        this.services = services;
        this.service = service;
        this.settings = settings;
        this.support = services.commands();
    }

    List<SiftCommand> all() {
        return List.of(afk(), afkZone());
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    /** Runs an action for a player sender. */
    private int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /afk

    private SiftCommand afk() {
        return new SimpleCommand("afk", List.of(), "Marks you as away from the keyboard, or back", AfkService.COMMAND_AFK,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(AfkService.COMMAND_AFK))
                .executes(ctx -> player(ctx, this.service::toggle))
                .then(Commands.literal("zone")
                    .requires(CommandSupport.playerPermission(AfkService.COMMAND_ZONE))
                    .executes(ctx -> player(ctx, this.service::teleport)))
                .then(Commands.literal("list")
                    .requires(CommandSupport.permission(AfkService.ADMIN))
                    .executes(ctx -> list(ctx.getSource().getSender()))));
    }

    private int list(CommandSender sender) {
        long now = System.currentTimeMillis();
        List<PlayerAfk> afk = new ArrayList<>();
        for (PlayerAfk state : this.service.tracked()) {
            Player player = Bukkit.getPlayer(state.player());
            if (state.afk() && player != null && visible(sender, player)) {
                afk.add(state);
            }
        }
        afk.sort(Comparator.comparingLong(PlayerAfk::afkSince));
        messenger().chat(sender, AfkMessages.LIST_HEADER, Arg.number("count", afk.size()));
        if (afk.isEmpty()) {
            messenger().chat(sender, AfkMessages.LIST_EMPTY);
        }
        for (PlayerAfk state : afk) {
            Duration time = Duration.ofMillis(Math.max(0, now - state.afkSince()));
            var key = state.inZone() ? AfkMessages.LIST_LINE_ZONE : state.manual() ? AfkMessages.LIST_LINE_MANUAL : AfkMessages.LIST_LINE;
            messenger().chat(sender, key, Arg.text("name", this.services.directory().name(state.player())), Arg.time("time", time));
        }
        return CommandSupport.OK;
    }

    /** Vanished staff are left out unless the viewer sees vanished staff (the console sees everyone). */
    private boolean visible(CommandSender sender, Player player) {
        return this.services.commands().canSee(sender, player);
    }

    // ------------------------------------------------------------------ /afkzone

    private SiftCommand afkZone() {
        String admin = AfkService.ADMIN;
        return new SimpleCommand("afkzone", List.of("afkarea"), "Teleports you to the AFK zone, where you earn shards",
            AfkService.COMMAND_ZONE,
            label -> Commands.literal(label)
                .requires(source -> source.getSender().hasPermission(AfkService.COMMAND_ZONE) || source.getSender().hasPermission(admin))
                .executes(ctx -> player(ctx, this.service::teleport))
                .then(Commands.literal("info")
                    .requires(CommandSupport.permission(admin))
                    .executes(ctx -> info(ctx.getSource().getSender())))
                .then(Commands.literal("pos1")
                    .requires(CommandSupport.playerPermission(admin))
                    .executes(ctx -> player(ctx, p -> corner(p, 1))))
                .then(Commands.literal("pos2")
                    .requires(CommandSupport.playerPermission(admin))
                    .executes(ctx -> player(ctx, p -> corner(p, 2))))
                .then(Commands.literal("arrival")
                    .requires(CommandSupport.playerPermission(admin))
                    .executes(ctx -> player(ctx, this::arrival)))
                .then(Commands.literal("set")
                    .requires(CommandSupport.permission(admin))
                    .then(world()
                        .then(coordinate("x1").then(coordinate("y1").then(coordinate("z1")
                            .then(coordinate("x2").then(coordinate("y2").then(coordinate("z2")
                                .executes(this::set)))))))))
                .then(Commands.literal("reset")
                    .requires(CommandSupport.permission(admin))
                    .executes(ctx -> reset(ctx.getSource().getSender()))));
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

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> coordinate(String name) {
        return Commands.argument(name, IntegerArgumentType.integer(-COORDINATE, COORDINATE));
    }

    private int info(CommandSender sender) {
        AfkSettings s = this.settings.get();
        ZoneSpec spec = this.service.spec();
        ZoneBox box = this.service.box();
        messenger().chat(sender, AfkMessages.ADMIN_HEADER, Arg.text("where", box != null ? box.describe() : spec.describe()));
        messenger().chat(sender, this.service.overridden() ? AfkMessages.ADMIN_SOURCE_GAME : AfkMessages.ADMIN_SOURCE_CONFIG);
        if (!s.zoneEnabled()) {
            messenger().chat(sender, AfkMessages.ADMIN_OFF);
            return CommandSupport.OK;
        }
        if (box == null) {
            messenger().chat(sender, AfkMessages.ADMIN_WORLD_MISSING, Arg.text("world", spec.world()));
            return CommandSupport.OK;
        }
        if (box.arrival() != null) {
            messenger().chat(sender, AfkMessages.ADMIN_ARRIVAL, Arg.text("where", box.arrival().format()));
        } else {
            messenger().chat(sender, AfkMessages.ADMIN_ARRIVAL_AUTO);
        }
        messenger().chat(sender, AfkMessages.ADMIN_PLAYERS, Arg.number("count", this.service.visibleInside()),
            Arg.number("earning", this.service.visibleEarning()));
        messenger().chat(sender, AfkMessages.ADMIN_REWARDS, Arg.number("shards", s.shards()), Arg.time("time", s.interval()));
        if (!s.rankShards().isEmpty()) {
            messenger().chat(sender, AfkMessages.ADMIN_RANK_REWARDS, Arg.text("ranks", s.rankShards().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "))));
        }
        if (s.dailyCap() > 0) {
            messenger().chat(sender, AfkMessages.ADMIN_CAP, Arg.number("cap", s.dailyCap()));
        } else {
            messenger().chat(sender, AfkMessages.ADMIN_NO_CAP);
        }
        safety(sender, box);
        return CommandSupport.OK;
    }

    /** Says whether players resting in the zone are protected. */
    private void safety(CommandSender sender, ZoneBox box) {
        if (this.service.insideSpawn(box)) {
            messenger().chat(sender, AfkMessages.ADMIN_PROTECTED);
        } else if (this.settings.get().zoneSafe()) {
            messenger().chat(sender, AfkMessages.ADMIN_SAFE);
        } else {
            messenger().chat(sender, AfkMessages.ADMIN_UNSAFE);
        }
    }

    private void corner(Player admin, int index) {
        var here = admin.getLocation();
        messenger().chat(admin, AfkMessages.ADMIN_CORNER_SET, Arg.number("corner", index),
            Arg.text("where", here.getWorld().getName() + " " + here.getBlockX() + " " + here.getBlockY() + " " + here.getBlockZ()));
        Optional<ZoneSpec> zone = this.service.corner(admin, index);
        if (zone.isEmpty()) {
            messenger().chat(admin, AfkMessages.ADMIN_CORNER_NEXT, Arg.number("corner", index == 1 ? 2 : 1));
            return;
        }
        changed(admin, zone.get());
    }

    private int set(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String world = StringArgumentType.getString(ctx, "world");
        World loaded = Bukkit.getWorld(world);
        if (loaded == null) {
            messenger().chat(sender, AfkMessages.ADMIN_UNKNOWN_WORLD, Arg.text("world", world));
            return CommandSupport.OK;
        }
        Corner a = new Corner(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "y1"),
            IntegerArgumentType.getInteger(ctx, "z1"));
        Corner b = new Corner(IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "y2"),
            IntegerArgumentType.getInteger(ctx, "z2"));
        changed(sender, this.service.set(loaded.getName(), a, b));
        return CommandSupport.OK;
    }

    private void changed(CommandSender sender, ZoneSpec spec) {
        ZoneBox box = spec.resolve(0, 0, 0);
        messenger().chat(sender, AfkMessages.ADMIN_ZONE_SET, Arg.text("where", box.describe()));
        safety(sender, box);
        this.services.audit().record(actor(sender), "afk.zone.set", null, box.describe());
        reportSave(sender);
    }

    /** Tells the sender when the zone set in game could not be written to disk (it still applies until a restart). */
    private void reportSave(CommandSender sender) {
        this.service.saved().whenComplete((ignored, error) -> {
            if (error != null) {
                messenger().chat(sender, AfkMessages.ADMIN_SAVE_FAILED);
            }
        });
    }

    private void arrival(Player admin) {
        var here = admin.getLocation();
        if (!this.service.arrival(here)) {
            messenger().chat(admin, AfkMessages.ADMIN_ARRIVAL_OUTSIDE);
            return;
        }
        String where = String.format(Locale.ROOT, "%s %.1f %.1f %.1f", here.getWorld().getName(), here.getX(), here.getY(), here.getZ());
        messenger().chat(admin, AfkMessages.ADMIN_ARRIVAL_SET, Arg.text("where", where));
        this.services.audit().record(actor(admin), "afk.zone.arrival", null, where);
        reportSave(admin);
    }

    private int reset(CommandSender sender) {
        if (!this.service.reset()) {
            messenger().chat(sender, AfkMessages.ADMIN_NOTHING_TO_RESET);
            return CommandSupport.OK;
        }
        ZoneBox box = this.service.box();
        messenger().chat(sender, AfkMessages.ADMIN_RESET, Arg.text("where", box != null ? box.describe() : this.service.spec().describe()));
        this.services.audit().record(actor(sender), "afk.zone.reset", null, null);
        reportSave(sender);
        return CommandSupport.OK;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
