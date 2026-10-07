package net.siftvanilla.siftcore.feature.extras;

import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/extras.yml}. */
public record ExtrasSettings(boolean joinMessages, boolean quitMessages, boolean firstJoinWelcome) {

    public static ExtrasSettings parse(ConfigReader r) {
        ConfigReader messages = r.section("messages");
        return new ExtrasSettings(
            messages.bool("join", false),
            messages.bool("quit", false),
            messages.bool("first-join-welcome", true));
    }
}
