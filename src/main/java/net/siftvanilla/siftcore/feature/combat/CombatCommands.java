package net.siftvanilla.siftcore.feature.combat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /combat: your own combat state, and staff tools to inspect, tag and untag players and read the kill log. */
final class CombatCommands {

    static final String USE = "siftcore.command.combat";
    static final String ADMIN = "siftcore.admin.combat";

    private static final Duration MAX_STAFF_TAG = Duration.ofHours(1);
    private static final int KILLS_PAGE = 10;

    private final Services services;
    private final CommandSupport support;
    private final CombatTags tags;
    private final CombatTagger tagger;
    private final KillLog log;

    CombatCommands(Services services, CombatTags tags, CombatTagger tagger, KillLog log) {
        this.services = services;
        this.support = services.commands();
        this.tags = tags;
        this.tagger = tagger;
        this.log = log;
    }

    List<SiftCommand> all() {
        return List.of(new SimpleCommand("combat", List.of("combattag", "ct"), "Shows whether you are in combat", USE,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(USE))
                .executes(this::self)
                .then(Commands.literal("status").requires(CommandSupport.permission(ADMIN))
                    .then(this.support.knownPlayer("player").executes(this::status)))
                .then(Commands.literal("tag").requires(CommandSupport.permission(ADMIN))
                    .then(CommandSupport.onlinePlayer("player")
                        .executes(ctx -> tag(ctx, null))
                        .then(Commands.argument("time", StringArgumentType.word())
                            .executes(ctx -> tag(ctx, StringArgumentType.getString(ctx, "time"))))))
                .then(Commands.literal("untag").requires(CommandSupport.permission(ADMIN))
                    .then(CommandSupport.onlinePlayer("player").executes(this::untag)))
                .then(Commands.literal("kills").requires(CommandSupport.permission(ADMIN))
                    .then(this.support.knownPlayer("player")
                        .executes(ctx -> kills(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                            .executes(ctx -> kills(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))));
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private int self(CommandContext<CommandSourceStack> ctx) {
        Player player = this.support.player(ctx);
        if (player == null) {
            return CommandSupport.OK;
        }
        Duration left = this.tags.remaining(player.getUniqueId());
        if (left.isZero()) {
            messenger().send(player, CombatMessages.STATUS_CLEAR);
        } else {
            messenger().send(player, CombatMessages.STATUS_TAGGED, Arg.time("time", roundUp(left)));
        }
        return CommandSupport.OK;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.services.directory().name(target.get());
        CombatTags.Tag tag = this.tags.get(target.get());
        if (tag == null) {
            messenger().chat(sender, CombatMessages.ADMIN_STATUS_CLEAR, Arg.text("name", name));
            return CommandSupport.OK;
        }
        Duration left = roundUp(Duration.ofMillis(Math.max(0, tag.until() - System.currentTimeMillis())));
        if (tag.lastAttacker() == null) {
            messenger().chat(sender, CombatMessages.ADMIN_STATUS_TAGGED_NO_HIT, Arg.text("name", name), Arg.time("time", left));
        } else {
            messenger().chat(sender, CombatMessages.ADMIN_STATUS_TAGGED, Arg.text("name", name), Arg.time("time", left),
                Arg.text("attacker", this.services.directory().name(tag.lastAttacker())));
        }
        return CommandSupport.OK;
    }

    private int tag(CommandContext<CommandSourceStack> ctx, String time) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = this.support.online(ctx, "player");
        if (target == null) {
            return CommandSupport.OK;
        }
        Duration duration;
        if (time == null) {
            duration = null;
        } else {
            try {
                duration = Durations.parse(time);
            } catch (IllegalArgumentException | ArithmeticException e) {
                duration = Duration.ZERO;
            }
            if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(MAX_STAFF_TAG) > 0) {
                messenger().chat(sender, CombatMessages.ADMIN_INVALID_TIME, Arg.text("input", time), Arg.time("max", MAX_STAFF_TAG));
                return CommandSupport.OK;
            }
        }
        Duration length = duration;
        this.services.scheduler().entity(target, () -> {
            Duration applied = length == null ? this.tagger.defaultDuration() : length;
            this.tagger.tagByStaff(target, applied);
            messenger().chat(sender, CombatMessages.ADMIN_TAGGED, Arg.text("name", target.getName()), Arg.time("time", applied));
            this.services.audit().record(actor(sender), "combat.tag", target.getUniqueId().toString(), applied.toMillis() + "ms");
        }, () -> messenger().send(sender, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", target.getName())));
        return CommandSupport.OK;
    }

    private int untag(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = this.support.online(ctx, "player");
        if (target == null) {
            return CommandSupport.OK;
        }
        if (!this.tags.tagged(target.getUniqueId())) {
            messenger().chat(sender, CombatMessages.ADMIN_STATUS_CLEAR, Arg.text("name", target.getName()));
            return CommandSupport.OK;
        }
        this.tags.untag(target.getUniqueId());
        messenger().chat(sender, CombatMessages.ADMIN_UNTAGGED, Arg.text("name", target.getName()));
        this.services.audit().record(actor(sender), "combat.untag", target.getUniqueId().toString(), null);
        return CommandSupport.OK;
    }

    private int kills(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> target = this.support.known(ctx, "player");
        if (target.isEmpty()) {
            return CommandSupport.OK;
        }
        var directory = this.services.directory();
        String name = directory.name(target.get());
        this.log.involving(target.get(), KILLS_PAGE, (page - 1) * KILLS_PAGE).whenComplete((rows, error) -> {
            if (error != null) {
                messenger().chat(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            messenger().chat(sender, CombatMessages.ADMIN_KILLS_HEADER, Arg.text("name", name), Arg.number("page", page));
            if (rows.isEmpty()) {
                messenger().chat(sender, CombatMessages.ADMIN_KILLS_EMPTY);
            }
            long now = System.currentTimeMillis();
            var lang = this.services.lang();
            for (KillLog.Row row : rows) {
                Arg ago = Arg.time("ago", Duration.ofMillis(Math.max(0, now - row.at())));
                Arg killer = Arg.text("killer", directory.name(row.killer()));
                Arg victim = Arg.text("victim", directory.name(row.victim()));
                if (row.counted()) {
                    messenger().chat(sender, CombatMessages.ADMIN_KILLS_COUNTED, ago, killer, victim);
                    continue;
                }
                AntiFarm.Reason reason = AntiFarm.Reason.byId(row.reason());
                String text = reason == null ? String.valueOf(row.reason()) : lang.plain(CombatMessages.reason(reason));
                messenger().chat(sender, CombatMessages.ADMIN_KILLS_NOT_COUNTED, ago, killer, victim, Arg.text("reason", text));
            }
        });
        return CommandSupport.OK;
    }

    /** Whole seconds, rounded up, so a running timer never reads 0s. */
    private static Duration roundUp(Duration left) {
        return Duration.ofSeconds(TagTicker.secondsLeft(left.toMillis(), 0));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }
}
