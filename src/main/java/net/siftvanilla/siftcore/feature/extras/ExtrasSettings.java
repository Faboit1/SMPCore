package net.siftvanilla.siftcore.feature.extras;

import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/extras.yml}. */
public record ExtrasSettings(boolean joinMessages, boolean quitMessages, boolean firstJoinWelcome) {

    /** Whether the server shows any plain join, leave or welcome line. */
    public boolean anyLines() {
        return this.joinMessages || this.quitMessages || this.firstJoinWelcome;
    }

    /**
     * Whether the join and leave setting is offered: some line can show, a plain one of this file or a rank or custom
     * line of the cosmetics ({@code rankLines}), the same rule the staff tools' fake vanish lines follow.
     */
    public boolean offersSetting(boolean rankLines) {
        return anyLines() || rankLines;
    }

    public static ExtrasSettings parse(ConfigReader r) {
        ConfigReader messages = r.section("messages");
        return new ExtrasSettings(
            messages.bool("join", false),
            messages.bool("quit", false),
            messages.bool("first-join-welcome", true));
    }
}
