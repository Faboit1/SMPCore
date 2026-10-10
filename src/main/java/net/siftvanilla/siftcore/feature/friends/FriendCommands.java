package net.siftvanilla.siftcore.feature.friends;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /friend} (aliases {@code /f} and {@code /friends}) with every subcommand, and {@code /profile}. Every dialog
 * action has a command, so players without dialogs (older clients) can do everything from chat. Names resolve through
 * the player directory (Bedrock names with a dot prefix included) and are checked with the same rule as the dialog
 * form. Tab completion uses this feature's own suggestions and never offers a vanished player or one the sender can't
 * see.
 */
final class FriendCommands {

    static final String FRIEND_PERMISSION = "siftcore.command.friend";
    static final String PROFILE_PERMISSION = "siftcore.command.profile";

    private static final int MAX_SUGGESTIONS = 40;

    private final Services services;
    private final CommandSupport support;
    private final Messenger messenger;
    private final Lang lang;
    private final Setting<FriendsSettings> settings;
    private final FriendService service;
    private final FriendGraph graph;
    private final FriendViews views;

    FriendCommands(Services services, Setting<FriendsSettings> settings, FriendService service, FriendViews views) {
        this.services = services;
        this.support = services.commands();
        this.messenger = services.messenger();
        this.lang = services.lang();
        this.settings = settings;
        this.service = service;
        this.graph = service.graph();
        this.views = views;
    }

    List<SiftCommand> all() {
        return List.of(friend(), profile());
    }

    // ------------------------------------------------------------------ /friend

