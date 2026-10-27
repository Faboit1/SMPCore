package net.siftvanilla.siftcore.feature.combat;

import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Which deaths of other players a player sees: the options of the {@code death-messages} setting (it was a switch: on
 * reads as {@link #ALL}, off as {@link #OFF}). A player always sees their own deaths and kills, whatever they chose.
 */
public enum DeathFilter {
    /** Every death message. */
    ALL("all", OptionTexts.ANNOUNCE_ALL),
    /** Only player kills (and combat logs that gave someone the kill). */
    PVP("pvp", CombatMessages.OPTION_DEATHS_PVP),
    /** Only deaths of friends and teammates, and kills they made. */
    FRIENDS_TEAM("friends-team", OptionTexts.AUDIENCE_FRIENDS_TEAM),
    /** None. */
    OFF("off", OptionTexts.ANNOUNCE_OFF);

    private final String id;
    private final MessageKey label;

    DeathFilter(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored option id. */
    public String id() {
        return this.id;
    }

    public MessageKey label() {
        return this.label;
    }

    /**
     * Whether a player who is neither the victim nor the killer sees a death message.
     *
     * @param pvp     a player gets the kill
     * @param related the victim or the killer is the viewer's friend or teammate
     */
    public boolean shows(boolean pvp, boolean related) {
        return switch (this) {
            case ALL -> true;
            case PVP -> pvp;
            case FRIENDS_TEAM -> related;
            case OFF -> false;
        };
    }
}
