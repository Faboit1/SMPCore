package net.siftvanilla.siftcore.feature.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Public chat and chat commands: messages of staff in staff chat mode go to staff only, and muted players can't
 * talk. Runs at {@link EventPriority#LOW} and only cancels, so the chat feature (later priority, skips cancelled
 * events) never renders those messages.
 */
final class ChatGuard implements Listener {

    private final StaffChat staffChat;
    private final Punishments punishments;
    private final Setting<StaffSettings> settings;

    ChatGuard(StaffChat staffChat, Punishments punishments, Setting<StaffSettings> settings) {
        this.staffChat = staffChat;
        this.punishments = punishments;
        this.settings = settings;
    }

    /** Runs on the async chat thread; everything it reads is thread-safe. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (this.staffChat.inChatMode(id)) {
            if (player.hasPermission(StaffNodes.CHAT)) {
                event.setCancelled(true);
                this.staffChat.send(player, PlainTextComponentSerializer.plainText().serialize(event.message()));
                return;
            }
            this.staffChat.forget(id);
        }
        this.punishments.activeMute(id).ifPresent(mute -> {
            event.setCancelled(true);
            this.punishments.tellMuted(player, mute);
        });
    }

    /** Muted players can't use the configured chat commands (/me, /msg, ...). Runs on the player's thread. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Set<String> blocked = this.settings.get().muteBlockedCommands();
        if (blocked.isEmpty() || !CommandLabels.matches(event.getMessage(), blocked)) {
            return;
        }
        this.punishments.activeMute(event.getPlayer().getUniqueId()).ifPresent(mute -> {
            event.setCancelled(true);
            this.punishments.tellMuted(event.getPlayer(), mute);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.staffChat.forget(event.getPlayer().getUniqueId());
    }
}
