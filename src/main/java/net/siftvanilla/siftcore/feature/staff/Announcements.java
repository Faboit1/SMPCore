package net.siftvanilla.siftcore.feature.staff;

import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /broadcast} and {@code /clearchat}. Both only send packets, so they are safe from any thread. */
final class Announcements {

    private final Services services;
    private final Setting<StaffSettings> settings;

    Announcements(Services services, Setting<StaffSettings> settings) {
        this.services = services;
        this.settings = settings;
    }

    /** A plain line to everyone (no prefix) with the notify sound. The text is inserted literally. */
    void broadcast(CommandSender sender, String message) {
        String text = CleanText.clean(message);
        if (text.isEmpty()) {
            return;
        }
        this.services.messenger().broadcast(StaffMessages.BROADCAST, Arg.text("message", text));
        this.services.audit().record(Actor.of(sender).id(), "staff.broadcast", null, text);
    }

    /**
     * Pushes old messages off everyone's screen (one message of blank lines each), except players with the bypass
     * permission, then says the chat was cleared.
     */
    void clearChat(CommandSender sender) {
        Component blank = Component.text("\n".repeat(Math.max(0, this.settings.get().clearChatLines() - 1)));
        int cleared = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(StaffNodes.CLEARCHAT_BYPASS)) {
                player.sendMessage(blank);
                cleared++;
            }
        }
        this.services.messenger().broadcast(StaffMessages.CLEARCHAT_DONE);
        this.services.messenger().chat(sender, StaffMessages.CLEARCHAT_CONFIRM, Arg.number("count", cleared));
        this.services.audit().record(Actor.of(sender).id(), "staff.clearchat", null, cleared + " players");
    }
}
