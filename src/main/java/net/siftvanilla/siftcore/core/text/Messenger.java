package net.siftvanilla.siftcore.core.text;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Sends lang messages on their declared channel with their sound, following each player's delivery settings (see
 * {@link Routing}): short results and errors go where the player's {@code feedback-channel} says, and
 * {@link #alert} delivers a notification in the style a feature's setting chose, honouring quiet in combat. Every
 * message is rendered for its recipient: money in it is written in the player's "Money format" ({@link MoneyDisplay}),
 * whoever caused it, and {@link #broadcast} renders once per format. All methods are thread-safe: they only send
 * packets to the audience.
 */
public final class Messenger {

    private static final Title.Times TITLE_TIMES = Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500));

    private final Lang lang;
    private final Sounds sounds;
    private final PlayerSettings settings;
    private final CombatStatus combat;
    private final ChatRepeats repeats = new ChatRepeats();

    /** A messenger without per-player delivery settings (tests). */
    public Messenger(Lang lang, Sounds sounds) {
        this(lang, sounds, null, null);
    }

    public Messenger(Lang lang, Sounds sounds, PlayerSettings settings, CombatStatus combat) {
        this.lang = lang;
        this.sounds = sounds;
        this.settings = settings;
        this.combat = combat;
    }

    public Lang lang() {
        return this.lang;
    }

    public Sounds sounds() {
        return this.sounds;
    }

    /**
     * Sends on the key's channel and plays its sound. Success and error lines on the action bar follow the player's
     * feedback channel (an error repeated while the player keeps trying shows in chat once); console senders always
     * get chat.
     */
    public void send(Audience audience, MessageKey key, Arg... args) {
        Component text = render(audience, key, args);
        if (!(audience instanceof Player player)) {
            audience.sendMessage(text);
            return;
        }
        deliver(player, text, feedbackPlaces(player.getUniqueId(), key, key.channel()));
        this.sounds.play(player, key.feedback());
    }

    /**
     * A message as its reader gets it: money in the player's money format (the server's way for the console and other
     * audiences), whatever scope the caller renders in.
     */
    public Component render(Audience reader, MessageKey key, Arg... args) {
        return this.lang.viewing(reader, () -> this.lang.get(key, args));
    }

    /** Sends to chat regardless of the key's channel (used for command output that should be kept). */
    public void chat(Audience audience, MessageKey key, Arg... args) {
        audience.sendMessage(render(audience, key, args));
        if (audience instanceof Player) {
            this.sounds.play(audience, key.feedback());
        }
    }

    /** Sends on the action bar (success and error lines follow the player's feedback channel). */
    public void actionbar(Audience audience, MessageKey key, Arg... args) {
        Component text = render(audience, key, args);
        if (audience instanceof Player player) {
            deliver(player, text, feedbackPlaces(player.getUniqueId(), key, Channel.ACTIONBAR));
            this.sounds.play(audience, key.feedback());
        } else {
            audience.sendMessage(text);
        }
    }

    public void actionbar(Audience audience, Component text) {
        audience.sendActionBar(text);
    }

    public void title(Audience audience, MessageKey title, MessageKey subtitle, Arg... args) {
        Component sub = subtitle == null ? Component.empty() : render(audience, subtitle, args);
        audience.showTitle(Title.title(render(audience, title, args), sub, TITLE_TIMES));
        this.sounds.play(audience, title.feedback());
    }

    /**
     * Delivers a notification in the style the player chose in one of their settings (chat, action bar, title, both
     * or off; a boss bar style shows on the action bar). While they are in combat with quiet in combat on, action
     * bar and title alerts become a chat line and pings and chimes stay silent.
     */
    public void alert(Audience audience, AlertStyle style, MessageKey key, Arg... args) {
        alert(audience, style, true, key, args);
    }

    /**
     * {@link #alert(Audience, AlertStyle, MessageKey, Arg...)} where {@code quiet} false ignores quiet in combat:
     * combat's own alerts (the timer, being tagged, kills) must show in combat.
     */
    public void alert(Audience audience, AlertStyle style, boolean quiet, MessageKey key, Arg... args) {
        if (style == AlertStyle.OFF) {
            return;
        }
        Component text = render(audience, key, args);
        if (!(audience instanceof Player player)) {
            audience.sendMessage(text);
            return;
        }
        deliver(player, text, Routing.alert(style, quiet && quietNow(player.getUniqueId())));
        this.sounds.play(player, key.feedback());
    }

    public void feedback(Audience audience, Feedback feedback) {
        this.sounds.play(audience, feedback);
    }

    /**
     * Sends to every online player and the console, each with money in their own money format (the line is rendered
     * once per format, not once per player).
     */
    public void broadcast(MessageKey key, Arg... args) {
        Function<Audience, Component> text = this.lang.perViewer(() -> this.lang.get(key, args));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(text.apply(player));
            this.sounds.play(player, key.feedback());
        }
        Bukkit.getConsoleSender().sendMessage(text.apply(Bukkit.getConsoleSender()));
    }

    /** Whether quiet in combat applies to a player now (they turned it on and are tagged). */
    public boolean quietNow(UUID player) {
        return this.settings != null && this.combat != null && this.combat.tagged(player)
            && this.settings.get(player, SharedSettings.QUIET_IN_COMBAT);
    }

    private AlertStyle feedbackChannel(UUID player) {
        return this.settings == null ? AlertStyle.ACTIONBAR : this.settings.get(player, SharedSettings.FEEDBACK_CHANNEL);
    }

    /** Where a line sent on {@code channel} goes for a player, leaving out chat for an error they keep repeating. */
    private Set<Routing.Place> feedbackPlaces(UUID player, MessageKey key, Channel channel) {
        Set<Routing.Place> places = Routing.feedback(key, channel, feedbackChannel(player));
        if (Routing.movedToChat(key, channel, places) && key.feedback() == Feedback.ERROR
            && !this.repeats.allow(player, key.path(), System.currentTimeMillis())) {
            Set<Routing.Place> rest = EnumSet.noneOf(Routing.Place.class);
            rest.addAll(places);
            rest.remove(Routing.Place.CHAT);
            return rest;
        }
        return places;
    }

    private static void deliver(Audience audience, Component text, Set<Routing.Place> places) {
        for (Routing.Place place : places) {
            switch (place) {
                case CHAT -> audience.sendMessage(text);
                case ACTIONBAR -> audience.sendActionBar(text);
                case TITLE -> audience.showTitle(Title.title(text, Component.empty(), TITLE_TIMES));
            }
        }
    }
}
