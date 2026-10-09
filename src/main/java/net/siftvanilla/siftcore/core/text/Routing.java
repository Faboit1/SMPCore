package net.siftvanilla.siftcore.core.text;

import java.util.EnumSet;
import java.util.Set;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;

/**
 * Where a message goes for a player: the pure routing rules of {@link Messenger}, unit tested.
 * <ul>
 *   <li>Feedback: short results and errors ({@link Feedback#SUCCESS}/{@link Feedback#ERROR} on the action bar) follow
 *       the player's {@code feedback-channel} (hotbar, chat or both). Status lines ({@link MessageKey#status()}, also
 *       refusals marked {@link MessageKey#asStatus()}) and everything else keep their channel. The messenger shows an
 *       error moved to chat once while the player keeps repeating it.</li>
 *   <li>Alerts: a notification setting's {@link AlertStyle} picks the place; while the player is in combat with
 *       "Quiet during combat" on, action bar, title and boss bar alerts become a chat line instead.</li>
 * </ul>
 */
public final class Routing {

    /** A place a message is shown. */
    public enum Place {
        CHAT,
        ACTIONBAR,
        TITLE
    }

    private Routing() {
    }

    /**
     * Where a message sent on {@code channel} goes.
     *
     * @param preference the player's feedback channel ({@link AlertStyle#ACTIONBAR}, {@link AlertStyle#CHAT} or
     *                   {@link AlertStyle#BOTH}; anything else counts as the action bar)
     */
    public static Set<Place> feedback(MessageKey key, Channel channel, AlertStyle preference) {
        if (channel == Channel.ACTIONBAR && !key.status() && (key.feedback() == Feedback.SUCCESS || key.feedback() == Feedback.ERROR)) {
            return switch (preference) {
                case CHAT -> EnumSet.of(Place.CHAT);
                case BOTH -> EnumSet.of(Place.CHAT, Place.ACTIONBAR);
                default -> EnumSet.of(Place.ACTIONBAR);
            };
        }
        return switch (channel) {
            case CHAT, NONE -> EnumSet.of(Place.CHAT);
            case ACTIONBAR -> EnumSet.of(Place.ACTIONBAR);
            case TITLE -> EnumSet.of(Place.TITLE);
        };
    }

    /**
     * Whether {@link #feedback} moved a line meant for the action bar into chat (the player's feedback channel is chat
     * or both): only such lines can pile up in chat when an error repeats.
     */
    public static boolean movedToChat(MessageKey key, Channel channel, Set<Place> places) {
        return channel == Channel.ACTIONBAR && !key.status() && places.contains(Place.CHAT);
    }

    /**
     * Where an alert goes.
     *
     * @param quiet true when the alert respects quiet-in-combat, the player turned it on and is in combat now
     */
    public static Set<Place> alert(AlertStyle style, boolean quiet) {
        return switch (style) {
            case OFF -> EnumSet.noneOf(Place.class);
            case CHAT -> EnumSet.of(Place.CHAT);
            case ACTIONBAR, BOSSBAR -> EnumSet.of(quiet ? Place.CHAT : Place.ACTIONBAR);
            case TITLE -> EnumSet.of(quiet ? Place.CHAT : Place.TITLE);
            case BOTH -> quiet ? EnumSet.of(Place.CHAT) : EnumSet.of(Place.CHAT, Place.ACTIONBAR);
        };
    }
}
