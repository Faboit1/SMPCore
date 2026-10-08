package net.siftvanilla.siftcore.feature.staff;

import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells online staff (and the console log) about things other staff did. The staff member who caused a notice
 * already got a confirmation, so they are skipped. Safe from any thread: it only sends packets.
 */
final class StaffNotices {

    private final Messenger messenger;

    StaffNotices(Messenger messenger) {
        this.messenger = messenger;
    }

    /** Sends a lang message to everyone online with {@code permission} except {@code actor}, and to the console. */
    void send(String permission, Actor actor, MessageKey key, Arg... args) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission) && (actor == null || !actor.is(player.getUniqueId()))) {
                this.messenger.send(player, key, args);
            }
        }
        if (actor == null || !actor.isConsole()) {
            this.messenger.chat(Bukkit.getConsoleSender(), key, args);
        }
    }

    /** Sends a ready component (for example one with a click action) with a sound, the same way. */
    void send(String permission, Actor actor, Component text, Feedback feedback) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission) && (actor == null || !actor.is(player.getUniqueId()))) {
                player.sendMessage(text);
                this.messenger.feedback(player, feedback);
            }
        }
        if (actor == null || !actor.isConsole()) {
            Bukkit.getConsoleSender().sendMessage(text);
        }
    }
}
