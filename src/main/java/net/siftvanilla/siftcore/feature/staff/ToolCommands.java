package net.siftvanilla.siftcore.feature.staff;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /vanish, /freeze, /staffchat, /broadcast, /clearchat, /invsee, /ecsee, /alts and /whois. */
final class ToolCommands {

    private final Services services;
    private final CommandSupport support;
    private final Messenger messenger;
    private final VanishService vanish;
    private final FreezeService freeze;
    private final StaffChat staffChat;
    private final Announcements announcements;
    private final Inspector inspector;
    private final Lookups lookups;
    private final StaffNotices notices;
    private final StaffText text;

    ToolCommands(Services services, VanishService vanish, FreezeService freeze, StaffChat staffChat, Announcements announcements,
                 Inspector inspector, Lookups lookups, StaffNotices notices, StaffText text) {
        this.services = services;
        this.support = services.commands();
        this.messenger = services.messenger();
        this.vanish = vanish;
        this.freeze = freeze;
        this.staffChat = staffChat;
        this.announcements = announcements;
        this.inspector = inspector;
        this.lookups = lookups;
        this.notices = notices;
        this.text = text;
    }

    List<SiftCommand> all() {
        return List.of(vanishCommand(), freezeCommand(), staffChatCommand(), broadcast(), clearChat(),
            inspect("invsee", InspectLayout.Kind.INVENTORY, "Looks into a player's inventory"),
            inspect("ecsee", InspectLayout.Kind.ENDER_CHEST, "Looks into a player's ender chest"),
            alts(), whois());
    }

    // ------------------------------------------------------------------ vanish

    private SiftCommand vanishCommand() {
        return new SimpleCommand("vanish", List.of("v"), "Hides you from players", StaffNodes.VANISH, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.VANISH))
            .executes(ctx -> {
                Player player = this.support.player(ctx);
                if (player != null) {
                    boolean now = !this.vanish.vanished(player.getUniqueId());
                    this.vanish.set(player.getUniqueId(), now, Actor.of(player));
                    this.messenger.send(player, now ? StaffMessages.VANISH_ON : StaffMessages.VANISH_OFF);
                }
                return CommandSupport.OK;
            })
            .then(StaffArgs.knownPlayer(this.services.directory(), "player")
                .requires(CommandSupport.permission(StaffNodes.VANISH_OTHERS))
                .executes(ctx -> {
                    Optional<UUID> target = this.support.known(ctx, "player");
                    target.ifPresent(uuid -> vanishOther(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })));
    }

    private void vanishOther(CommandSender sender, UUID target) {
        boolean now = !this.vanish.vanished(target);
        this.vanish.set(target, now, Actor.of(sender));
        Arg name = Arg.text("name", this.services.directory().name(target));
        this.messenger.chat(sender, now ? StaffMessages.VANISH_ON_OTHER : StaffMessages.VANISH_OFF_OTHER, name);
        Player online = Bukkit.getPlayer(target);
        if (online != null && !online.equals(sender)) {
            this.messenger.send(online, now ? StaffMessages.VANISH_ON : StaffMessages.VANISH_OFF);
        }
    }

    // ------------------------------------------------------------------ freeze

    private SiftCommand freezeCommand() {
        return new SimpleCommand("freeze", List.of(), "Freezes or unfreezes a player", StaffNodes.FREEZE, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.FREEZE))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                Optional<UUID> target = this.support.known(ctx, "player");
                if (target.isEmpty()) {
                    return CommandSupport.OK;
                }
                Actor actor = Actor.of(sender);
                if (actor.is(target.get())) {
                    this.messenger.send(sender, CoreMessages.NOT_YOURSELF);
                    return CommandSupport.OK;
                }
                boolean now = !this.freeze.frozen(target.get());
                this.freeze.set(target.get(), now, actor);
                Arg name = Arg.text("name", this.services.directory().name(target.get()));
                this.messenger.chat(sender, now ? StaffMessages.FREEZE_DONE : StaffMessages.UNFREEZE_DONE, name);
                this.notices.send(StaffNodes.NOTIFY, actor, now ? StaffMessages.NOTIFY_FREEZE : StaffMessages.NOTIFY_UNFREEZE,
                    Arg.text("staff", this.text.staff(actor)), name);
                return CommandSupport.OK;
            })));
    }

    // ------------------------------------------------------------------ staff chat

    private SiftCommand staffChatCommand() {
        return new SimpleCommand("staffchat", List.of("sc"), "Talks in staff chat, or switches your chat to it", StaffNodes.CHAT,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(StaffNodes.CHAT))
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        boolean on = this.staffChat.toggle(player.getUniqueId());
                        this.messenger.send(player, on ? StaffMessages.CHAT_ON : StaffMessages.CHAT_OFF);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    String message = CleanText.clean(StringArgumentType.getString(ctx, "message"));
                    if (!message.isEmpty()) {
                        this.staffChat.send(ctx.getSource().getSender(), message);
                    }
                    return CommandSupport.OK;
                })));
    }

    // ------------------------------------------------------------------ announcements

    private SiftCommand broadcast() {
        return new SimpleCommand("broadcast", List.of("bc"), "Announces a message to everyone", StaffNodes.BROADCAST,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(StaffNodes.BROADCAST))
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    this.announcements.broadcast(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "message"));
                    return CommandSupport.OK;
                })));
    }

    private SiftCommand clearChat() {
        return new SimpleCommand("clearchat", List.of(), "Clears everyone's chat", StaffNodes.CLEARCHAT, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.CLEARCHAT))
            .executes(ctx -> {
                this.announcements.clearChat(ctx.getSource().getSender());
                return CommandSupport.OK;
            }));
    }

    // ------------------------------------------------------------------ inspection

    private SiftCommand inspect(String name, InspectLayout.Kind kind, String description) {
        String node = Inspector.viewNode(kind);
        return new SimpleCommand(name, List.of(), description, node, label -> Commands.literal(label)
            .requires(CommandSupport.playerPermission(node))
            .then(CommandSupport.onlinePlayer("player").executes(ctx -> inspect(ctx, kind))));
    }

    private int inspect(CommandContext<CommandSourceStack> ctx, InspectLayout.Kind kind) {
        Player staff = this.support.player(ctx);
        if (staff == null) {
            return CommandSupport.OK;
        }
        Player target = this.support.online(ctx, "player");
        if (target == null) {
            return CommandSupport.OK;
        }
        if (target.equals(staff)) {
            this.messenger.send(staff, StaffMessages.INSPECT_SELF);
            return CommandSupport.OK;
        }
        this.inspector.open(staff, target, kind);
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ lookups

    private SiftCommand alts() {
        return new SimpleCommand("alts", List.of(), "Lists accounts that share a player's address", StaffNodes.ALTS,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(StaffNodes.ALTS))
                .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(ctx -> {
                    this.support.known(ctx, "player").ifPresent(uuid -> this.lookups.alts(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })));
    }

    private SiftCommand whois() {
        return new SimpleCommand("whois", List.of(), "Shows everything about a player", StaffNodes.WHOIS,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(StaffNodes.WHOIS))
                .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(ctx -> {
                    this.support.known(ctx, "player").ifPresent(uuid -> this.lookups.whois(ctx.getSource().getSender(), uuid));
                    return CommandSupport.OK;
                })));
    }
}
