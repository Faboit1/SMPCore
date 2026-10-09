package net.siftvanilla.siftcore.feature.cosmetics;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The cosmetics commands. Each opens its dialog without arguments and does the same with arguments, for players who
 * prefer typing: {@code /chatcolor}, {@code /nick}, {@code /tags}, {@code /joinmessage}, {@code /leavemessage},
 * {@code /killeffect}, {@code /cosmetics} and {@code /realname}. Staff set and clear nicknames with
 * {@code /nick <player> <name|off>} and look after players' cosmetics with {@code /cosmetics admin} (status, show,
 * reset, owned tags); every staff change is audited and works from the console.
 */
final class CosmeticsCommands {

    private final Services services;
    private final CommandSupport support;
    private final Lang lang;
    private final CosmeticsService cosmetics;
    private final CosmeticsActions actions;
    private final CosmeticsDialogs dialogs;

    CosmeticsCommands(Services services, CosmeticsService cosmetics, CosmeticsActions actions, CosmeticsDialogs dialogs) {
        this.services = services;
        this.support = services.commands();
        this.lang = services.lang();
        this.cosmetics = cosmetics;
        this.actions = actions;
        this.dialogs = dialogs;
    }

    List<SiftCommand> all() {
        return List.of(
            new SimpleCommand("cosmetics", List.of("cosmetic"), "Opens the cosmetics menu", CosmeticsNodes.MENU, this::cosmeticsTree),
            new SimpleCommand("chatcolor", List.of("chatcolour"), "Picks the colour of your chat messages", CosmeticsNodes.CHAT_COLOR,
                this::chatColorTree),
            new SimpleCommand("nick", List.of("nickname"), "Sets or removes your nickname", CosmeticsNodes.NICK, this::nickTree),
            new SimpleCommand("realname", List.of(), "Shows who uses a nickname", CosmeticsNodes.REALNAME, this::realnameTree),
            new SimpleCommand("tags", List.of("tag"), "Picks the tag before your name in chat", CosmeticsNodes.TAGS, this::tagsTree),
            new SimpleCommand("joinmessage", List.of("joinmsg"), "Sets your custom join message", CosmeticsNodes.JOIN_CUSTOM,
                label -> messageTree(label, true)),
            new SimpleCommand("leavemessage", List.of("leavemsg", "quitmessage"), "Sets your custom leave message",
                CosmeticsNodes.JOIN_CUSTOM, label -> messageTree(label, false)),
            new SimpleCommand("killeffect", List.of("killeffects"), "Picks your kill effect", CosmeticsNodes.KILL_EFFECT, this::killEffectTree));
    }

