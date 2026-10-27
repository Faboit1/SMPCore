package net.siftvanilla.siftcore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.VanishStatus;
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
 * <p>
 * Vanished staff stay hidden: player-name suggestions ({@link #onlinePlayer} and {@link #knownPlayer}) and
 * {@link #online} only offer or find online players the sender {@link #canSee may see}, which asks the staff feature's
 * vanish (bound once it is built, see {@link #vanish(VanishStatus)}) as well as the server's own hide list.
 */
public final class CommandSupport {

    public static final int OK = Command.SINGLE_SUCCESS;
    /** Players with this node see vanished staff (granted by the staff feature, which declares it). */
    public static final String SEE_VANISHED = "siftcore.staff.vanish.see";
    /** At most this many names are suggested for a player argument. */
    static final int MAX_SUGGESTIONS = 20;

    /** The player and command whose cooldown the command running on this thread was charged for. */
    private record Charged(UUID player, String command) {
    }

    private final ThreadLocal<Charged> charged = new ThreadLocal<>();
    private final Messenger messenger;
    private final PlayerDirectory directory;
    private final Cooldowns cooldowns;
    private final Setting<CommandSettings> settings;
    private final Supplier<MoneyFormat> money;
    private volatile VanishStatus vanish = VanishStatus.NONE;
    /**
     * The vanish bound last, for the static {@link #onlinePlayer} suggestions, which features build without an
     * instance. The plugin has one CommandSupport, so this is its binding.
     */
    private static volatile VanishStatus boundVanish = VanishStatus.NONE;

    public CommandSupport(Messenger messenger, PlayerDirectory directory, Cooldowns cooldowns,
                          Setting<CommandSettings> settings, Supplier<MoneyFormat> money) {
        this.messenger = messenger;
        this.directory = directory;
        this.cooldowns = cooldowns;
        this.settings = settings;
        this.money = money;
    }

    /**
     * Binds who is vanished (the staff feature is built after the commands, so the composition root binds it once it
     * exists). Until then, and on servers without it, only the server's hide list counts. It also binds the static
     * {@link #onlinePlayer} suggestions.
     */
    public void vanish(VanishStatus vanish) {
        this.vanish = Objects.requireNonNull(vanish);
        boundVanish = vanish;
    }

    /** Who is vanished, as bound by {@link #vanish(VanishStatus)}. */
    public VanishStatus vanish() {
        return this.vanish;
    }

    /**
     * Whether {@code viewer} may know that the online {@code target} is online: the console (and command blocks) and
     * the target themselves always may; a player only when the server shows them the target and the target is not
     * vanished, unless they see vanished staff. The vanish check also covers the moment before a vanish reaches the
     * server's hide list. Thread-safe (suggestions are computed off the main threads).
     */
    public boolean canSee(CommandSender viewer, Player target) {
        return canSee(viewer, target, this.vanish);
    }

    /** {@link #canSee(CommandSender, Player)} with the given vanish. */
    static boolean canSee(CommandSender viewer, Player target, VanishStatus vanish) {
        if (!(viewer instanceof Player player) || player.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        return visible(player.canSee(target), vanish.vanished(target.getUniqueId()), player.hasPermission(SEE_VANISHED));
    }

    /** The visibility rule on its own: shown by the server, and not vanished unless the viewer sees vanished staff. */
    static boolean visible(boolean serverShows, boolean vanished, boolean seesVanished) {
        return serverShows && (!vanished || seesVanished);
    }

    /** The online player with this id if {@code viewer} {@link #canSee may see} them, otherwise null. */
    public Player visibleOnline(CommandSender viewer, UUID player) {
        Player online = Bukkit.getPlayer(player);
        return online != null && canSee(viewer, online) ? online : null;
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

    /**
     * Applies the command's cooldown from commands.yml; returns false (after telling the player) if not ready.
     * {@link CommandService} already does this for every player who runs a SiftCore command, so a feature only calls
     * it for its own paths outside the command (a dialog button that does what the command does). Called while that
     * same command runs, it returns true: the run was already charged when it started. A button on a screen the command
     * opens would always find this cooldown running; use {@link #cooldown(Player, String, String)} there.
     */
    public boolean cooldown(Player player, String command) {
        Charged charged = this.charged.get();
        if (charged != null && charged.player().equals(player.getUniqueId()) && charged.command().equals(command)) {
            return true;
        }
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

    /**
     * Applies the command's commands.yml cooldown to one action that both the command and its screens run (a friend
     * request), under a key of its own: opening the command's screens or its other subcommands doesn't use it up, and
     * the action is charged once whichever way it runs. Returns false (after telling the player) if not ready.
     */
    public boolean cooldown(Player player, String command, String action) {
        return cooldown(player, "cmd:" + command + ":" + action, this.settings.get().get(command).cooldown());
    }

    /**
     * Wraps the body of one command node so a player who runs it first passes the command's commands.yml cooldown
     * ({@link #cooldown(Player, String)}, bypassed with {@code siftcore.bypass.cooldown}). The cooldown is read on every
     * run, so {@code /sift reload} applies it. The console and command blocks are never held up. The body renders as
     * the player reads (money in their money format, {@link net.siftvanilla.siftcore.core.text.Lang#viewing}): the
     * lines and screens a command shows its sender follow their choice.
     */
    Command<CommandSourceStack> withCooldown(String command, Command<CommandSourceStack> body) {
        return context -> {
            if (!(context.getSource().getSender() instanceof Player player)) {
                return body.run(context);
            }
            if (!cooldown(player, command)) {
                return OK;
            }
            Charged outer = this.charged.get();
            this.charged.set(new Charged(player.getUniqueId(), command));
            try (var _ = this.messenger.lang().open(player)) {
                return body.run(context);
            } finally {
                if (outer == null) {
                    this.charged.remove();
                } else {
                    this.charged.set(outer);
                }
            }
        };
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

    /**
     * A player-name word argument suggesting the online players the sender {@link #canSee may see}: the server's hide
     * list and the vanish {@link #vanish(VanishStatus) bound} to the plugin's CommandSupport, so a vanished staff member
     * is never offered, also in the moment before the server hides them.
     */
    public static RequiredArgumentBuilder<CommandSourceStack, String> onlinePlayer(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            for (String suggestion : onlineNames(context.getSource().getSender(), builder.getRemainingLowerCase(), Bukkit.getOnlinePlayers())) {
                builder.suggest(suggestion);
            }
            return builder.buildFuture();
        });
    }

    /** The names {@link #onlinePlayer} suggests for what was typed: online players the sender may see, in order. */
    static List<String> onlineNames(CommandSender sender, String typed, Collection<? extends Player> online) {
        String remaining = typed.toLowerCase(Locale.ROOT);
        VanishStatus vanish = boundVanish;
        List<String> names = new ArrayList<>();
        for (Player player : online) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining) && canSee(sender, player, vanish)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    /**
     * A player-name argument for anyone who ever joined: suggests the online players the sender {@link #canSee may see}
     * (so a vanished staff member never shows up as online), then, from two typed letters on, known names.
     */
    public RequiredArgumentBuilder<CommandSourceStack, String> knownPlayer(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            for (String suggestion : knownNames(context.getSource().getSender(), builder.getRemainingLowerCase(), Bukkit.getOnlinePlayers())) {
                builder.suggest(suggestion);
            }
            return builder.buildFuture();
        });
    }

    /**
     * The names {@link #knownPlayer} suggests for what was typed: online players the sender may see, then (from two
     * letters on, so an empty prefix lists exactly who is visibly online) names from the directory, each name once and
     * at most {@link #MAX_SUGGESTIONS}. A hidden player can only come up as an ordinary known name, which says nothing
     * about whether they are online (suggestions are shown sorted).
     */
    List<String> knownNames(CommandSender sender, String typed, Collection<? extends Player> online) {
        String remaining = typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Player player : online) {
            if (names.size() >= MAX_SUGGESTIONS) {
                return names;
            }
            String lower = player.getName().toLowerCase(Locale.ROOT);
            if (lower.startsWith(remaining) && canSee(sender, player) && seen.add(lower)) {
                names.add(player.getName());
            }
        }
        if (remaining.length() >= 2) {
            for (String known : this.directory.namesStartingWith(remaining, MAX_SUGGESTIONS)) {
                if (names.size() >= MAX_SUGGESTIONS) {
                    break;
                }
                if (seen.add(known.toLowerCase(Locale.ROOT))) {
                    names.add(known);
                }
            }
        }
        return names;
    }

    /** A money amount argument (word, so 1.5k works). */
    public static RequiredArgumentBuilder<CommandSourceStack, String> amount(String name) {
        return Commands.argument(name, StringArgumentType.word());
    }

    /**
     * Resolves an online player by exact name, telling the sender if not found. A player the sender may not
     * {@link #canSee see} (vanished staff) is not found, with the same message as someone offline.
     */
    public Player online(CommandContext<CommandSourceStack> context, String argument) {
        String name = StringArgumentType.getString(context, argument);
        CommandSender sender = context.getSource().getSender();
        Player player = online(sender, name);
        if (player == null) {
            this.messenger.send(sender, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", name));
        }
        return player;
    }

    /** The online player of that exact name if {@code sender} {@link #canSee may see} them, otherwise null. */
    public Player online(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player != null && canSee(sender, player) ? player : null;
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
