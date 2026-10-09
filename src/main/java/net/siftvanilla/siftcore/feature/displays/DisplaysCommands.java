package net.siftvanilla.siftcore.feature.displays;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /displays: create, move and delete displays, list them with a button to go to each, and refresh them now.
 * Positions come from where the admin stands, or from typed coordinates (which also works from the console).
 * Everything is re-checked when it runs (a dialog may be old), and the database decides before anything moves.
 */
final class DisplaysCommands {

    private static final double MAX = DisplaysSettings.MAX_COORDINATE;

    private final Services services;
    private final CommandSupport support;
    private final DisplaysFeature feature;

    DisplaysCommands(Services services, DisplaysFeature feature) {
        this.services = services;
        this.support = services.commands();
        this.feature = feature;
    }

    List<SiftCommand> all() {
        return List.of(displays());
    }

    private SiftCommand displays() {
        String permission = DisplaysFeature.PERMISSION;
        return new SimpleCommand("displays", List.of("display"), "Places leaderboards and info boards", permission,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(permission))
                .executes(ctx -> list(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getSender())))
                .then(Commands.literal("create")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(this::suggestTemplates)
                            .executes(this::createHere)
                            .then(world().then(coordinates(this::createAt))))))
                .then(Commands.literal("move")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(this::suggestDisplays)
                        .executes(this::moveHere)
                        .then(world().then(coordinates(this::moveAt)))))
                .then(Commands.literal("delete")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(this::suggestDisplays)
                        .executes(ctx -> delete(ctx, false))
                        .then(Commands.literal("confirm").executes(ctx -> delete(ctx, true)))))
                .then(Commands.literal("refresh").executes(ctx -> refresh(ctx.getSource().getSender()))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> world() {
        return Commands.argument("world", StringArgumentType.word()).suggests((ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(world.getName());
                }
            }
            return builder.buildFuture();
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> coordinates(com.mojang.brigadier.Command<CommandSourceStack> run) {
        return Commands.argument("x", DoubleArgumentType.doubleArg(-MAX, MAX))
            .then(Commands.argument("y", DoubleArgumentType.doubleArg(DisplaysSettings.MIN_Y, DisplaysSettings.MAX_Y))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-MAX, MAX)).executes(run)));
    }

    private CompletableFuture<Suggestions> suggestTemplates(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(this.feature.settings().templates().keySet(), builder);
    }

    private CompletableFuture<Suggestions> suggestDisplays(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(this.feature.displays().keySet(), builder);
    }

    private static CompletableFuture<Suggestions> suggest(Collection<String> names, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String name : names) {
            if (name.startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------ positions

    /** Where the player stands, facing them when the display is fixed. Player's thread. */
    private static DisplayPosition here(Player player) {
        Location at = player.getLocation();
        return new DisplayPosition(at.getWorld().getName(), DisplayPosition.round(at.getX()), DisplayPosition.round(at.getY()),
            DisplayPosition.round(at.getZ()), DisplayPosition.facing(at.getYaw()));
    }

    /** Typed coordinates; null (after telling the sender) when the world does not exist. */
    private DisplayPosition typed(CommandContext<CommandSourceStack> ctx) {
        String world = StringArgumentType.getString(ctx, "world");
        if (Bukkit.getWorld(world) == null) {
            this.services.messenger().send(ctx.getSource().getSender(), DisplaysMessages.UNKNOWN_WORLD, Arg.text("world", world));
            return null;
        }
        return new DisplayPosition(world, DisplayPosition.round(DoubleArgumentType.getDouble(ctx, "x")),
            DisplayPosition.round(DoubleArgumentType.getDouble(ctx, "y")), DisplayPosition.round(DoubleArgumentType.getDouble(ctx, "z")), 0f);
    }

    private String where(DisplayPosition position) {
        return this.services.lang().plain(DisplaysMessages.WHERE, Arg.text("world", position.world()),
            Arg.text("x", DisplayPosition.format(position.x())), Arg.text("y", DisplayPosition.format(position.y())),
            Arg.text("z", DisplayPosition.format(position.z())));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    // ------------------------------------------------------------------ create

    private int createHere(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player != null) {
            create(player, StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "template"), here(player));
        }
        return CommandSupport.OK;
    }

    private int createAt(CommandContext<CommandSourceStack> ctx) {
        DisplayPosition position = typed(ctx);
        if (position != null) {
            create(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "template"), position);
        }
        return CommandSupport.OK;
    }

    private void create(CommandSender sender, String id, String template, DisplayPosition position) {
        var messenger = this.services.messenger();
        if (!DisplaysSettings.NAME.matcher(id).matches()) {
            messenger.send(sender, DisplaysMessages.INVALID_ID);
            return;
        }
        DisplaysSettings settings = this.feature.settings();
        if (!settings.templates().containsKey(template)) {
            messenger.send(sender, DisplaysMessages.UNKNOWN_TEMPLATE, Arg.text("template", template));
            return;
        }
        if (settings.displays().containsKey(id)) {
            messenger.send(sender, DisplaysMessages.IN_CONFIG, Arg.text("id", id));
            return;
        }
        if (this.feature.displays().containsKey(id)) {
            messenger.send(sender, DisplaysMessages.EXISTS, Arg.text("id", id));
            return;
        }
        String where = where(position);
        Placement placement = new Placement(id, template, position, actor(sender), System.currentTimeMillis());
        this.feature.placements().create(placement).thenAccept(outcome -> {
            switch (outcome) {
                case DONE -> {
                    messenger.send(sender, DisplaysMessages.CREATED, Arg.text("id", id), Arg.text("where", where));
                    this.services.audit().record(actor(sender), "displays.create", id, template + " at " + where);
                }
                case TAKEN -> messenger.send(sender, DisplaysMessages.EXISTS, Arg.text("id", id));
                case BUSY -> messenger.send(sender, DisplaysMessages.BUSY, Arg.text("id", id));
                default -> messenger.send(sender, DisplaysMessages.FAILED, Arg.text("id", id));
            }
        });
    }

    // ------------------------------------------------------------------ move

    private int moveHere(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player != null) {
            move(player, StringArgumentType.getString(ctx, "id"), here(player));
        }
        return CommandSupport.OK;
    }

    private int moveAt(CommandContext<CommandSourceStack> ctx) {
        DisplayPosition position = typed(ctx);
        if (position != null) {
            move(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "id"), position);
        }
        return CommandSupport.OK;
    }

    private void move(CommandSender sender, String id, DisplayPosition position) {
        var messenger = this.services.messenger();
        DisplayDef def = this.feature.displays().get(id);
        if (def == null) {
            messenger.send(sender, DisplaysMessages.UNKNOWN, Arg.text("id", id));
            return;
        }
        Placement existing = this.feature.placements().get(id);
        String template = def.inConfig() ? null : existing != null ? existing.template() : def.templateId();
        String where = where(position);
        Placement placement = new Placement(id, template, position, actor(sender), System.currentTimeMillis());
        this.feature.placements().save(placement).thenAccept(outcome -> {
            switch (outcome) {
                case DONE -> {
                    messenger.send(sender, DisplaysMessages.MOVED, Arg.text("id", id), Arg.text("where", where));
                    this.services.audit().record(actor(sender), "displays.move", id, where);
                }
                case BUSY -> messenger.send(sender, DisplaysMessages.BUSY, Arg.text("id", id));
                default -> messenger.send(sender, DisplaysMessages.FAILED, Arg.text("id", id));
            }
        });
    }

    // ------------------------------------------------------------------ delete

    private int delete(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id");
        var messenger = this.services.messenger();
        DisplayDef def = this.feature.displays().get(id);
        if (def == null) {
            messenger.send(sender, DisplaysMessages.UNKNOWN, Arg.text("id", id));
            return CommandSupport.OK;
        }
        if (this.feature.placements().get(id) == null) {
            messenger.send(sender, DisplaysMessages.CONFIG_ONLY, Arg.text("id", id));
            return CommandSupport.OK;
        }
        if (confirmed) {
            deleteNow(sender, id);
        } else if (sender instanceof Player player) {
            confirmDelete(player, def);
        } else {
            messenger.send(sender, DisplaysMessages.CONFIRM_CONSOLE, Arg.text("id", id));
        }
        return CommandSupport.OK;
    }

    private void confirmDelete(Player player, DisplayDef def) {
        Lang lang = this.services.lang();
        String id = def.id();
        List<Component> body;
        MessageKey yes;
        if (!def.inConfig()) {
            body = lang.lines(DisplaysMessages.DELETE_BODY, Arg.text("id", id), Arg.text("template", templateName(def)),
                Arg.text("where", def.position() == null ? "-" : where(def.position())));
            yes = DisplaysMessages.DELETE_BUTTON;
        } else {
            DisplaysSettings.ConfigDisplay configured = this.feature.settings().displays().get(id);
            boolean backToFile = configured != null && configured.position() != null;
            body = lang.lines(backToFile ? DisplaysMessages.RESET_BODY : DisplaysMessages.HIDE_BODY, Arg.text("id", id));
            yes = DisplaysMessages.RESET_BUTTON;
        }
        View view = this.services.templates().confirm(lang.get(DisplaysMessages.DELETE_TITLE), body, lang.get(yes),
            lang.get(CoreMessages.UI_CANCEL), submission -> {
                Player clicker = submission.player();
                submission.close();
                if (!clicker.hasPermission(DisplaysFeature.PERMISSION)) {
                    this.services.messenger().send(clicker, CoreMessages.NO_PERMISSION);
                    return;
                }
                deleteNow(clicker, id);
            }, null).closing();
        this.services.dialogs().show(player, view);
    }

    /** Deletes the in-game row of a display, re-checking that it is still there. */
    private void deleteNow(CommandSender sender, String id) {
        var messenger = this.services.messenger();
        DisplayDef def = this.feature.displays().get(id);
        if (def == null || this.feature.placements().get(id) == null) {
            messenger.send(sender, def == null ? DisplaysMessages.UNKNOWN : DisplaysMessages.CONFIG_ONLY, Arg.text("id", id));
            return;
        }
        DisplaysSettings.ConfigDisplay configured = this.feature.settings().displays().get(id);
        this.feature.placements().delete(id).thenAccept(outcome -> {
            switch (outcome) {
                case DONE, MISSING -> {
                    MessageKey done = configured == null ? DisplaysMessages.DELETED
                        : configured.position() != null ? DisplaysMessages.RESET : DisplaysMessages.HIDDEN;
                    messenger.send(sender, done, Arg.text("id", id));
                    this.services.audit().record(actor(sender), "displays.delete", id, null);
                }
                case BUSY -> messenger.send(sender, DisplaysMessages.BUSY, Arg.text("id", id));
                default -> messenger.send(sender, DisplaysMessages.FAILED, Arg.text("id", id));
            }
        });
    }

    // ------------------------------------------------------------------ list, go to, refresh

    private int list(CommandSender sender) {
        Lang lang = this.services.lang();
        var messenger = this.services.messenger();
        var all = this.feature.displays().values();
        if (sender instanceof Player player) {
            // A button per display: placed ones teleport there, the others say how to place them. What each shows,
            // where it stands and its state are in the tooltip.
            List<Component> lines = all.isEmpty() ? List.of(lang.get(DisplaysMessages.LIST_EMPTY)) : List.of();
            List<Button> buttons = new ArrayList<>();
            for (DisplayDef def : all) {
                String id = def.id();
                if (def.position() != null && Bukkit.getWorld(def.position().world()) != null) {
                    // Go teleports and shows nothing next, so the list closes on the click.
                    buttons.add(Button.of(lang.get(DisplaysMessages.LIST_GO, Arg.text("id", id)),
                        Templates.lines(List.of(line(def), lang.get(DisplaysMessages.LIST_GO_TOOLTIP))),
                        submission -> go(submission.player(), id)).closes());
                } else {
                    buttons.add(Button.of(lang.get(DisplaysMessages.LIST_UNPLACED, Arg.text("id", id)),
                        Templates.lines(List.of(line(def), lang.get(DisplaysMessages.LIST_PLACE_TOOLTIP, Arg.text("id", id)))),
                        submission -> list(submission.player())));
                }
            }
            this.services.dialogs().show(player, this.services.templates().column(lang.get(DisplaysMessages.LIST_TITLE), lines, buttons, null));
            return CommandSupport.OK;
        }
        messenger.chat(sender, DisplaysMessages.LIST_HEADER, Arg.number("count", all.size()));
        if (all.isEmpty()) {
            messenger.chat(sender, DisplaysMessages.LIST_EMPTY);
        }
        for (DisplayDef def : all) {
            sender.sendMessage(line(def));
        }
        return CommandSupport.OK;
    }

    private Component line(DisplayDef def) {
        Lang lang = this.services.lang();
        Arg id = Arg.text("id", def.id());
        Arg template = Arg.text("template", templateName(def));
        if (def.position() == null) {
            return lang.get(DisplaysMessages.LIST_LINE_UNPLACED, id, template);
        }
        MessageKey state = switch (this.feature.state(def)) {
            case SHOWN -> DisplaysMessages.STATE_SHOWN;
            case MISSING -> DisplaysMessages.STATE_MISSING;
            case NO_TEMPLATE -> DisplaysMessages.STATE_NO_TEMPLATE;
            case NO_WORLD -> DisplaysMessages.STATE_NO_WORLD;
            case NOT_LOADED, NOT_PLACED -> DisplaysMessages.STATE_NOT_LOADED;
        };
        return lang.get(DisplaysMessages.LIST_LINE, id, template, Arg.text("where", where(def.position())),
            Arg.text("state", lang.plain(state)));
    }

    private static String templateName(DisplayDef def) {
        return def.templateId() == null ? "-" : def.templateId();
    }

    /** The list's "Go to" button: re-checks permission and position, then teleports (player's thread). */
    private void go(Player player, String id) {
        var messenger = this.services.messenger();
        if (!player.hasPermission(DisplaysFeature.PERMISSION)) {
            messenger.send(player, CoreMessages.NO_PERMISSION);
            return;
        }
        DisplayDef def = this.feature.displays().get(id);
        if (def == null) {
            messenger.send(player, DisplaysMessages.UNKNOWN, Arg.text("id", id));
            return;
        }
        if (def.position() == null) {
            messenger.send(player, DisplaysMessages.NOT_PLACED, Arg.text("id", id));
            return;
        }
        DisplayPosition position = def.position();
        World world = Bukkit.getWorld(position.world());
        if (world == null) {
            messenger.send(player, DisplaysMessages.UNKNOWN_WORLD, Arg.text("world", position.world()));
            return;
        }
        Location target = new Location(world, position.x(), position.y(), position.z(), position.yaw() + 180f, 0f);
        this.services.teleports().teleport(player, "displays", Duration.ZERO, () -> CompletableFuture.completedFuture(target), null);
    }

    private int refresh(CommandSender sender) {
        int count = this.feature.refreshAll();
        this.services.messenger().send(sender, DisplaysMessages.REFRESHED, Arg.number("count", count));
        return CommandSupport.OK;
    }
}
