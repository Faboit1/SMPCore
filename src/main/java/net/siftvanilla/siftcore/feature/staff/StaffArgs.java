package net.siftvanilla.siftcore.feature.staff;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Command arguments of the staff tools. */
final class StaffArgs {

    private StaffArgs() {
    }

    /**
     * A player-name word for anyone who ever joined. Suggests online players the sender can see (so a vanished staff
     * member never shows up as online, see {@link CommandSupport#canSee}), then known names once two letters are typed.
     */
    static RequiredArgumentBuilder<CommandSourceStack, String> knownPlayer(CommandSupport support, PlayerDirectory directory,
                                                                          String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            Set<String> suggested = new HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)
                    && support.canSee(sender, online) && suggested.add(online.getName().toLowerCase(Locale.ROOT))) {
                    builder.suggest(online.getName());
                }
            }
            if (remaining.length() >= 2 && suggested.size() < 20) {
                for (String known : directory.namesStartingWith(remaining, 20)) {
                    if (suggested.size() >= 20) {
                        break;
                    }
                    if (suggested.add(known.toLowerCase(Locale.ROOT))) {
                        builder.suggest(known);
                    }
                }
            }
            return builder.buildFuture();
        });
    }
}
