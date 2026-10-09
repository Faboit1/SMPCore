package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.siftvanilla.siftcore.feature.homes.BareHome.Action;
import org.junit.jupiter.api.Test;

/** "/home with no name" decisions and the homes settings' stored values. */
class HomesSettingsTest {

    @Test
    void theDefaultKeepsTheClassicBehaviour() {
        assertEquals(Action.NONE, BareHome.SMART.decide(0, false));
        assertEquals(Action.ONLY, BareHome.SMART.decide(1, false));
        assertEquals(Action.ONLY, BareHome.SMART.decide(1, true));
        assertEquals(Action.LIST, BareHome.SMART.decide(2, true), "several homes open the list, even with one called home");
    }

    @Test
    void theHomeNamedHomeWinsWhenThereIsOne() {
        assertEquals(Action.DEFAULT, BareHome.DEFAULT_HOME.decide(3, true));
        assertEquals(Action.DEFAULT, BareHome.DEFAULT_HOME.decide(1, true));
        assertEquals(Action.LIST, BareHome.DEFAULT_HOME.decide(3, false), "without it, like the default");
        assertEquals(Action.ONLY, BareHome.DEFAULT_HOME.decide(1, false), "without it, like the default");
        assertEquals(Action.NONE, BareHome.DEFAULT_HOME.decide(0, false));
    }

    @Test
    void theListAlwaysOpens() {
        assertEquals(Action.LIST, BareHome.LIST.decide(1, true), "even with a single home");
        assertEquals(Action.LIST, BareHome.LIST.decide(5, false));
        assertEquals(Action.NONE, BareHome.LIST.decide(0, false), "no homes is still told");
    }

    @Test
    void storedValuesAreTheOptionIds() {
        assertEquals("default-home", HomesFeature.BARE_COMMAND.encode(BareHome.DEFAULT_HOME));
        assertEquals(BareHome.LIST, HomesFeature.BARE_COMMAND.decodeOrNull("LIST"));
        assertEquals(BareHome.SMART, HomesFeature.BARE_COMMAND.defaultValue());
        assertTrue(HomesFeature.CONFIRM_OVERWRITE.defaultOn(), "moving a home asks first unless turned off");
    }
}
