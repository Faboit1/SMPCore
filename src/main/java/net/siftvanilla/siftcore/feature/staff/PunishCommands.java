package net.siftvanilla.siftcore.feature.staff;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * /ban, /tempban, /unban, /kick, /warn, /mute, /unmute and /history. All work from the console. Brigadier lets
 * plugin commands replace vanilla ones, so /ban and /kick are SiftCore's; vanilla's stay reachable as
 * /minecraft:ban and /minecraft:kick. Bans, kicks, warnings and mutes follow the staff hierarchy ({@link StaffHierarchy}):
 * nobody punishes staff of the same or a higher weight, or the owner; lifting a ban or mute is never refused.
 */
final class PunishCommands {

    private static final String TIME_AND_REASON = "time-and-reason";

    private final Services services;
    private final CommandSupport support;
    private final Messenger messenger;
    private final Punishments punishments;
    private final HistoryView history;
    private final StaffNotices notices;
    private final StaffText text;
    private final Setting<StaffSettings> settings;
    private final StaffHierarchy hierarchy;

    PunishCommands(Services services, Punishments punishments, HistoryView history, StaffNotices notices, StaffText text,
                   Setting<StaffSettings> settings, StaffHierarchy hierarchy) {
        this.services = services;
        this.support = services.commands();
        this.messenger = services.messenger();
        this.punishments = punishments;
        this.history = history;
        this.notices = notices;
        this.text = text;
        this.settings = settings;
        this.hierarchy = hierarchy;
    }

    List<SiftCommand> all() {
        return List.of(ban(), tempban(), unban(), kick(), warn(), mute(), unmute(), historyCommand());
    }

    // ------------------------------------------------------------------ trees

