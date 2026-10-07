package net.siftvanilla.siftcore.core.text;

import java.time.Duration;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Sends lang messages on their declared channel with their sound. All methods are thread-safe: they only send
 * packets to the audience.
 */
public final class Messenger {

    private static final Title.Times TITLE_TIMES = Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500));

    private final Lang lang;
    private final Sounds sounds;

    public Messenger(Lang lang, Sounds sounds) {
        this.lang = lang;
        this.sounds = sounds;
    }

    public Lang lang() {
        return this.lang;
    }

    /** Sends on the key's channel and plays its sound. Console senders always get chat. */
    public void send(Audience audience, MessageKey key, Arg... args) {
        Component text = this.lang.get(key, args);
        if (!(audience instanceof Player)) {
            audience.sendMessage(text);
            return;
        }
        switch (key.channel()) {
            case CHAT, NONE -> audience.sendMessage(text);
            case ACTIONBAR -> audience.sendActionBar(text);
            case TITLE -> audience.showTitle(Title.title(text, Component.empty(), TITLE_TIMES));
        }
        this.sounds.play(audience, key.feedback());
    }

    /** Sends to chat regardless of the key's channel (used for command output that should be kept). */
    public void chat(Audience audience, MessageKey key, Arg... args) {
        audience.sendMessage(this.lang.get(key, args));
        if (audience instanceof Player) {
            this.sounds.play(audience, key.feedback());
        }
    }

    public void actionbar(Audience audience, MessageKey key, Arg... args) {
        if (audience instanceof Player) {
            audience.sendActionBar(this.lang.get(key, args));
            this.sounds.play(audience, key.feedback());
        } else {
            audience.sendMessage(this.lang.get(key, args));
        }
    }

    public void actionbar(Audience audience, Component text) {
        audience.sendActionBar(text);
    }

    public void title(Audience audience, MessageKey title, MessageKey subtitle, Arg... args) {
        Component sub = subtitle == null ? Component.empty() : this.lang.get(subtitle, args);
        audience.showTitle(Title.title(this.lang.get(title, args), sub, TITLE_TIMES));
        this.sounds.play(audience, title.feedback());
    }

    public void feedback(Audience audience, Feedback feedback) {
        this.sounds.play(audience, feedback);
    }

    /** Sends to every online player and the console. */
    public void broadcast(MessageKey key, Arg... args) {
        Component text = this.lang.get(key, args);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(text);
            this.sounds.play(player, key.feedback());
        }
        Bukkit.getConsoleSender().sendMessage(text);
    }
}
