package net.siftvanilla.siftcore.feature.teams;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Team chat: {@code /tc <message>} sends one message, and team chat mode ({@code /team chat}) routes everything a
 * player types to their team instead of public chat. Messages reach online members, staff with
 * {@value #SPY_PERMISSION} who have spying on, and the console when configured. A muted player can't use it.
 * <p>
 * Chat events arrive on an async chat thread; everything read here is thread-safe (the registry's immutable
 * snapshots, a concurrent set, packet sends). The listener runs early (low priority) so a team message leaves public
 * chat before formatting, broadcast or relay plugins handle it, and it skips events an earlier listener already
 * cancelled (anti-spam, mutes), so those stop team chat too.
 */
final class TeamChat implements Listener {

    static final String SPY_PERMISSION = "siftcore.teams.spy";

    private final TeamRegistry registry;
    private final Messenger messenger;
    private final Lang lang;
    private final PlayerSettings playerSettings;
    private final Toggle spyToggle;
    private final Setting<TeamsSettings> settings;
    private final MuteStatus mutes;
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();

    TeamChat(TeamRegistry registry, Messenger messenger, PlayerSettings playerSettings, Toggle spyToggle,
             Setting<TeamsSettings> settings, MuteStatus mutes) {
        this.registry = registry;
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.playerSettings = playerSettings;
        this.spyToggle = spyToggle;
        this.settings = settings;
        this.mutes = mutes;
    }

    /** Whether the player's typed chat goes to their team. */
    boolean inChatMode(UUID player) {
        return this.chatMode.contains(player);
    }

    /** Switches team chat mode; returns the new state. */
    boolean toggle(UUID player) {
        if (this.chatMode.remove(player)) {
            return false;
        }
        this.chatMode.add(player);
        return true;
    }

    /** Turns team chat mode off (left the team, quit). Returns true if it was on. */
    boolean off(UUID player) {
        return this.chatMode.remove(player);
    }

    /** Sends one message from {@code sender} to their team, unless they are muted. Safe from any thread. */
    void send(Player sender, Team team, String rawMessage) {
        Optional<MuteStatus.Mute> mute = this.mutes.mute(sender.getUniqueId());
        if (mute.isPresent()) {
            if (mute.get().permanent()) {
                this.messenger.send(sender, TeamsMessages.CHAT_MUTED_PERMANENT);
            } else {
                this.messenger.send(sender, TeamsMessages.CHAT_MUTED,
                    Arg.time("time", Duration.ofMillis(Math.max(0, mute.get().until() - System.currentTimeMillis()))));
            }
            return;
        }
        String message = clean(rawMessage);
        if (message.isEmpty()) {
            return;
        }
        Component line = this.lang.get(TeamsMessages.CHAT_FORMAT, Arg.text("name", sender.getName()), Arg.text("message", message));
        Component spyLine = this.lang.get(TeamsMessages.CHAT_SPY_FORMAT, Arg.text("team", team.name()),
            Arg.text("name", sender.getName()), Arg.text("message", message));
        for (UUID member : team.memberIds()) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                online.sendMessage(line);
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!team.isMember(online.getUniqueId()) && online.hasPermission(SPY_PERMISSION)
                && this.playerSettings.enabled(online.getUniqueId(), this.spyToggle)) {
                online.sendMessage(spyLine);
            }
        }
        if (this.settings.get().chatToConsole()) {
            Bukkit.getConsoleSender().sendMessage(spyLine);
        }
    }

    /**
     * Strips what the client should never send but a command could carry: control and formatting characters and
     * the legacy section sign, which the client would render as colours.
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != '§') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!this.chatMode.contains(id)) {
            return;
        }
        // Out of public chat: cancelled, and without viewers in case a later listener un-cancels it.
        event.setCancelled(true);
        event.viewers().clear();
        Team team = this.registry.of(id).orElse(null);
        if (team == null) {
            this.chatMode.remove(id);
            this.messenger.send(player, TeamsMessages.CHAT_NO_TEAM);
            return;
        }
        send(player, team, TextStyle.plain(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.chatMode.remove(event.getPlayer().getUniqueId());
    }
}
