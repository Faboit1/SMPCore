package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.player.Registry;

/**
 * The pure part of the settings buttons, unit tested: what a click asks for, and which settings a list shows.
 * <p>
 * A click asks for the value after the one its button showed, never after the value stored now: when something else
 * changed the setting while the page was open (a command, another screen), the player still gets what they saw the
 * button would do (a switch that showed ON turns OFF).
 */
final class SettingsClicks {

    private SettingsClicks() {
    }

    /** What a switch that showed {@code shown} asks for. */
    static boolean flipped(boolean shown) {
        return !shown;
    }

    /**
     * The option a choice moves to: the one after {@code shown} among the options the player may pick now, back to the
     * first after the last. When {@code shown} is not offered (any more), the first option; null when none is offered.
     */
    static String next(List<String> offered, String shown) {
        if (offered.isEmpty()) {
            return null;
        }
        int at = shown == null ? -1 : offered.indexOf(shown);
        return offered.get(at < 0 ? 0 : (at + 1) % offered.size());
    }

    /**
     * The settings a list of changed settings shows: those among {@code ids} (the ones the player had changed when the
     * list opened) that they still see, in dialog order. Settings flipped back to their default stay listed until the
     * list is opened again, so a click never makes a button jump away.
     */
    static List<Registry.Entry<?>> snapshot(List<Registry.Entry<?>> visible, List<String> ids) {
        Set<String> wanted = Set.copyOf(ids);
        List<Registry.Entry<?>> shown = new ArrayList<>();
        for (Registry.Entry<?> entry : visible) {
            if (wanted.contains(entry.id())) {
                shown.add(entry);
            }
        }
        return shown;
    }

    /** A setting id as a dialog input key (letters, digits and {@code _}; the registry makes them unique). */
    static String key(String id) {
        return Registry.inputKey(id);
    }
}
