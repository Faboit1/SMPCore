package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import org.junit.jupiter.api.Test;

/** The spawn hologram switch: its place, when it is offered, and who the displays are hidden from. */
class HologramSettingTest {

    @Test
    void theSwitchSitsInTheDisplayGroupAndAppliesAtOnce() {
        AtomicBoolean placed = new AtomicBoolean(true);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        DisplaysFeature.registerSettings(settings, placed::get, (player, before, now) -> { });
        Registry.Entry<?> entry = settings.registry().entry("show-spawn-holograms");
        assertEquals(SettingCategories.DISPLAY, entry.category());
        assertEquals(5, entry.options().order(), "the fifth display setting of the catalog");
        assertEquals(SettingOptions.Apply.INSTANT, entry.options().apply());
        assertNotNull(entry.options().onChange());
        assertNull(entry.setting().permission());
        assertTrue(DisplaysFeature.HOLOGRAMS.defaultOn());
        assertTrue(entry.offered());

        placed.set(false);
        assertFalse(entry.offered(), "no display in the world, nothing to hide");
    }

    @Test
    void playersWhoTurnItOffAreTrackedUntilTheyLeave() {
        HologramViewers viewers = new HologramViewers();
        UUID alex = new UUID(1, 1);
        UUID blake = new UUID(2, 2);
        assertFalse(viewers.set(alex, true), "showing them is the default: nothing to apply");
        assertTrue(viewers.set(blake, false), "turning them off changes what Blake sees");
        assertFalse(viewers.set(blake, false), "again: already hidden");
        assertTrue(viewers.hides(blake) && !viewers.hides(alex));
        assertEquals(Set.of(blake), viewers.hiding(), "new display entities are hidden from these players as they spawn");

        assertTrue(viewers.set(blake, true), "turning them back on shows every display again");
        assertEquals(Set.of(), viewers.hiding());

        viewers.set(alex, false);
        viewers.forget(alex);
        assertFalse(viewers.hides(alex), "a hide ends with the session (the server forgets it too)");
    }
}
