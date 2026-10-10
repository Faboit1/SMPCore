package net.siftvanilla.siftcore.feature.boosters;

import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;

/**
 * The booster settings: which sell booster announcements a player sees in chat ({@code booster-announcements}, Server
 * announcements) and the boss bar switch ({@code booster-bar}, Display), with the pure rule of the filter.
 */
final class BoosterNews {

    /** What an announcement is about. */
    enum Kind {
        /** A booster started (and the reason staff gave for it). */
        STARTED,
        /** A booster joined the line. */
        QUEUED,
        /** A booster ended or was ended early. */
        ENDED
    }

    /** Which booster announcements a player sees: all, only new boosters, or none. */
    enum Filter {
        ALL("all"),
        STARTS("starts"),
        OFF("off");

        private final String id;

        Filter(String id) {
            this.id = id;
        }

        String id() {
            return this.id;
        }
    }

    /** The booster announcement filter; the shared announcement words for all and off. */
    static final Choice<Filter> ANNOUNCEMENTS = Choice.ofEnum("booster-announcements", Filter.class, Filter::id, Filter.ALL)
        .option(Filter.ALL, OptionTexts.ANNOUNCE_ALL)
        .option(Filter.STARTS, BoostersMessages.SETTING_NEWS_STARTS, null, Filter.ALL.id())
        .option(Filter.OFF, OptionTexts.ANNOUNCE_OFF)
        .text(BoostersMessages.SETTING_NEWS, BoostersMessages.SETTING_NEWS_DESCRIPTION).build();
    /** The boss bar switch. */
    static final Toggle BAR = new Toggle("booster-bar", true, BoostersMessages.TOGGLE_LABEL, BoostersMessages.TOGGLE_DESCRIPTION, null);

    private BoosterNews() {
    }

    /**
     * Registers the bar switch in {@code display} (on-screen things, next to the sidebar) and the announcement filter
     * in Server announcements, each offered while the config shows what it controls. Flipping the bar applies at once.
     *
     * @param barChanged shows or hides a player's bar at once (on their thread)
     */
    static void register(PlayerSettings prefs, SettingCategory display, Supplier<BoostersSettings> config,
                         SettingOptions.ChangeHook<Boolean> barChanged) {
        prefs.register(display, BAR, SettingOptions.<Boolean>builder().order(6).onChange(barChanged)
            .availableWhen(() -> config.get().bar().enabled()).build());
        prefs.register(SettingCategories.ANNOUNCEMENTS, ANNOUNCEMENTS, SettingOptions.<Filter>builder().order(8)
            .availableWhen(() -> anyAnnounced(config.get().announce()))
            // "Only new boosters" differs from "All" only while starts and something else are announced.
            .optionAvailableWhen(Filter.STARTS.id(), () -> startsDiffer(config.get().announce()))
            .build());
    }

    static boolean anyAnnounced(BoostersSettings.Announce announce) {
        return announce.started() || announce.queued() || announce.ended();
    }

    static boolean startsDiffer(BoostersSettings.Announce announce) {
        return announce.started() && (announce.queued() || announce.ended());
    }

    /**
     * Whether a player sees an announcement of {@code kind}: by their filter, and always when it is about their own
     * booster (the buyer's thank-you, their booster ending).
     */
    static boolean shows(Filter filter, Kind kind, boolean own) {
        if (own) {
            return true;
        }
        return switch (filter) {
            case ALL -> true;
            case STARTS -> kind == Kind.STARTED;
            case OFF -> false;
        };
    }
}
