package net.siftvanilla.siftcore.feature.stats;

import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** When a player is told they moved up a leaderboard: the options of {@code leaderboard-rank-alerts}. */
public enum RankAlerts {
    /** Only when the new place is in the top 10. */
    TOP_10("top-10", StatsMessages.OPTION_TOP_10),
    /** At any place on the board. */
    ALL("all", StatsMessages.OPTION_ANY_PLACE),
    /** Never. */
    OFF("off", OptionTexts.ALERT_OFF);

    /** The places {@link #TOP_10} tells about. */
    static final int TOP = 10;

    private final String id;
    private final MessageKey label;

    RankAlerts(String id, MessageKey label) {
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

    /** Whether a climb to {@code rank} (1 is the top) is told. */
    public boolean tells(int rank) {
        return switch (this) {
            case TOP_10 -> rank >= 1 && rank <= TOP;
            case ALL -> rank >= 1;
            case OFF -> false;
        };
    }
}
