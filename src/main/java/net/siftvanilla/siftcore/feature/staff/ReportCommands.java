package net.siftvanilla.siftcore.feature.staff;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import org.bukkit.entity.Player;

/** /report (everyone) and /reports (staff). */
final class ReportCommands {

    private final Services services;
    private final CommandSupport support;
    private final Reports reports;
    private final ReportDialogs dialogs;

    ReportCommands(Services services, Reports reports, ReportDialogs dialogs) {
        this.services = services;
        this.support = services.commands();
        this.reports = reports;
        this.dialogs = dialogs;
    }

    List<SiftCommand> all() {
        return List.of(report(), reportsCommand());
    }

    private SiftCommand report() {
        return new SimpleCommand("report", List.of(), "Reports a player to staff", StaffNodes.REPORT, label -> Commands.literal(label)
            .requires(CommandSupport.playerPermission(StaffNodes.REPORT))
            .executes(ctx -> {
                Player player = this.support.player(ctx);
                if (player != null) {
                    this.dialogs.openForm(player, "", "");
                }
                return CommandSupport.OK;
            })
            .then(StaffArgs.knownPlayer(this.services.commands(), this.services.directory(), "player")
                .executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player != null) {
                        this.dialogs.openForm(player, StringArgumentType.getString(ctx, "player"), "");
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> {
                    Player player = this.support.player(ctx);
                    if (player == null) {
                        return CommandSupport.OK;
                    }
                    Optional<UUID> target = this.support.known(ctx, "player");
                    if (target.isEmpty()) {
                        return CommandSupport.OK;
                    }
                    Reports.Refusal refusal = this.reports.submit(player, target.get(), this.services.directory().name(target.get()),
                        StringArgumentType.getString(ctx, "reason"));
                    if (refusal != null) {
                        this.services.messenger().send(player, refusal.key(), refusal.args());
                    }
                    return CommandSupport.OK;
                }))));
    }

    private SiftCommand reportsCommand() {
        return new SimpleCommand("reports", List.of(), "Shows open player reports", StaffNodes.REPORTS, label -> Commands.literal(label)
            .requires(CommandSupport.permission(StaffNodes.REPORTS))
            .executes(ctx -> {
                if (ctx.getSource().getSender() instanceof Player player) {
                    this.dialogs.openList(player);
                } else {
                    this.dialogs.print(ctx.getSource().getSender());
                }
                return CommandSupport.OK;
            })
            .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
                Player player = this.support.player(ctx);
                if (player != null) {
                    this.dialogs.openDetail(player, LongArgumentType.getLong(ctx, "id"));
                }
                return CommandSupport.OK;
            })));
    }
}
