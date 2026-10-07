package net.siftvanilla.siftcore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shared helpers for feature commands: permission predicates, player-only checks, cooldowns from
 * {@code commands.yml}, and arguments for player names and money amounts. Player-name arguments are plain words
 * with suggestions (never entity selectors), so regular players cannot target {@code @a}.
 */
public final class CommandSupport {

    public static final int OK = Command.SINGLE_SUCCESS;

    private final Messenger messenger;
    private final PlayerDirectory directory;
    private final Cooldowns cooldowns;
    private final Setting<CommandSettings> settings;
    private final Supplier<MoneyFormat> money;

    public CommandSupport(Messenger messenger, PlayerDirectory directory, Cooldowns cooldowns,
                          Setting<CommandSettings> settings, Supplier<MoneyFormat> money) {
        this.messenger = messenger;
        this.directory = directory;
        this.cooldowns = cooldowns;
        this.settings = settings;
        this.money = money;
    }

    public Messenger messenger() {
        return this.messenger;
    }

    public Cooldowns cooldowns() {
        return this.cooldowns;
    }

    public static Predicate<CommandSourceStack> permission(String node) {
        return source -> node == null || source.getSender().hasPermission(node);
    }

    /** Only players with the permission (console excluded). */
    public static Predicate<CommandSourceStack> playerPermission(String node) {
        return source -> source.getSender() instanceof Player && (node == null || source.getSender().hasPermission(node));
    }

    /** The executing player, or null after telling a console sender this command is for players. */
    public Player player(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (context.getSource().getExecutor() instanceof Player executor) {
            return executor;
        }
        if (sender instanceof Player player) {
            return player;
        }
        this.messenger.send(sender, CoreMessages.PLAYERS_ONLY);
        return null;
    }

    public CommandSender sender(CommandContext<CommandSourceStack> context) {
        return context.getSource().getSender();
    }

    /** Applies the command's cooldown from commands.yml; returns false (after telling the player) if not ready. */
    public boolean cooldown(Player player, String command) {
        Duration cooldown = this.settings.get().get(command).cooldown();
        if (cooldown.isZero() || player.hasPermission("siftcore.bypass.cooldown")) {
            return true;
        }
        Duration left = this.cooldowns.tryUse(player.getUniqueId(), "cmd:" + command, cooldown);
        if (left.isZero()) {
            return true;
        }
        this.messenger.send(player, CoreMessages.COOLDOWN, Arg.time("time", left));
        return false;
    }

    /** A cooldown with an explicit duration (feature-specific cooldowns). */
    public boolean cooldown(Player player, String key, Duration cooldown) {
        if (cooldown.isZero() || player.hasPermission("siftcore.bypass.cooldown")) {
            return true;
        }
        Duration left = this.cooldowns.tryUse(player.getUniqueId(), key, cooldown);
        if (left.isZero()) {
            return true;
        }
        this.messenger.send(player, CoreMessages.COOLDOWN, Arg.time("time", left));
        return false;
    }

    /** A player-name word argument suggesting online players the sender can see. */
    public static RequiredArgumentBuilder<CommandSourceStack, String> onlinePlayer(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)
                    && (!(sender instanceof Player viewer) || viewer.canSee(online))) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        });
    }

    /** A player-name argument suggesting online players first, then known offline names. */
    public RequiredArgumentBuilder<CommandSourceStack, String> knownPlayer(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            int count = 0;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(online.getName());
                    count++;
                }
            }
            if (remaining.length() >= 2 && count < 20) {
                for (String known : this.directory.namesStartingWith(remaining, 20 - count)) {
                    builder.suggest(known);
                }
            }
            return builder.buildFuture();
        });
    }

    /** A money amount argument (word, so 1.5k works). */
    public static RequiredArgumentBuilder<CommandSourceStack, String> amount(String name) {
        return Commands.argument(name, StringArgumentType.word());
    }

    /** Resolves an online player by exact name, telling the sender if not found. */
    public Player online(CommandContext<CommandSourceStack> context, String argument) {
        String name = StringArgumentType.getString(context, argument);
        Player player = Bukkit.getPlayerExact(name);
        CommandSender sender = context.getSource().getSender();
        if (player == null || (sender instanceof Player viewer && !viewer.canSee(player))) {
            this.messenger.send(sender, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", name));
            return null;
        }
        return player;
    }

    /** Resolves any player who ever joined, telling the sender if unknown. */
    public Optional<UUID> known(CommandContext<CommandSourceStack> context, String argument) {
        String name = StringArgumentType.getString(context, argument);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        Optional<UUID> uuid = this.directory.uuid(name);
        if (uuid.isEmpty()) {
            this.messenger.send(context.getSource().getSender(), CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", name));
        }
        return uuid;
    }

    /** Parses a money argument, telling the sender precisely what is wrong. */
    public OptionalLong money(CommandContext<CommandSourceStack> context, String argument) {
        return money(context.getSource().getSender(), StringArgumentType.getString(context, argument));
    }

    public OptionalLong money(CommandSender sender, String input) {
        MoneyFormat format = this.money.get();
        MoneyFormat.ParseResult result = format.parse(input);
        if (result.ok()) {
            return OptionalLong.of(result.amount());
        }
        switch (result.error()) {
            case NOT_WHOLE -> this.messenger.send(sender, CoreMessages.AMOUNT_NOT_WHOLE, Arg.text("input", input));
            case NOT_POSITIVE -> this.messenger.send(sender, CoreMessages.AMOUNT_NOT_POSITIVE);
            case TOO_LARGE -> this.messenger.send(sender, CoreMessages.AMOUNT_TOO_LARGE, Arg.money("max", format.maxAmount()));
            default -> this.messenger.send(sender, CoreMessages.INVALID_AMOUNT, Arg.text("input", input));
        }
        return OptionalLong.empty();
    }

    public MoneyFormat moneyFormat() {
        return this.money.get();
    }

    public PlayerDirectory directory() {
        return this.directory;
    }
}