    private SiftCommand ban() {
        return new SimpleCommand("ban", List.of(), "Bans a player permanently", StaffNodes.BAN, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.BAN))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player")
                .executes(ctx -> ban(ctx, null, ""))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> ban(ctx, null, StringArgumentType.getString(ctx, "reason"))))));
    }

    private SiftCommand tempban() {
        return new SimpleCommand("tempban", List.of(), "Bans a player for a while", StaffNodes.TEMPBAN, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.TEMPBAN))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player")
                .executes(ctx -> {
                    this.messenger.send(ctx.getSource().getSender(), StaffMessages.DURATION_MISSING);
                    return CommandSupport.OK;
                })
                .then(Commands.argument(TIME_AND_REASON, StringArgumentType.greedyString())
                    .executes(ctx -> ban(ctx, StringArgumentType.getString(ctx, TIME_AND_REASON), null)))));
    }

    private SiftCommand unban() {
        return new SimpleCommand("unban", List.of("pardon"), "Lifts a player's ban", StaffNodes.UNBAN, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.UNBAN))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(this::unban)));
    }

    private SiftCommand kick() {
        return new SimpleCommand("kick", List.of(), "Removes a player from the server", StaffNodes.KICK, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.KICK))
            .then(CommandSupport.onlinePlayer("player")
                .executes(ctx -> kick(ctx, ""))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> kick(ctx, StringArgumentType.getString(ctx, "reason"))))));
    }

    private SiftCommand warn() {
        return new SimpleCommand("warn", List.of(), "Warns a player", StaffNodes.WARN, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.WARN))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player")
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> warn(ctx, StringArgumentType.getString(ctx, "reason"))))));
    }

    private SiftCommand mute() {
        return new SimpleCommand("mute", List.of(), "Mutes a player, for a while or for good", StaffNodes.MUTE, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.MUTE))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player")
                .executes(ctx -> mute(ctx, ""))
                .then(Commands.argument(TIME_AND_REASON, StringArgumentType.greedyString())
                    .executes(ctx -> mute(ctx, StringArgumentType.getString(ctx, TIME_AND_REASON))))));
    }

    private SiftCommand unmute() {
        return new SimpleCommand("unmute", List.of(), "Lifts a player's mute", StaffNodes.MUTE, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.MUTE))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(this::unmute)));
    }

    private SiftCommand historyCommand() {
        return new SimpleCommand("history", List.of(), "Shows a player's punishments", StaffNodes.HISTORY, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.HISTORY))
            .then(StaffArgs.knownPlayer(this.services.directory(), "player").executes(ctx -> {
                Optional<UUID> target = this.support.known(ctx, "player");
                target.ifPresent(uuid -> {
                    CommandSender sender = ctx.getSource().getSender();
                    this.services.audit().record(Actor.of(sender).id(), "staff.history", uuid.toString(), null);
                    this.history.show(sender, uuid, this.services.directory().name(uuid));
                });
                return CommandSupport.OK;
            })));
    }

    // ------------------------------------------------------------------ actions

    /** /ban (timeAndReason null) and /tempban (reason null). */
    private int ban(CommandContext<CommandSourceStack> ctx, String timeAndReason, String reasonOnly) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = target(ctx);
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        DurationInput.Parsed parsed = timeAndReason == null
            ? DurationInput.reasonOnly(reasonOnly)
            : DurationInput.requiredLength(timeAndReason, this.settings.get().maxLength());
        if (refused(sender, parsed, tooLong(timeAndReason != null))) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        this.hierarchy.guard(sender, uuid, name, "ban", () -> ban(sender, uuid, name, parsed));
        return CommandSupport.OK;
    }

    private void ban(CommandSender sender, UUID uuid, String name, DurationInput.Parsed parsed) {
        Actor actor = Actor.of(sender);
        Punishments.Issued issued = this.punishments.issue(PunishmentType.BAN, uuid, name, actor, parsed.reason(), parsed.length(), true);
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            onPlayerThread(online, () -> this.punishments.removeBanned(online, issued.punishment()));
        }
        Arg nameArg = Arg.text("name", name);
        Arg reason = Arg.text("reason", this.text.reason(parsed.reason()));
        Arg staff = Arg.text("staff", this.text.staff(actor));
        if (parsed.permanent()) {
            this.messenger.chat(sender, StaffMessages.BAN_DONE_PERMANENT, nameArg);
            this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_BAN_PERMANENT, staff, nameArg, reason);
        } else {
            this.messenger.chat(sender, StaffMessages.BAN_DONE, nameArg, Arg.time("time", parsed.length()));
            this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_BAN, staff, nameArg, Arg.time("time", parsed.length()), reason);
        }
    }

    private int unban(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        Actor actor = Actor.of(sender);
        Arg name = Arg.text("name", this.services.directory().name(target.get()));
        if (this.punishments.lift(PunishmentType.BAN, target.get(), actor).isEmpty()) {
            this.messenger.send(sender, StaffMessages.NOT_BANNED, name);
            return CommandSupport.OK;
        }
        this.messenger.chat(sender, StaffMessages.UNBAN_DONE, name);
        this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_UNBAN, Arg.text("staff", this.text.staff(actor)), name);
        return CommandSupport.OK;
    }

    private int kick(CommandContext<CommandSourceStack> ctx, String reasonText) {
        CommandSender sender = ctx.getSource().getSender();
        Player online = this.support.online(ctx, "player");
        if (online == null) {
            return CommandSupport.OK;
        }
        Actor actor = Actor.of(sender);
        if (actor.is(online.getUniqueId())) {
            this.messenger.send(sender, CoreMessages.NOT_YOURSELF);
            return CommandSupport.OK;
        }
        DurationInput.Parsed parsed = DurationInput.reasonOnly(reasonText);
        if (refused(sender, parsed)) {
            return CommandSupport.OK;
        }
        this.hierarchy.guard(sender, online.getUniqueId(), online.getName(), "kick", () -> {
            this.punishments.issue(PunishmentType.KICK, online.getUniqueId(), online.getName(), actor, parsed.reason(), null, true);
            Component screen = this.punishments.kickScreen(parsed.reason());
            onPlayerThread(online, () -> online.kick(screen, PlayerKickEvent.Cause.KICKED));
            Arg name = Arg.text("name", online.getName());
            this.messenger.chat(sender, StaffMessages.KICK_DONE, name);
            this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_KICK, Arg.text("staff", this.text.staff(actor)), name,
                Arg.text("reason", this.text.reason(parsed.reason())));
        });
        return CommandSupport.OK;
    }

    private int warn(CommandContext<CommandSourceStack> ctx, String reasonText) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = target(ctx);
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        DurationInput.Parsed parsed = DurationInput.reasonOnly(reasonText);
        if (refused(sender, parsed)) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        this.hierarchy.guard(sender, uuid, name, "warn", () -> warn(sender, uuid, name, parsed));
        return CommandSupport.OK;
    }

    private void warn(CommandSender sender, UUID uuid, String name, DurationInput.Parsed parsed) {
        Actor actor = Actor.of(sender);
        Player online = Bukkit.getPlayer(uuid);
        this.punishments.issue(PunishmentType.WARN, uuid, name, actor, parsed.reason(), null, online != null);
        Arg reason = Arg.text("reason", this.text.reason(parsed.reason()));
        if (online != null) {
            this.messenger.send(online, StaffMessages.WARN_TARGET, reason);
            this.messenger.chat(sender, StaffMessages.WARN_DONE, Arg.text("name", name));
        } else {
            this.messenger.chat(sender, StaffMessages.WARN_DONE_OFFLINE, Arg.text("name", name));
        }
        this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_WARN, Arg.text("staff", this.text.staff(actor)),
            Arg.text("name", name), reason);
    }

    private int mute(CommandContext<CommandSourceStack> ctx, String timeAndReason) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = target(ctx);
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        DurationInput.Parsed parsed = DurationInput.optionalLength(timeAndReason, this.settings.get().maxLength());
        if (refused(sender, parsed)) {
            return CommandSupport.OK;
        }
        UUID uuid = target.get();
        String name = this.services.directory().name(uuid);
        this.hierarchy.guard(sender, uuid, name, "mute", () -> mute(sender, uuid, name, parsed));
        return CommandSupport.OK;
    }

    private void mute(CommandSender sender, UUID uuid, String name, DurationInput.Parsed parsed) {
        Actor actor = Actor.of(sender);
        this.punishments.issue(PunishmentType.MUTE, uuid, name, actor, parsed.reason(), parsed.length(), true);
        Arg nameArg = Arg.text("name", name);
        Arg reason = Arg.text("reason", this.text.reason(parsed.reason()));
        Arg staff = Arg.text("staff", this.text.staff(actor));
        Player online = Bukkit.getPlayer(uuid);
        if (parsed.permanent()) {
            if (online != null) {
                this.messenger.send(online, StaffMessages.MUTE_TARGET_PERMANENT, reason);
            }
            this.messenger.chat(sender, StaffMessages.MUTE_DONE_PERMANENT, nameArg);
            this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_MUTE_PERMANENT, staff, nameArg, reason);
        } else {
            Arg time = Arg.time("time", parsed.length());
            if (online != null) {
                this.messenger.send(online, StaffMessages.MUTE_TARGET, time, reason);
            }
            this.messenger.chat(sender, StaffMessages.MUTE_DONE, nameArg, time);
            this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_MUTE, staff, nameArg, time, reason);
        }
    }

    private int unmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        Actor actor = Actor.of(sender);
        Arg name = Arg.text("name", this.services.directory().name(target.get()));
        if (this.punishments.lift(PunishmentType.MUTE, target.get(), actor).isEmpty()) {
            this.messenger.send(sender, StaffMessages.NOT_MUTED, name);
            return CommandSupport.OK;
        }
        Player online = Bukkit.getPlayer(target.get());
        if (online != null) {
            this.messenger.send(online, StaffMessages.UNMUTE_TARGET);
        }
        this.messenger.chat(sender, StaffMessages.UNMUTE_DONE, name);
        this.notices.send(StaffNodes.NOTIFY, actor, StaffMessages.NOTIFY_UNMUTE, Arg.text("staff", this.text.staff(actor)), name);
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs a kick on the player's own thread. From any other thread the server only drops the connection, without the
     * kick event and with "disconnected" instead of "kicked" as the quit reason.
     */
    private void onPlayerThread(Player player, Runnable action) {
        if (this.services.scheduler().owns(player)) {
            action.run();
        } else {
            this.services.scheduler().entity(player, action, null);
        }
    }

    /** The target player (anyone who ever joined), refusing the sender themselves. */
    private Optional<UUID> target(CommandContext<CommandSourceStack> ctx) {
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isPresent() && Actor.of(ctx.getSource().getSender()).is(target.get())) {
            this.messenger.send(ctx.getSource().getSender(), CoreMessages.NOT_YOURSELF);
            return Optional.empty();
        }
        return target;
    }

    /**
     * What a time over the longest allowed says: a mute without a time is permanent, but /tempban always needs one
     * (a permanent ban is /ban).
     */
    static MessageKey tooLong(boolean temporaryBan) {
        return temporaryBan ? StaffMessages.DURATION_TOO_LONG_BAN : StaffMessages.DURATION_TOO_LONG;
    }

    /** Tells the sender what is wrong with the time or reason; returns true when the input can't be used. */
    private boolean refused(CommandSender sender, DurationInput.Parsed parsed) {
        return refused(sender, parsed, tooLong(false));
    }

    private boolean refused(CommandSender sender, DurationInput.Parsed parsed, MessageKey tooLong) {
        Duration max = this.settings.get().maxLength();
        switch (parsed.problem()) {
            case NONE -> {
                return false;
            }
            case MISSING -> this.messenger.send(sender, StaffMessages.DURATION_MISSING);
            case INVALID -> this.messenger.send(sender, StaffMessages.DURATION_INVALID, Arg.text("input", parsed.token()));
            case TOO_SHORT -> this.messenger.send(sender, StaffMessages.DURATION_TOO_SHORT);
            case TOO_LONG -> this.messenger.send(sender, tooLong, Arg.time("max", max));
            case REASON_TOO_LONG -> this.messenger.send(sender, StaffMessages.REASON_TOO_LONG, Arg.number("max", DurationInput.MAX_REASON));
        }
        return true;
    }
}
