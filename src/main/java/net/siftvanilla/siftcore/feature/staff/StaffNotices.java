package net.siftvanilla.siftcore.feature.staff;

import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Routing;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells online staff (and the console log) about things other staff did. The staff member who caused a notice
 * already got a confirmation, so they are skipped. Each recipient's alert setting for that kind of notice
 * ({@link StaffPreferences#PUNISH_ALERTS}, {@link StaffPreferences#REPORT_ALERTS},
 * {@link StaffPreferences#FREEZE_ALERTS}) decides whether and where it shows; the console always gets it. Safe from
 * any thread: it only reads cached settings and sends packets.
 */
final class StaffNotices {

    private final Messenger messenger;
    private final PlayerSettings settings;

    StaffNotices(Messenger messenger, PlayerSettings settings) {
        this.messenger = messenger;
        this.settings = settings;
    }

    /**
     * Sends a lang message to everyone online with {@code permission} except {@code actor}, each where their
     * {@code setting} says, and to the console.
     */
    void send(String permission, Choice<AlertStyle> setting, Actor actor, MessageKey key, Arg... args) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AlertStyle style = style(player, permission, setting, actor);
            if (style != AlertStyle.OFF) {
                this.messenger.alert(player, style, key, args);
            }
        }
        if (actor == null || !actor.isConsole()) {
            this.messenger.chat(Bukkit.getConsoleSender(), key, args);
        }
    }

    /**
     * Sends a ready component (for example one with a click action, which only works in chat) with a sound, the same
     * way.
     */
    void send(String permission, Choice<AlertStyle> setting, Actor actor, Component text, Feedback feedback) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AlertStyle style = style(player, permission, setting, actor);
            if (style == AlertStyle.OFF) {
                continue;
            }
            Set<Routing.Place> places = Routing.alert(style, this.messenger.quietNow(player.getUniqueId()));
            for (Routing.Place place : places) {
                switch (place) {
                    case CHAT -> player.sendMessage(text);
                    case ACTIONBAR -> player.sendActionBar(text);
                    case TITLE -> player.showTitle(Title.title(text, Component.empty()));
                }
            }
            this.messenger.feedback(player, feedback);
        }
        if (actor == null || !actor.isConsole()) {
            Bukkit.getConsoleSender().sendMessage(text);
        }
    }

    private AlertStyle style(Player player, String permission, Choice<AlertStyle> setting, Actor actor) {
        return StaffPreferences.noticeStyle(player.hasPermission(permission), actor != null && actor.is(player.getUniqueId()),
            this.settings.get(player.getUniqueId(), setting));
    }
}
