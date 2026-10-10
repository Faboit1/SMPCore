package net.siftvanilla.siftcore.feature.scoreboard;

import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Which lines a player's sidebar shows (the {@code sidebar-layout} setting): every configured line, a short money
 * view, or fight stats. The lines of each layout come from {@code features/scoreboard.yml}: {@code sidebar.lines} for
 * the full sidebar, {@code sidebar.layouts.<id>} for the others.
 */
public enum SidebarLayout {

    FULL("full", ScoreboardMessages.LAYOUT_FULL),
    COMPACT("compact", ScoreboardMessages.LAYOUT_COMPACT),
    COMBAT("combat", ScoreboardMessages.LAYOUT_COMBAT);

    private final String id;
    private final MessageKey label;

    SidebarLayout(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored value and the config key under {@code sidebar.layouts}. */
    public String id() {
        return this.id;
    }

    /** The option's name in the settings dialog. */
    public MessageKey label() {
        return this.label;
    }

    /** The layout with this id, or null. */
    public static SidebarLayout byId(String id) {
        for (SidebarLayout layout : values()) {
            if (layout.id.equals(id)) {
                return layout;
            }
        }
        return null;
    }
}
