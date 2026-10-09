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
import net.siftvanilla.siftcore.core.player.SharedSettings;
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
import org.bukkit.event.player.PlayerJoinEvent;
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
    /**
     * Where team chat mode is remembered for {@code team-chat-sticky}: the membership it was on in ({@link #membership}:
     * the team's id and when the player joined it), or {@link #MODE_OFF}. A free-form per-player value (not a setting),
     * kept as it really is: written when the mode changes and when the player leaves. A value from another membership
     * (the player changed team, or was removed while offline and invited back) never matches, so nothing has to be
     * written for offline players; their next login clears it.
     */
    static final String MODE_KEY = "team-chat-mode";
    static final String MODE_OFF = "0";

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

    /**
     * Switches team chat mode in the player's team; returns the new state. The mode is remembered with the membership,
     * so it can come back at the next login ({@code team-chat-sticky}).
     */
    boolean toggle(UUID player, Team team) {
        boolean on = !this.chatMode.remove(player);
        if (on) {
            this.chatMode.add(player);
        }
        remember(player, on ? membership(team, player) : MODE_OFF);
        return on;
    }

    /** Turns team chat mode off and forgets it (left the team, removed, disbanded). Returns true if it was on. */
    boolean off(UUID player) {
        boolean was = this.chatMode.remove(player);
        // Only a loaded player's remembered mode is known (others read as off, so nothing is written for them): the
        // membership left for an offline player matches no team they can be in again, and their next login clears it.
        remember(player, MODE_OFF);
        return was;
    }

    /** Stores the remembered mode when it changes (one write; nothing for an unchanged or unknown value). */
    private void remember(UUID player, String value) {
        if (!value.equals(this.playerSettings.raw(player, MODE_KEY, MODE_OFF))) {
            this.playerSettings.setRaw(player, MODE_KEY, value);
        }
    }

    /**
     * The remembered value of team chat mode on in a membership: the team's id and when the player joined it, so a
     * value from an earlier membership of the same team (removed and invited back) never matches.
     */
    static String membership(Team team, UUID player) {
        TeamMember member = team.members().get(player);
        return team.id() + ":" + (member == null ? 0 : member.joined());
    }

    /**
     * Whether team chat mode comes back at login: the player keeps it ({@code team-chat-sticky}), had it on when they
     * left, and is still in the same membership they had it on in.
     *
     * @param sticky     the player's {@code team-chat-sticky}
     * @param remembered the remembered mode ({@link #MODE_KEY}: a {@link #membership} or {@link #MODE_OFF})
     * @param membership the player's membership now ({@link #membership}), or null without a team
     */
    static boolean restores(boolean sticky, String remembered, String membership) {
        return sticky && membership != null && membership.equals(remembered);
    }

    /**
     * What to remember when the player leaves: their membership while team chat mode is on, else {@link #MODE_OFF}.
     *
     * @param on         team chat mode is on
     * @param membership the player's membership now, or null without a team
     */
    static String atQuit(boolean on, String membership) {
        return on && membership != null ? membership : MODE_OFF;
    }

    /**
     * At login: puts team chat mode back for players who keep it, and tells them in chat; otherwise forgets a
     * remembered mode, so turning the setting on later never brings back a mode from an older session (or team). Call
     * on the player's thread.
     */
    void restore(Player player) {
        UUID id = player.getUniqueId();
        String membership = this.registry.of(id).map(team -> membership(team, id)).orElse(null);
        String remembered = this.playerSettings.raw(id, MODE_KEY, MODE_OFF);
        boolean back = restores(this.playerSettings.get(player, TeamPrefs.CHAT_STICKY), remembered, membership);
        if (back) {
            this.chatMode.add(id);
            this.messenger.send(player, TeamsMessages.CHAT_RESTORED);
        }
        remember(id, back ? remembered : MODE_OFF);
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
                if (!member.equals(sender.getUniqueId())) {
                    // The team chat sound each member picked (off by default).
                    this.messenger.sounds().ping(online, this.playerSettings.get(member, SharedSettings.SOUND_TEAM_CHAT));
                }
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
            off(id);
            this.messenger.send(player, TeamsMessages.CHAT_NO_TEAM);
            return;
        }
        send(player, team, TextStyle.plain(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        restore(event.getPlayer());
    }

    /**
     * Quitting remembers the mode as it is (it may come back at the next login). Runs before core forgets the player's
     * settings (feature listeners are registered first).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer().getUniqueId());
    }

    /** A player left: team chat mode ends for now and is remembered as it was ({@link #atQuit}). */
    void quit(UUID player) {
        boolean on = this.chatMode.remove(player);
        remember(player, atQuit(on, this.registry.of(player).map(team -> membership(team, player)).orElse(null)));
    }
}