    private SiftCommand friend() {
        return new SimpleCommand("friend", List.of("f", "friends"), "Your friends: who is online, requests and profiles",
            FRIEND_PERMISSION, label -> Commands.literal(label)
                .requires(CommandSupport.permission(FRIEND_PERMISSION))
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        this.views.openList(player, FriendViews.Nav.command(), null);
                    } else {
                        help(ctx.getSource().getSender());
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
                .then(player("add").then(nameArgument(this::addable).executes(playerOnly(this::smart))))
                .then(player("accept")
                    .executes(playerOnly((player, ctx) -> answerAll(player, true)))
                    .then(nameArgument(this::requesters).executes(playerOnly((player, ctx) -> answer(player, ctx, true)))))
                .then(player("deny")
                    .executes(playerOnly((player, ctx) -> answerAll(player, false)))
                    .then(nameArgument(this::requesters).executes(playerOnly((player, ctx) -> answer(player, ctx, false)))))
                .then(player("cancel").then(nameArgument(this::sentTo).executes(playerOnly(this::cancel))))
                .then(player("remove").then(nameArgument(this::friendNames).executes(playerOnly(this::remove))))
                .then(player("requests").executes(playerOnly((player, ctx) ->
                    this.views.openRequests(player, FriendViews.Nav.command(), null))))
                .then(favourite("favourite"))
                .then(favourite("fav"))
                .then(player("note").then(nameArgument(this::friendNames)
                    .executes(playerOnly((player, ctx) -> note(player, ctx, null)))
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(playerOnly((player, ctx) -> note(player, ctx, StringArgumentType.getString(ctx, "text")))))))
                .then(player("settings")
                    .executes(playerOnly((player, ctx) -> this.views.openSettings(player, FriendViews.Nav.command(), false)))
                    .then(Commands.argument("key", StringArgumentType.word()).suggests(this::settingKeys)
                        .executes(playerOnly((player, ctx) -> showSetting(player, StringArgumentType.getString(ctx, "key"))))
                        .then(Commands.argument("value", StringArgumentType.word()).suggests(this::settingValues)
                            .executes(playerOnly((player, ctx) -> setSetting(player, StringArgumentType.getString(ctx, "key"),
                                StringArgumentType.getString(ctx, "value")))))))
                .then(player("list")
                    .executes(playerOnly((player, ctx) -> chatList(player, 1)))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000))
                        .executes(playerOnly((player, ctx) -> chatList(player, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(nameArgument(this::addable).requires(CommandSupport.playerPermission(FRIEND_PERMISSION))
                    .executes(playerOnly(this::smart))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> player(String literal) {
        return Commands.literal(literal).requires(CommandSupport.playerPermission(FRIEND_PERMISSION));
    }

    private LiteralArgumentBuilder<CommandSourceStack> favourite(String literal) {
        return player(literal).then(nameArgument(this::friendNames).executes(playerOnly(this::toggleFavourite)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArgument(
        com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("player", StringArgumentType.word()).suggests(suggestions);
    }

    /** Wraps a player-only handler (the console gets the usual "players only" message). */
    private Command<CommandSourceStack> playerOnly(PlayerHandler handler) {
        return ctx -> {
            Player player = this.support.player(ctx);
            if (player != null) {
                handler.handle(player, ctx);
            }
            return CommandSupport.OK;
        };
    }

    @FunctionalInterface
    private interface PlayerHandler {
        void handle(Player player, CommandContext<CommandSourceStack> ctx);
    }

    /** The player help; the console, which can't use any of it, gets a pointer to the staff tools and their help. */
    private int help(CommandSender sender) {
        if (sender instanceof Player) {
            this.messenger.chat(sender, FriendsMessages.HELP);
        } else {
            this.messenger.chat(sender, FriendsMessages.CONSOLE_HELP);
            this.messenger.chat(sender, FriendsMessages.STAFF_HELP);
        }
        return CommandSupport.OK;
    }

    /**
     * The player named by the "player" argument, or null after telling the sender why not: a malformed name, or
     * nobody of that name ever joined.
     */
    private UUID target(Player sender, CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        if (!PlayerNames.valid(name)) {
            this.messenger.send(sender, FriendsMessages.INVALID_NAME);
            return null;
        }
        UUID uuid = this.views.resolve(name);
        if (uuid == null) {
            this.messenger.send(sender, CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", name));
        }
        return uuid;
    }

    private void smart(Player player, CommandContext<CommandSourceStack> ctx) {
        UUID target = target(player, ctx);
        if (target != null) {
            this.views.smartAdd(player, target, FriendViews.Nav.command(), FriendService.Via.COMMAND, reply -> {
            });
        }
    }

    private void answer(Player player, CommandContext<CommandSourceStack> ctx, boolean accept) {
        UUID target = target(player, ctx);
        if (target == null) {
            return;
        }
        if (accept) {
            this.service.accept(player, target, FriendService.Via.COMMAND);
        } else {
            this.service.deny(player, target, FriendService.Via.COMMAND);
        }
    }

    /** {@code /friend accept|deny} without a name: acts on the only request, or opens the requests dialog. */
    private void answerAll(Player player, boolean accept) {
        if (!this.graph.isLoaded(player.getUniqueId())) {
            this.messenger.send(player, FriendsMessages.LOADING);
            return;
        }
        Map<UUID, Long> incoming = this.service.incoming(player.getUniqueId());
        if (incoming.isEmpty()) {
            this.messenger.send(player, FriendsMessages.REQUEST_NONE);
            return;
        }
        if (incoming.size() > 1) {
            this.views.openRequests(player, FriendViews.Nav.command(), null);
            return;
        }
        UUID only = incoming.keySet().iterator().next();
        if (accept) {
            this.service.accept(player, only, FriendService.Via.COMMAND);
        } else {
            this.service.deny(player, only, FriendService.Via.COMMAND);
        }
    }

    private void cancel(Player player, CommandContext<CommandSourceStack> ctx) {
        UUID target = target(player, ctx);
        if (target != null) {
            this.service.cancel(player, target, FriendService.Via.COMMAND);
        }
    }

    /** {@code /friend remove <player>}: asks first, in a dialog. */
    private void remove(Player player, CommandContext<CommandSourceStack> ctx) {
        UUID target = target(player, ctx);
        if (target == null) {
            return;
        }
        if (!this.graph.isLoaded(player.getUniqueId())) {
            this.messenger.send(player, FriendsMessages.LOADING);
            return;
        }
        if (!this.graph.friends(player.getUniqueId(), target)) {
            this.messenger.send(player, FriendsMessages.NOT_FRIENDS, Arg.text("name", this.service.name(target)));
            return;
        }
        this.views.confirmRemove(player, target, FriendViews.Nav.command());
    }

    private void toggleFavourite(Player player, CommandContext<CommandSourceStack> ctx) {
        UUID target = target(player, ctx);
        if (target == null) {
            return;
        }
        FriendGraph.Edge edge = this.graph.edge(player.getUniqueId(), target);
        if (edge == null) {
            if (!this.graph.isLoaded(player.getUniqueId())) {
                this.messenger.send(player, FriendsMessages.LOADING);
            } else {
                this.messenger.send(player, FriendsMessages.NOT_FRIENDS, Arg.text("name", this.service.name(target)));
            }
            return;
        }
        this.service.favourite(player, target, !edge.favourite(), FriendService.Via.COMMAND);
    }

    /** {@code /friend note <player> [text]}: no text opens the form, {@code -} clears, anything else is the note. */
    private void note(Player player, CommandContext<CommandSourceStack> ctx, String text) {
        UUID target = target(player, ctx);
        if (target == null) {
            return;
        }
        if (text == null) {
            if (!this.graph.isLoaded(player.getUniqueId())) {
                this.messenger.send(player, FriendsMessages.LOADING);
                return;
            }
            if (!this.graph.friends(player.getUniqueId(), target)) {
                this.messenger.send(player, FriendsMessages.NOT_FRIENDS, Arg.text("name", this.service.name(target)));
                return;
            }
            this.views.openNote(player, target, FriendViews.Nav.command());
            return;
        }
        String note = text.strip().equals(NoteText.CLEAR) ? "" : text;
        this.service.note(player, target, note, FriendService.Via.COMMAND);
    }

    // ------------------------------------------------------------------ settings

    /** The lowercase key names {@code /friend settings} takes. */
    static List<String> settingKeys() {
        List<String> keys = new ArrayList<>();
        for (FriendPrefs.Key key : FriendPrefs.Key.values()) {
            keys.add(key.id().toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    /** An unknown key, in chat (it lists every key, too long for the action bar and worth keeping). */
    private void unknownKey(Player player) {
        this.messenger.chat(player, FriendsMessages.SETTINGS_UNKNOWN_KEY, Arg.text("keys", String.join(", ", settingKeys())));
    }

    private void showSetting(Player player, String keyText) {
        FriendPrefs.Key key = FriendPrefs.Key.parse(keyText);
        if (key == null) {
            unknownKey(player);
            return;
        }
        // The value the player reads now: a stored "favourites" while favourites are off shows as what it does, off.
        this.messenger.chat(player, FriendsMessages.SETTINGS_CURRENT, Arg.text("setting", key.id()),
            Arg.text("value", this.service.prefs().value(player, key)));
    }

    /**
     * Sets one friends setting through the settings registry, as the settings dialog would: refused with a reason when
     * the server locked or hides it, or another plugin stops it.
     */
    private void setSetting(Player player, String keyText, String value) {
        FriendPrefs.Key key = FriendPrefs.Key.parse(keyText);
        if (key == null) {
            unknownKey(player);
            return;
        }
        FriendPrefs prefs = this.service.prefs();
        switch (prefs.set(player, key, value)) {
            case CHANGED, UNCHANGED -> this.messenger.send(player, FriendsMessages.SETTINGS_SET, Arg.text("setting", key.id()),
                Arg.text("value", prefs.value(player, key)));
            case INVALID -> this.messenger.chat(player, FriendsMessages.SETTINGS_UNKNOWN_VALUE, Arg.text("setting", key.id()),
                Arg.text("values", String.join(", ", prefs.values(player, key))));
            case LOCKED -> this.messenger.send(player, FriendsMessages.SETTINGS_LOCKED, Arg.text("setting", key.id()),
                Arg.text("value", prefs.value(player, key)));
            case CANCELLED -> this.messenger.send(player, FriendsMessages.SETTINGS_REFUSED, Arg.text("setting", key.id()));
            case NOT_ALLOWED, UNKNOWN -> this.messenger.send(player, FriendsMessages.SETTINGS_UNAVAILABLE, Arg.text("setting", key.id()));
        }
    }

    private CompletableFuture<Suggestions> settingKeys(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String key : settingKeys()) {
            if (key.startsWith(remaining)) {
                builder.suggest(key);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> settingValues(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        FriendPrefs.Key key = FriendPrefs.Key.parse(StringArgumentType.getString(ctx, "key"));
        if (key != null && ctx.getSource().getSender() instanceof Player player) {
            String remaining = builder.getRemainingLowerCase();
            for (String value : this.service.prefs().values(player, key)) {
                if (value.startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------ /friend list

    /** The list in chat, for clients without dialogs: clickable names and pages, in the player's list order. */
    private void chatList(Player player, int page) {
        FriendGraph.Node node = this.graph.loaded(player.getUniqueId());
        if (node == null) {
            this.messenger.send(player, FriendsMessages.LOADING);
            return;
        }
        this.views.rows(player, node).whenComplete((rows, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Reading friends data for " + player.getName() + " failed", error);
                this.messenger.send(player, CoreMessages.ACTION_FAILED);
            } else if (player.isOnline()) {
                chatList(player, rows, page);
            }
        });
    }

    private void chatList(Player player, List<ListOrder.Row> rows, int page) {
        int pageSize = this.settings.get().pageSize();
        int pages = ListOrder.pages(rows.size(), pageSize);
        int current = Math.clamp(page, 1, pages);
        this.messenger.chat(player, FriendsMessages.LIST_CHAT_HEADER, Arg.number("total", rows.size()),
            Arg.number("online", ListOrder.online(rows)), Arg.number("page", current), Arg.number("pages", pages));
        if (rows.isEmpty()) {
            this.messenger.chat(player, FriendsMessages.LIST_CHAT_EMPTY);
            return;
        }
        long now = this.service.now();
        for (ListOrder.Row row : ListOrder.page(rows, current, pageSize)) {
            Component line = this.lang.get(FriendsMessages.LIST_CHAT_ROW, Arg.text("name", row.name()),
                Arg.text("status", this.views.statusWord(row.status(), row.lastSeen(), now)))
                .clickEvent(ClickEvent.runCommand("/profile " + row.name()))
                .hoverEvent(HoverEvent.showText(this.lang.get(FriendsMessages.LINK_PROFILE_HOVER, Arg.text("name", row.name()))));
            player.sendMessage(line);
        }
        if (pages > 1) {
            Component nav = Component.empty();
            if (current > 1) {
                nav = nav.append(pageLink(FriendsMessages.LIST_CHAT_PREVIOUS, current - 1));
            }
            if (current < pages) {
                if (current > 1) {
                    nav = nav.append(Component.text(" "));
                }
                nav = nav.append(pageLink(FriendsMessages.LIST_CHAT_NEXT, current + 1));
            }
            player.sendMessage(nav);
        }
    }

    private Component pageLink(net.siftvanilla.siftcore.core.text.MessageKey key, int page) {
        return this.lang.get(key)
            .clickEvent(ClickEvent.runCommand("/friend list " + page))
            .hoverEvent(HoverEvent.showText(this.lang.get(FriendsMessages.LIST_CHAT_PAGE_HOVER, Arg.number("page", page))));
    }

    // ------------------------------------------------------------------ /profile

    private SiftCommand profile() {
        return new SimpleCommand("profile", List.of(), "Shows a player's card, or a friend's profile", PROFILE_PERMISSION,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(PROFILE_PERMISSION))
                .executes(playerOnly((player, ctx) -> this.views.openProfile(player, player.getUniqueId(), FriendViews.Nav.command(), null)))
                .then(nameArgument(this::visiblePlayers).executes(playerOnly((player, ctx) -> {
                    UUID target = target(player, ctx);
                    if (target != null) {
                        this.views.openProfile(player, target, FriendViews.Nav.command(), null);
                    }
                }))));
    }

    // ------------------------------------------------------------------ suggestions

    /**
     * Whether the sender may see this online player among the online suggestions: not vanished, and visible to them
     * ({@link net.siftvanilla.siftcore.core.command.CommandSupport#canSee}, safe on the threads suggestions run on).
     */
    private boolean suggestable(CommandSender sender, Player online) {
        return !this.service.links().vanish().vanished(online.getUniqueId()) && this.services.commands().canSee(sender, online);
    }

    /**
     * Known names (players who joined before) starting with the prefix, once two characters are typed. A vanished
     * player (or one the sender can't see) is left out of the online part but stays here, exactly like any offline
     * player, so the suggestions never show who is hidden.
     */
    private void knownNames(SuggestionsBuilder builder, String remaining, Set<String> taken, Set<UUID> exclude) {
        if (remaining.length() < 2 || taken.size() >= MAX_SUGGESTIONS) {
            return;
        }
        for (String name : this.services.directory().namesStartingWith(remaining, MAX_SUGGESTIONS)) {
            if (taken.size() >= MAX_SUGGESTIONS) {
                return;
            }
            if (taken.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID id = this.services.directory().uuid(name).orElse(null);
            if (id == null || exclude.contains(id)) {
                continue;
            }
            taken.add(name.toLowerCase(Locale.ROOT));
            builder.suggest(name);
        }
    }

    /** {@code /friend add}: online visible non-friends without a request from the sender, then known names. */
    private CompletableFuture<Suggestions> addable(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            return builder.buildFuture();
        }
        UUID self = player.getUniqueId();
        Set<UUID> exclude = new HashSet<>(this.graph.friendsOf(self));
        exclude.addAll(this.service.outgoing(self).keySet());
        exclude.add(self);
        String remaining = builder.getRemainingLowerCase();
        Set<String> taken = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (taken.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (exclude.contains(online.getUniqueId()) || !suggestable(sender, online)) {
                continue;
            }
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                taken.add(name.toLowerCase(Locale.ROOT));
                builder.suggest(name);
            }
        }
        knownNames(builder, remaining, taken, exclude);
        return builder.buildFuture();
    }

    /** {@code accept}/{@code deny}: who sent the sender a request they can see. */
    private CompletableFuture<Suggestions> requesters(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            suggestIds(builder, this.service.incoming(player.getUniqueId()).keySet());
        }
        return builder.buildFuture();
    }

    /** {@code cancel}: who the sender sent a request to. */
    private CompletableFuture<Suggestions> sentTo(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            suggestIds(builder, this.service.outgoing(player.getUniqueId()).keySet());
        }
        return builder.buildFuture();
    }

    /** {@code remove}, {@code favourite}, {@code note}: the sender's friends. */
    private CompletableFuture<Suggestions> friendNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            suggestIds(builder, this.graph.friendsOf(player.getUniqueId()));
        }
        return builder.buildFuture();
    }

    private void suggestIds(SuggestionsBuilder builder, Set<UUID> ids) {
        String remaining = builder.getRemainingLowerCase();
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            String name = this.service.name(id);
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names.subList(0, Math.min(names.size(), MAX_SUGGESTIONS))) {
            builder.suggest(name);
        }
    }

    /** {@code /profile}: online players the sender can see, then known names. */
    private CompletableFuture<Suggestions> visiblePlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
        String remaining = builder.getRemainingLowerCase();
        Set<String> taken = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (taken.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (!suggestable(sender, online)) {
                continue;
            }
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                taken.add(name.toLowerCase(Locale.ROOT));
                builder.suggest(name);
            }
        }
        knownNames(builder, remaining, taken, Set.of());
        return builder.buildFuture();
    }
}
