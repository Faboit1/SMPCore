package net.siftvanilla.siftcore.feature.staff;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Staff chat: {@code /sc <message>} goes to everyone with {@code siftcore.staff.chat}; {@code /sc} alone switches a
 * staff member's chat to staff only until they switch back or leave. Safe from any thread (the chat thread uses it).
 */
final class StaffChat {

    private final Lang lang;
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();

    StaffChat(Lang lang) {
        this.lang = lang;
    }

    /** Switches staff chat mode; returns the new state. */
    boolean toggle(UUID player) {
        if (this.chatMode.remove(player)) {
            return false;
        }
        this.chatMode.add(player);
        return true;
    }

    boolean inChatMode(UUID player) {
        return this.chatMode.contains(player);
    }

    void forget(UUID player) {
        this.chatMode.remove(player);
    }

    /** Sends a staff chat line from {@code sender}; the message is inserted literally. */
    void send(CommandSender sender, String message) {
        String name = sender instanceof Player player ? player.getName() : this.lang.plain(StaffMessages.CONSOLE);
        Component line = this.lang.get(StaffMessages.CHAT_FORMAT, Arg.text("name", name), Arg.text("message", message));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(StaffNodes.CHAT)) {
                player.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }
}