    private static Predicate<CommandSourceStack> anyPlayerPermission(String... nodes) {
        return source -> {
            if (!(source.getSender() instanceof Player player)) {
                return false;
            }
            for (String node : nodes) {
                if (player.hasPermission(node)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** Runs {@code action} for the executing player. */
    private int forPlayer(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.support.player(ctx);
        if (player != null) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ /cosmetics

    private LiteralArgumentBuilder<CommandSourceStack> cosmeticsTree(String label) {
        return Commands.literal(label)
            .requires(source -> source.getSender().hasPermission(CosmeticsNodes.MENU) || source.getSender().hasPermission(CosmeticsNodes.ADMIN))
            .executes(ctx -> forPlayer(ctx, this.dialogs::menu))
            .then(Commands.literal("admin").requires(CommandSupport.permission(CosmeticsNodes.ADMIN))
                .executes(ctx -> {
                    status(ctx.getSource().getSender());
                    return CommandSupport.OK;
                })
                .then(Commands.literal("show").then(this.support.knownPlayer("player").executes(ctx -> {
                    this.support.known(ctx, "player").ifPresent(uuid -> show(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })))
                .then(Commands.literal("reset").then(this.support.knownPlayer("player").executes(ctx -> {
                    this.support.known(ctx, "player").ifPresent(uuid -> reset(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })))
                .then(Commands.literal("owned").then(this.support.knownPlayer("player")
                    .then(Commands.literal("give").then(Commands.argument("tag", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestTags(builder))
                        .executes(ctx -> {
                            this.support.known(ctx, "player").ifPresent(uuid -> owned(ctx.getSource().getSender(), uuid,
                                StringArgumentType.getString(ctx, "tag"), true));
                            return CommandSupport.OK;
                        })))
                    .then(Commands.literal("take").then(Commands.argument("tag", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestTags(builder))
                        .executes(ctx -> {
                            this.support.known(ctx, "player").ifPresent(uuid -> owned(ctx.getSource().getSender(), uuid,
                                StringArgumentType.getString(ctx, "tag"), false));
                            return CommandSupport.OK;
                        }))))));
    }

    private CompletableFuture<Suggestions> suggestTags(SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String id : this.cosmetics.settings().tags().keySet()) {
            if (id.startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private void status(CommandSender sender) {
        CosmeticsService.Counters counters = this.cosmetics.counters();
        this.services.messenger().send(sender, CosmeticsMessages.ADMIN_STATUS,
            Arg.component("state", this.lang.get(this.cosmetics.on() ? CosmeticsMessages.ADMIN_ON : CosmeticsMessages.ADMIN_OFF)),
            Arg.number("players", this.cosmetics.profiles().size()),
            Arg.number("nicks", this.cosmetics.profiles().nickCount()),
            Arg.number("tags", this.cosmetics.settings().tags().size()),
            Arg.number("played", counters.played()),
            Arg.number("limited", counters.limited()));
    }

    private void show(CommandSender sender, UUID uuid) {
        Profile profile = this.cosmetics.profiles().get(uuid);
        Component none = this.lang.get(CosmeticsMessages.NONE);
        this.services.messenger().send(sender, CosmeticsMessages.ADMIN_SHOW,
            Arg.text("name", this.services.directory().name(uuid)),
            Arg.component("color", profile.chatStyle().none() ? none : this.cosmetics.styleName(profile.chatStyle())),
            Arg.component("nick", profile.nick() == null ? none : profile.nickStyle().apply(profile.nick())),
            Arg.component("tag", profile.tag() == null ? none : Component.text(profile.tag())),
            Arg.component("owned", profile.ownedTags().isEmpty() ? none : Component.text(String.join(", ", profile.ownedTags().stream().sorted().toList()))),
            profile.joinMessage() == null ? Arg.component("join", none) : Arg.text("join", profile.joinMessage()),
            profile.leaveMessage() == null ? Arg.component("leave", none) : Arg.text("leave", profile.leaveMessage()),
            Arg.component("effect", profile.killEffect() == null ? none : Component.text(profile.killEffect())));
    }

    /** Forgets a player's choices but keeps their owned monthly exclusives; the audit entry lists what was there. */
    private void reset(CommandSender sender, UUID uuid) {
        Profile before = this.cosmetics.profiles().reset(uuid);
        this.services.audit().record(actor(sender), "cosmetics.reset", uuid.toString(), "was " + before.describe()
            + (before.ownedTags().isEmpty() ? "" : "; owned tags kept"));
        String name = this.services.directory().name(uuid);
        if (before.ownedTags().isEmpty()) {
            this.services.messenger().send(sender, CosmeticsMessages.ADMIN_RESET, Arg.text("name", name));
        } else {
            this.services.messenger().send(sender, CosmeticsMessages.ADMIN_RESET_KEPT, Arg.text("name", name),
                Arg.number("owned", before.ownedTags().size()));
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            this.services.messenger().send(online, CosmeticsMessages.STAFF_RESET);
        }
    }

    /**
     * Gives a player a tag for good (as if they had picked a monthly exclusive in its month), or takes an owned tag
     * away: to put back what a reset or a mistake took, or to take back a tag after a refund. A taken tag that is
     * picked stops showing unless the player may still pick it through its permission (checked when shown).
     */
    private void owned(CommandSender sender, UUID uuid, String rawId, boolean give) {
        String id = rawId.toLowerCase(Locale.ROOT);
        String name = this.services.directory().name(uuid);
        ChatTag tag = this.cosmetics.settings().tags().get(id);
        if (give && tag == null) {
            this.services.messenger().send(sender, CosmeticsMessages.TAG_UNKNOWN, Arg.text("id", rawId));
            return;
        }
        boolean owns = this.cosmetics.profiles().get(uuid).ownedTags().contains(id);
        if (give == owns) {
            this.services.messenger().send(sender, give ? CosmeticsMessages.ADMIN_OWNED_ALREADY : CosmeticsMessages.ADMIN_OWNED_NOT,
                Arg.text("name", name), Arg.text("id", id));
            return;
        }
        this.cosmetics.profiles().update(uuid, profile -> give ? profile.withOwnedTag(id) : profile.withoutOwnedTag(id));
        this.services.audit().record(actor(sender), "cosmetics.owned", uuid.toString(), (give ? "gave '" : "took '") + id + "'");
        this.services.messenger().send(sender, give ? CosmeticsMessages.ADMIN_OWNED_GIVEN : CosmeticsMessages.ADMIN_OWNED_TAKEN,
            Arg.text("name", name), Arg.text("id", id));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    // ------------------------------------------------------------------ /chatcolor

    private LiteralArgumentBuilder<CommandSourceStack> chatColorTree(String label) {
        return Commands.literal(label)
            .requires(anyPlayerPermission(CosmeticsNodes.CHAT_COLOR, CosmeticsNodes.CHAT_COLOR_HEX))
            .executes(ctx -> forPlayer(ctx, this.dialogs::chatColor))
            .then(Commands.literal("reset").executes(ctx -> forPlayer(ctx,
                player -> this.actions.tell(player, this.actions.setChatStyle(player, ChatStyle.NONE)))))
            .then(Commands.argument("colour", StringArgumentType.greedyString()).suggests((ctx, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                for (NamedTextColor color : this.cosmetics.settings().colors().basic()) {
                    String name = NamedTextColor.NAMES.key(color);
                    if (name.startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            }).executes(ctx -> forPlayer(ctx, player -> {
                String input = StringArgumentType.getString(ctx, "colour");
                ChatStyle style = CosmeticsActions.typed(input, "");
                this.actions.tell(player, style == null
                    ? CosmeticsActions.Outcome.refused(CosmeticsMessages.COLOR_INVALID, Arg.text("input", input))
                    : this.actions.setChatStyle(player, style));
            })));
    }

    // ------------------------------------------------------------------ /nick and /realname

    private LiteralArgumentBuilder<CommandSourceStack> nickTree(String label) {
        return Commands.literal(label)
            .requires(source -> source.getSender() instanceof Player player && player.hasPermission(CosmeticsNodes.NICK)
                || source.getSender().hasPermission(CosmeticsNodes.NICK_ADMIN))
            .executes(ctx -> forPlayer(ctx, this.dialogs::nick))
            .then(Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                if ("off".startsWith(remaining)) {
                    builder.suggest("off");
                }
                if (ctx.getSource().getSender().hasPermission(CosmeticsNodes.NICK_ADMIN)) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                            builder.suggest(online.getName());
                        }
                    }
                }
                return builder.buildFuture();
            }).executes(ctx -> forPlayer(ctx, player -> {
                String name = StringArgumentType.getString(ctx, "name");
                this.actions.tell(player, name.equalsIgnoreCase("off") ? this.actions.clearNick(player)
                    : this.actions.setNick(player, name, null));
            })).then(Commands.argument("nickname", StringArgumentType.word())
                .requires(CommandSupport.permission(CosmeticsNodes.NICK_ADMIN))
                .suggests((ctx, builder) -> builder.suggest("off").buildFuture())
                .executes(ctx -> {
                    staffNick(ctx);
                    return CommandSupport.OK;
                })));
    }

    /** {@code /nick <player> <name|off>}: staff set or clear anyone's nickname (the rules still apply). */
    private void staffNick(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "name");
        if (target.isEmpty()) {
            return;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        String value = StringArgumentType.getString(ctx, "nickname");
        Player online = Bukkit.getPlayer(uuid);
        Profile profile = this.cosmetics.profiles().get(uuid);
        if (value.equalsIgnoreCase("off")) {
            if (profile.nick() == null) {
                this.services.messenger().send(sender, CosmeticsMessages.NICK_ADMIN_NONE, Arg.text("name", name));
                return;
            }
            this.cosmetics.claimNick(uuid, null, null, actor(sender));
            this.services.audit().record(actor(sender), "cosmetics.nick", uuid.toString(), "removed '" + profile.nick() + "'");
            this.services.messenger().send(sender, CosmeticsMessages.NICK_ADMIN_REMOVED, Arg.text("name", name));
            if (online != null) {
                this.services.messenger().send(online, CosmeticsMessages.NICK_STAFF_REMOVED);
            }
            return;
        }
        CosmeticsActions.Outcome refused = this.actions.checkNick(uuid, value);
        if (refused == null && !this.cosmetics.claimNick(uuid, value, profile.nickStyle(), actor(sender))) {
            refused = CosmeticsActions.Outcome.refused(CosmeticsMessages.NICK_TAKEN);
        }
        if (refused != null) {
            this.services.messenger().send(sender, CosmeticsMessages.NICK_ADMIN_REFUSED,
                Arg.component("reason", this.lang.get(refused.key(), refused.args())));
            return;
        }
        this.services.audit().record(actor(sender), "cosmetics.nick", uuid.toString(),
            "set '" + value + "'" + (profile.nick() == null ? "" : ", was '" + profile.nick() + "'"));
        Component shown = profile.nickStyle().apply(value);
        this.services.messenger().send(sender, CosmeticsMessages.NICK_ADMIN_SET, Arg.text("name", name), Arg.component("nick", shown));
        if (online != null) {
            this.services.messenger().send(online, CosmeticsMessages.NICK_STAFF_SET, Arg.component("nick", shown));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> realnameTree(String label) {
        return Commands.literal(label)
            .requires(CommandSupport.permission(CosmeticsNodes.REALNAME))
            .then(Commands.argument("nickname", StringArgumentType.word()).executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                String nick = StringArgumentType.getString(ctx, "nickname");
                Optional<UUID> owner = this.cosmetics.nickHolder(nick);
                if (owner.isEmpty()) {
                    this.services.messenger().send(sender, CosmeticsMessages.REALNAME_NONE, Arg.text("nick", nick));
                    return CommandSupport.OK;
                }
                Profile profile = this.cosmetics.profiles().get(owner.get());
                this.services.messenger().send(sender, CosmeticsMessages.REALNAME,
                    Arg.component("nick", profile.nickStyle().apply(profile.nick() == null ? nick : profile.nick())),
                    Arg.text("name", this.services.directory().name(owner.get())));
                return CommandSupport.OK;
            }));
    }

    // ------------------------------------------------------------------ /tags

    private LiteralArgumentBuilder<CommandSourceStack> tagsTree(String label) {
        return Commands.literal(label)
            .requires(CommandSupport.playerPermission(CosmeticsNodes.TAGS))
            .executes(ctx -> forPlayer(ctx, player -> this.dialogs.tags(player, 0)))
            .then(Commands.literal("off").executes(ctx -> forPlayer(ctx, player -> this.actions.tell(player, this.actions.clearTag(player)))))
            .then(Commands.argument("tag", StringArgumentType.word()).suggests((ctx, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                if (ctx.getSource().getSender() instanceof Player player) {
                    for (ChatTag tag : this.cosmetics.settings().tags().values()) {
                        if (tag.id().startsWith(remaining) && this.cosmetics.usable(player, tag)) {
                            builder.suggest(tag.id());
                        }
                    }
                }
                return builder.buildFuture();
            }).executes(ctx -> forPlayer(ctx, player -> this.actions.tell(player,
                this.actions.setTag(player, StringArgumentType.getString(ctx, "tag").toLowerCase(Locale.ROOT))))));
    }

    // ------------------------------------------------------------------ /joinmessage and /leavemessage

    private LiteralArgumentBuilder<CommandSourceStack> messageTree(String label, boolean join) {
        return Commands.literal(label)
            .requires(CommandSupport.playerPermission(CosmeticsNodes.JOIN_CUSTOM))
            .executes(ctx -> forPlayer(ctx, this.dialogs::joinMessages))
            .then(Commands.literal("set").then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> forPlayer(ctx,
                player -> this.actions.tell(player, this.actions.setMessage(player, join, StringArgumentType.getString(ctx, "message")))))))
            .then(Commands.literal("reset").executes(ctx -> forPlayer(ctx,
                player -> this.actions.tell(player, this.actions.setMessage(player, join, null)))))
            .then(Commands.literal("preview").executes(ctx -> forPlayer(ctx, player -> {
                CosmeticsActions.Outcome blocked = this.actions.customBlocked(player);
                if (blocked != null) {
                    this.actions.tell(player, blocked);
                } else {
                    this.actions.preview(player);
                }
            })));
    }

    // ------------------------------------------------------------------ /killeffect

    private LiteralArgumentBuilder<CommandSourceStack> killEffectTree(String label) {
        return Commands.literal(label)
            .requires(CommandSupport.playerPermission(CosmeticsNodes.KILL_EFFECT))
            .executes(ctx -> forPlayer(ctx, this.dialogs::killEffects))
            .then(Commands.literal("off").executes(ctx -> forPlayer(ctx, player -> this.actions.tell(player, this.actions.setKillEffect(player, null)))))
            .then(Commands.argument("effect", StringArgumentType.word()).suggests((ctx, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                if (ctx.getSource().getSender() instanceof Player player) {
                    for (KillEffect effect : this.cosmetics.settings().killEffects().effects()) {
                        if (effect.id().startsWith(remaining) && this.cosmetics.usable(player, effect)) {
                            builder.suggest(effect.id());
                        }
                    }
                }
                return builder.buildFuture();
            }).executes(ctx -> forPlayer(ctx, player -> {
                String id = StringArgumentType.getString(ctx, "effect");
                KillEffect effect = KillEffect.byId(id);
                this.actions.tell(player, effect == null
                    ? CosmeticsActions.Outcome.refused(CosmeticsMessages.KILL_UNKNOWN, Arg.text("id", id))
                    : this.actions.setKillEffect(player, effect));
            })));
    }
}
