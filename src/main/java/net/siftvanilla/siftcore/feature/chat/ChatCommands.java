package net.siftvanilla.siftcore.feature.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /msg, /r, /ignore, /unignore, /msgtoggle, /socialspy and the staff command /chat. */
final class ChatCommands {

    /** The longest slow mode gap staff can set. */
    private static final Duration MAX_SLOW = Duration.ofHours(1);

    private final Services services;
    private final CommandSupport support;
    private final Setting<ChatSettings> settings;
    private final PrivateMessages messages;
    private final IgnoreList ignores;
    private final IgnoreViews views;
    private final ChatModeration moderation;

    ChatCommands(Services services, Setting<ChatSettings> settings, PrivateMessages messages, IgnoreList ignores,
                 IgnoreViews views, ChatModeration moderation) {
        this.services = services;
        this.support = services.commands();
        this.settings = settings;
        this.messages = messages;
        this.ignores = ignores;
        this.views = views;
        this.moderation = moderation;
    }

    List<SiftCommand> all() {
        return List.of(msg(), reply(), ignore(), unignore(), msgToggle(), socialSpy(), chat());
    }

    // ------------------------------------------------------------------ private messages

    private SiftCommand msg() {
        return new SimpleCommand("msg", List.of("tell", "w", "whisper", "pm", "message", "dm"), "Sends a private message",
            ChatNodes.MSG,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(ChatNodes.MSG))
                .then(CommandSupport.onlinePlayer("player")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            this.messages.message(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"),
                                StringArgumentType.getString(ctx, "message"));
                            return CommandSupport.OK;
                        }))));
    }

    private SiftCommand reply() {
        return new SimpleCommand("r", List.of("reply"), "Answers your last private message", ChatNodes.REPLY,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(ChatNodes.REPLY))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        Player player = this.support.player(ctx);
                        if (player != null) {
                            this.messages.reply(player, StringArgumentType.getString(ctx, "message"));
                        }
                        return CommandSupport.OK;
                    })));
    }

    /**
     * {@code /msgtoggle}: switches "Who can message me" between nobody and everyone (any other choice counts as on,
     * so it goes to nobody). Says so when the server locked or hides the setting.
     */
    private SiftCommand msgToggle() {
        return new SimpleCommand("msgtoggle", List.of("togglemsg", "pmtoggle", "togglepm"), "Turns private messages to you on or off",
            ChatNodes.MSGTOGGLE,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(ChatNodes.MSGTOGGLE))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        boolean on = this.services.settings().get(player, ChatFeature.PRIVATE_MESSAGES) == Audience.NOBODY;
                        SetResult result = this.services.settings().set(player, ChatFeature.PRIVATE_MESSAGES,
                            on ? Audience.EVERYONE : Audience.NOBODY, Change.feature());
                        report(player, result, ChatFeature.PRIVATE_MESSAGES, on ? ChatMessages.PM_TOGGLED_ON : ChatMessages.PM_TOGGLED_OFF);
                    }
                    return CommandSupport.OK;
                }));
    }

    private SiftCommand socialSpy() {
        return new SimpleCommand("socialspy", List.of(), "Shows private messages between players (staff)", ChatNodes.SOCIALSPY,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(ChatNodes.SOCIALSPY))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        boolean on = !this.services.settings().get(player, ChatFeature.SOCIAL_SPY);
                        SetResult result = this.services.settings().set(player, ChatFeature.SOCIAL_SPY, on, Change.feature());
                        report(player, result, ChatFeature.SOCIAL_SPY, on ? ChatMessages.SPY_ON : ChatMessages.SPY_OFF);
                    }
                    return CommandSupport.OK;
                }));
    }

    /**
     * Tells a player what a switch command did: {@code done} when the value now is what they asked for, that the
     * server sets it (locked, hidden or not offered), or that it couldn't be changed (another plugin refused).
     */
    private void report(Player player, SetResult result, PlayerSetting<?> setting, MessageKey done) {
        var messenger = this.services.messenger();
        if (result.succeeded()) {
            messenger.send(player, done);
        } else if (result == SetResult.LOCKED || result == SetResult.NOT_ALLOWED) {
            messenger.send(player, ChatMessages.SETTING_FIXED, Arg.text("setting", this.services.lang().plain(setting.label())));
        } else {
            messenger.send(player, ChatMessages.SETTING_REFUSED, Arg.text("setting", this.services.lang().plain(setting.label())));
        }
    }

    // ------------------------------------------------------------------ ignore

    private SiftCommand ignore() {
        return new SimpleCommand("ignore", List.of(), "Hides a player's chat and messages from you, or lists who you ignore",
            ChatNodes.IGNORE,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(ChatNodes.IGNORE))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.views.openList(player, 1);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.views.openList(player, 1);
                    }
                    return CommandSupport.OK;
                }))
                .then(visibleOrKnown("player").executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.support.known(ctx, "player").ifPresent(target -> this.views.toggle(player, target));
                    }
                    return CommandSupport.OK;
                })));
    }

    /**
     * A player name suggesting online players the sender can see (vanished staff stay hidden), then, from two typed
     * letters on, anyone who ever joined.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> visibleOrKnown(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            Set<String> suggested = new HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String lower = online.getName().toLowerCase(Locale.ROOT);
                if (lower.startsWith(remaining) && (!(sender instanceof Player viewer) || viewer.canSee(online)) && suggested.add(lower)) {
                    builder.suggest(online.getName());
                }
            }
            if (remaining.length() >= 2) {
                for (String known : this.services.directory().namesStartingWith(remaining, 20)) {
                    if (suggested.add(known.toLowerCase(Locale.ROOT))) {
                        builder.suggest(known);
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private SiftCommand unignore() {
        return new SimpleCommand("unignore", List.of(), "Stops ignoring a player", ChatNodes.IGNORE,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(ChatNodes.IGNORE))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        if (context.getSource().getSender() instanceof Player player) {
                            String remaining = builder.getRemainingLowerCase();
                            for (UUID uuid : this.ignores.ignored(player.getUniqueId())) {
                                String name = this.services.directory().name(uuid);
                                if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                    builder.suggest(name);
                                }
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        Player player = this.support.player(ctx);
                        if (player != null) {
                            this.support.known(ctx, "player").ifPresent(target -> this.views.remove(player, target));
                        }
                        return CommandSupport.OK;
                    })));
    }

    // ------------------------------------------------------------------ /chat (staff)

    private SiftCommand chat() {
        return new SimpleCommand("chat", List.of("chatadmin"), "Chat lock, slow mode, filter test and ignore lists (staff)",
            ChatNodes.ADMIN,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(ChatNodes.ADMIN))
                .executes(ctx -> status(ctx.getSource().getSender()))
                .then(Commands.literal("lock").executes(ctx -> lock(ctx.getSource().getSender(), true)))
                .then(Commands.literal("unlock").executes(ctx -> lock(ctx.getSource().getSender(), false)))
                .then(Commands.literal("slow")
                    .then(Commands.argument("duration", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String option : List.of("off", "3s", "5s", "10s", "30s")) {
                                if (option.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(option);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::slow)))
                .then(Commands.literal("test")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> test(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("ignores")
                    .then(this.support.knownPlayer("player").executes(this::ignoresOf))));
    }

    private int status(CommandSender sender) {
        var lang = this.services.lang();
        Duration slow = this.moderation.slow();
        ChatSettings settings = this.settings.get();
        this.services.messenger().chat(sender, ChatMessages.ADMIN_STATUS,
            Arg.text("lock", lang.plain(this.moderation.locked() ? ChatMessages.ADMIN_STATUS_LOCKED : ChatMessages.ADMIN_STATUS_OPEN)),
            Arg.text("slow", slow.isZero() ? lang.plain(ChatMessages.ADMIN_STATUS_SLOW_OFF) : Durations.format(slow)),
            Arg.number("words", settings.filter().size()),
            Arg.text("links", lang.plain(!settings.links().enabled() ? ChatMessages.ADMIN_STATUS_LINKS_OFF
                : settings.linkAction() == ChatFilter.Action.BLOCK ? ChatMessages.ADMIN_STATUS_LINKS_BLOCK : ChatMessages.ADMIN_STATUS_LINKS_REPLACE)),
            Arg.number("ignores", this.ignores.total()),
            Arg.number("rate", settings.spam().rateMessages()),
            Arg.time("window", settings.spam().rateWindow()),
            Arg.time("cooldown", settings.spam().cooldown()));
        return CommandSupport.OK;
    }

    private int lock(CommandSender sender, boolean lock) {
        if (!this.moderation.lock(lock)) {
            this.services.messenger().chat(sender, lock ? ChatMessages.ADMIN_ALREADY_LOCKED : ChatMessages.ADMIN_ALREADY_UNLOCKED);
            return CommandSupport.OK;
        }
        this.services.messenger().broadcast(lock ? ChatMessages.ADMIN_LOCKED : ChatMessages.ADMIN_UNLOCKED);
        this.services.audit().record(actor(sender), lock ? "chat.lock" : "chat.unlock", null, null);
        return CommandSupport.OK;
    }

    private int slow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String input = StringArgumentType.getString(ctx, "duration");
        Duration duration;
        if (input.equalsIgnoreCase("off") || input.equals("0")) {
            duration = Duration.ZERO;
        } else {
            try {
                duration = Durations.parse(input);
            } catch (IllegalArgumentException | ArithmeticException e) {
                duration = null;
            }
            if (duration == null || duration.isNegative()) {
                this.services.messenger().chat(sender, ChatMessages.ADMIN_SLOW_INVALID, Arg.text("input", input));
                return CommandSupport.OK;
            }
            if (duration.compareTo(MAX_SLOW) > 0) {
                this.services.messenger().chat(sender, ChatMessages.ADMIN_SLOW_TOO_LONG, Arg.time("max", MAX_SLOW));
                return CommandSupport.OK;
            }
        }
        this.moderation.slow(duration);
        if (duration.isZero()) {
            this.services.messenger().broadcast(ChatMessages.ADMIN_SLOW_OFF);
        } else {
            this.services.messenger().broadcast(ChatMessages.ADMIN_SLOW_ON, Arg.time("time", duration));
        }
        this.services.audit().record(actor(sender), "chat.slow", null, Durations.format(duration));
        return CommandSupport.OK;
    }

    /** Shows what anti-spam's text checks, the link check and the filter would do with a message, without sending anything. */
    private int test(CommandSender sender, String raw) {
        String text = ChatText.clean(raw);
        ChatSettings settings = this.settings.get();
        var messenger = this.services.messenger();
        if (text.length() > settings.spam().maxLength()) {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_TOO_LONG, Arg.number("max", settings.spam().maxLength()));
        }
        if (SpamGuard.shouting(text, settings.spam().capsRatio(), settings.spam().capsMinLetters())) {
            if (settings.spam().capsAction() == SpamGuard.CapsAction.BLOCK) {
                messenger.chat(sender, ChatMessages.ADMIN_TEST_CAPS_BLOCKED);
            } else {
                messenger.chat(sender, ChatMessages.ADMIN_TEST_CAPS_LOWERED);
                text = text.toLowerCase(Locale.ROOT);
            }
        }
        LinkGuard.Result links = settings.links().apply(text, settings.linkAction(), settings.filterReplacement());
        if (links.blocked()) {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_LINK_BLOCKED, Arg.text("links", String.join(", ", links.matched())));
        } else if (links.changed()) {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_LINK_REPLACED, Arg.text("text", links.text()),
                Arg.text("links", String.join(", ", links.matched())));
            text = links.text();
        }
        ChatFilter.Result result = settings.filter().apply(text, settings.filterAction(), settings.filterReplacement());
        if (result.clean()) {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_CLEAN);
        } else if (result.blocked()) {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_BLOCKED, Arg.text("words", String.join(", ", result.matched())));
        } else {
            messenger.chat(sender, ChatMessages.ADMIN_TEST_REPLACED, Arg.text("text", result.text()),
                Arg.text("words", String.join(", ", result.matched())));
        }
        return CommandSupport.OK;
    }

    private int ignoresOf(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        var directory = this.services.directory();
        String name = directory.name(target.get());
        List<String> names = new ArrayList<>();
        for (UUID uuid : this.ignores.ignored(target.get())) {
            names.add(directory.name(uuid));
        }
        if (names.isEmpty()) {
            this.services.messenger().chat(sender, ChatMessages.ADMIN_IGNORES_NONE, Arg.text("name", name));
            return CommandSupport.OK;
        }
        names.sort(Comparator.comparing(n -> n.toLowerCase(Locale.ROOT)));
        this.services.messenger().chat(sender, ChatMessages.ADMIN_IGNORES_HEADER, Arg.text("name", name), Arg.number("count", names.size()));
        for (String ignored : names) {
            this.services.messenger().chat(sender, ChatMessages.ADMIN_IGNORES_LINE, Arg.text("name", ignored));
        }
        return CommandSupport.OK;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
