package net.siftvanilla.siftcore.feature.scoreboard;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /sidebar} (alias {@code /sb}): players show or hide their sidebar; staff refresh everything at once, read
 * the status and preview a player's lines. The staff parts work from the console.
 */
final class ScoreboardCommands {

    static final String USE = "siftcore.command.sidebar";
    static final String ADMIN = "siftcore.admin.scoreboard";

    private final Services services;
    private final Setting<ScoreboardSettings> settings;
    private final Toggle toggle;
    private final Boards boards;

    ScoreboardCommands(Services services, Setting<ScoreboardSettings> settings, Toggle toggle, Boards boards) {
        this.services = services;
        this.settings = settings;
        this.toggle = toggle;
        this.boards = boards;
    }

    List<SiftCommand> all() {
        return List.of(new SimpleCommand("sidebar", List.of("sb"), "Shows or hides your sidebar", USE,
            label -> Commands.literal(label)
                .requires(source -> CommandSupport.playerPermission(USE).test(source) || CommandSupport.permission(ADMIN).test(source))
                .executes(ctx -> switchSidebar(ctx, null))
                .then(Commands.literal("on").requires(CommandSupport.playerPermission(USE)).executes(ctx -> switchSidebar(ctx, true)))
                .then(Commands.literal("off").requires(CommandSupport.playerPermission(USE)).executes(ctx -> switchSidebar(ctx, false)))
                .then(Commands.literal("refresh").requires(CommandSupport.permission(ADMIN)).executes(this::refresh))
                .then(Commands.literal("status").requires(CommandSupport.permission(ADMIN)).executes(this::status))
                .then(Commands.literal("preview").requires(CommandSupport.permission(ADMIN))
                    .then(CommandSupport.onlinePlayer("player").executes(this::preview)))));
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    /** Turns the player's sidebar on, off, or to the other state; applied at once. */
    private int switchSidebar(CommandContext<CommandSourceStack> ctx, Boolean wanted) {
        Player player = this.services.commands().player(ctx);
        if (player == null) {
            return CommandSupport.OK;
        }
        ScoreboardSettings.Yielded yielded = this.boards.yielded();
        if (yielded.sidebar() != null && this.settings.get().sidebarEnabled()) {
            messenger().send(player, ScoreboardMessages.YIELDED, Arg.text("plugin", yielded.sidebar()));
            return CommandSupport.OK;
        }
        if (!this.settings.get().sidebarEnabled()) {
            messenger().send(player, ScoreboardMessages.OFF_ON_SERVER);
            return CommandSupport.OK;
        }
        boolean now = this.services.settings().enabled(player.getUniqueId(), this.toggle);
        boolean on = wanted == null ? !now : wanted;
        if (on == now && wanted != null) {
            messenger().send(player, on ? ScoreboardMessages.ALREADY_SHOWN : ScoreboardMessages.ALREADY_HIDDEN);
            return CommandSupport.OK;
        }
        this.services.settings().set(player.getUniqueId(), this.toggle, on);
        this.services.scheduler().global(() -> this.boards.refreshPlayer(player.getUniqueId()));
        messenger().send(player, on ? ScoreboardMessages.SHOWN : ScoreboardMessages.HIDDEN);
        return CommandSupport.OK;
    }

    private int refresh(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = this.services.commands().sender(ctx);
        this.services.scheduler().global(() -> {
            int count = this.boards.forceRefresh();
            messenger().send(sender, ScoreboardMessages.REFRESHED, Arg.number("count", count));
        });
        return CommandSupport.OK;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = this.services.commands().sender(ctx);
        Boards.Status status = this.boards.status();
        messenger().chat(sender, ScoreboardMessages.STATUS,
            Arg.number("players", status.players()),
            Arg.number("sidebars", status.sidebars()),
            Arg.number("hidden", status.hidden()),
            Arg.number("teams", status.teams()),
            Arg.number("pending", status.pending()),
            Arg.text("time", milliseconds(status.refreshMicros())),
            Arg.text("yielded", yieldedText(this.boards.yielded())));
        return CommandSupport.OK;
    }

    /** "sidebar (TAB), tab list (TAB)" for the parts other plugins show, or "none". */
    private String yieldedText(ScoreboardSettings.Yielded yielded) {
        var lang = this.services.lang();
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        if (yielded.sidebar() != null) {
            parts.add(lang.plain(ScoreboardMessages.PART_YIELDED, Arg.text("part", lang.plain(ScoreboardMessages.PART_SIDEBAR)),
                Arg.text("plugin", yielded.sidebar())));
        }
        if (yielded.tab() != null) {
            parts.add(lang.plain(ScoreboardMessages.PART_YIELDED, Arg.text("part", lang.plain(ScoreboardMessages.PART_TAB)),
                Arg.text("plugin", yielded.tab())));
        }
        if (yielded.nametags() != null) {
            parts.add(lang.plain(ScoreboardMessages.PART_YIELDED, Arg.text("part", lang.plain(ScoreboardMessages.PART_NAMETAGS)),
                Arg.text("plugin", yielded.nametags())));
        }
        return parts.isEmpty() ? lang.plain(ScoreboardMessages.PARTS_NONE) : String.join(", ", parts);
    }

    /** Microseconds as milliseconds with two decimals ("0.35ms"). */
    static String milliseconds(long micros) {
        return java.math.BigDecimal.valueOf(micros, 3).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "ms";
    }

    private int preview(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = this.services.commands().sender(ctx);
        Player target = this.services.commands().online(ctx, "player");
        if (target == null) {
            return CommandSupport.OK;
        }
        String name = target.getName();
        boolean hidden = !this.services.settings().enabled(target.getUniqueId(), this.toggle) || !this.boards.current().sidebarEnabled();
        this.services.scheduler().global(() -> {
            List<Component> lines = this.boards.preview(target.getUniqueId());
            if (lines == null) {
                messenger().chat(sender, ScoreboardMessages.PREVIEW_WAIT, Arg.text("name", name));
                return;
            }
            messenger().chat(sender, ScoreboardMessages.PREVIEW_TITLE, Arg.text("name", name));
            if (hidden) {
                messenger().chat(sender, ScoreboardMessages.PREVIEW_HIDDEN, Arg.text("name", name));
            }
            if (lines.isEmpty()) {
                messenger().chat(sender, ScoreboardMessages.PREVIEW_EMPTY);
            }
            int number = 1;
            for (Component line : lines) {
                messenger().chat(sender, ScoreboardMessages.PREVIEW_LINE, Arg.number("number", number++), Arg.component("line", line));
            }
        });
        return CommandSupport.OK;
    }
}
