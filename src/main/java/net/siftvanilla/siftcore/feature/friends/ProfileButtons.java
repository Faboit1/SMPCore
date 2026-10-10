package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * The buttons that lead from a profile to the rest of the server: message, teleport request, team invite, pay and
 * stats. Each one is shown only when the command behind it exists (a feature can be missing or turned off in
 * {@code commands.yml}), the viewer may use it, and, for the ones that need the other player around, the other player
 * is visibly online; Message is left out for a muted viewer, who couldn't send it, and Invite to team for a target
 * whose {@code team-invites} refuses the viewer. The decision is made on the viewer's thread before the profile is
 * built.
 * <p>
 * A button runs its command as the player from the dialog handler, on the player's thread, as if they had typed it
 * (the command preprocess event, then {@link Player#performCommand}), so the server's command guards and the other
 * feature do every check they normally do. If that throws, or the server reports the command as not run, the player
 * gets a chat line with a link that runs the same command (a throw is also logged, once per command).
 */
final class ProfileButtons {

    /** A cross-feature action of a profile. */
    enum Action {
        MESSAGE("msg", "siftcore.command.msg", true),
        TELEPORT("tpa", "siftcore.command.tpa", true),
        INVITE("team", "siftcore.command.team", true),
        PAY("pay", "siftcore.command.pay", false),
        STATS("stats", "siftcore.command.stats.others", false);

        private final String command;
        private final String permission;
        private final boolean needsOnline;

        Action(String command, String permission, boolean needsOnline) {
            this.command = command;
            this.permission = permission;
            this.needsOnline = needsOnline;
        }

        String command() {
            return this.command;
        }

        String permission() {
            return this.permission;
        }

        /** The command line this action runs for the target. */
        String line(String targetName) {
            return switch (this) {
                case INVITE -> "team invite " + targetName;
                default -> this.command + " " + targetName;
            };
        }
    }

    /** The teams feature's "Team invites from" choice, read by id (stored form: everyone, friends or nobody). */
    static final String TEAM_INVITES = "team-invites";

    private final TeamLookup teams;
    private final MuteStatus mutes;
    private final Messenger messenger;
    private final Lang lang;
    private final Logger logger;
    private final PlayerSettings settings;
    private final FriendLookup friends;
    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();

    ProfileButtons(TeamLookup teams, MuteStatus mutes, Messenger messenger, Logger logger, PlayerSettings settings,
                   FriendLookup friends) {
        this.teams = teams;
        this.mutes = mutes;
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.logger = logger;
        this.settings = settings;
        this.friends = friends;
    }

    /**
     * Whether a player's {@code team-invites} choice lets the viewer invite them (the invite would be refused
     * otherwise).
     *
     * @param choice  the target's {@value #TEAM_INVITES} in its stored form, null without a teams feature
     * @param friends the viewer and the target are friends
     */
    static boolean takesInvites(String choice, boolean friends) {
        if (choice == null) {
            return true;
        }
        return switch (choice) {
            case "nobody" -> false;
            case "friends" -> friends;
            default -> true;
        };
    }

    /** Whether a command with this label is registered right now. */
    static boolean registered(String command) {
        return Bukkit.getCommandMap().getCommand(command) != null;
    }

    /**
     * The actions the viewer gets on the target's profile. Call on the viewer's thread.
     *
     * @param visibleOnline whether the target is online and visible to the viewer
     */
    List<Action> available(Player viewer, UUID target, boolean visibleOnline) {
        List<Action> actions = new ArrayList<>();
        if (viewer.getUniqueId().equals(target)) {
            if (registered(Action.STATS.command()) && viewer.hasPermission("siftcore.command.stats")) {
                actions.add(Action.STATS);
            }
            return actions;
        }
        for (Action action : Action.values()) {
            if (action.needsOnline && !visibleOnline) {
                continue;
            }
            if (!registered(action.command()) || !viewer.hasPermission(action.permission())) {
                continue;
            }
            if (action == Action.INVITE && (this.teams.team(target).isPresent() || !this.teams.canInvite(viewer.getUniqueId())
                || !takesInvites(this.settings.encoded(target, TEAM_INVITES), this.friends.friends(viewer.getUniqueId(), target)))) {
                continue;
            }
            if (action == Action.MESSAGE && this.mutes.mute(viewer.getUniqueId()).isPresent()) {
                // A muted player can't send it: no button that leads to a form they can only fail.
                continue;
            }
            actions.add(action);
        }
        return actions;
    }

    /**
     * Runs the action's command as the player for the target (plain {@code /stats} on the player's own card). Call
     * on the player's thread.
     */
    void run(Player player, Action action, UUID target, String targetName) {
        boolean own = player.getUniqueId().equals(target);
        perform(player, own && action == Action.STATS ? action.command() : action.line(targetName));
    }

    /**
     * Runs a command line as the player, exactly as if they had typed it, with the chat-link fallback. Call on the
     * player's thread.
     * <p>
     * {@link Player#performCommand} alone skips {@link PlayerCommandPreprocessEvent}, which is where the server's
     * guards live (commands refused in combat, while frozen or while muted), so the event is fired first the way the
     * server fires it for a typed command: a guard that cancels it has told the player why and nothing runs, and a
     * listener that rewrites the line is respected.
     */
    void perform(Player player, String line) {
        try {
            PlayerCommandPreprocessEvent asTyped = new PlayerCommandPreprocessEvent(player, "/" + line);
            if (!asTyped.callEvent()) {
                return;
            }
            String message = asTyped.getMessage();
            if (player.performCommand(message.startsWith("/") ? message.substring(1) : message)) {
                return;
            }
        } catch (Throwable t) {
            String label = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
            if (this.failedOnce.add(label)) {
                this.logger.log(Level.WARNING, "Running /" + label + " from a friends profile failed; players get a chat link instead", t);
            }
        }
        Component link = Component.text("/" + line, this.lang.style().palette().primary())
            .clickEvent(ClickEvent.runCommand("/" + line))
            .hoverEvent(HoverEvent.showText(this.lang.get(FriendsMessages.LINK_COMMAND_HOVER)));
        this.messenger.send(player, FriendsMessages.LINK_COMMAND, Arg.component("command", link));
    }
}
