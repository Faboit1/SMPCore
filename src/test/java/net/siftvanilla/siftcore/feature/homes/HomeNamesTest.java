package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeNamesTest {

    @Test
    void validNamesAreLowercased() {
        assertEquals(Optional.of("home"), HomeNames.normalize("home"));
        assertEquals(Optional.of("base_2"), HomeNames.normalize("Base_2"));
        assertEquals(Optional.of("farm-north"), HomeNames.normalize("  Farm-North "), "surrounding spaces are ignored");
        assertEquals(Optional.of("a"), HomeNames.normalize("A"));
        assertEquals(Optional.of("abcdefghijklmnop"), HomeNames.normalize("abcdefghijklmnop"), "16 characters");
        assertEquals(Optional.of("123"), HomeNames.normalize("123"));
    }

    @Test
    void invalidNamesAreRefused() {
        for (String bad : new String[] {null, "", "   ", "abcdefghijklmnopq", "two words", "dot.ted", "<red>x", "home!", "ümlaut",
            "slash/name", "colon:name", "§ahome", "tab\tname"}) {
            assertTrue(HomeNames.normalize(bad).isEmpty(), "'" + bad + "' should be refused");
        }
    }

    @Test
    void suggestionsPickTheFirstFreeName() {
        assertEquals("home", HomeNames.suggest(Map.of()));
        assertEquals("home2", HomeNames.suggest(Map.of("home", 1)));
        assertEquals("home3", HomeNames.suggest(Map.of("home", 1, "home2", 1, "base", 1)));
        assertEquals("home", HomeNames.suggest(Map.of("home2", 1)));
    }

    @Test
    void limitsStopOnlyNewNames() {
        assertEquals(HomeStore.Outcome.CREATED, HomeStore.decide(0, false, 2));
        assertEquals(HomeStore.Outcome.CREATED, HomeStore.decide(1, false, 2));
        assertEquals(HomeStore.Outcome.LIMIT, HomeStore.decide(2, false, 2));
        assertEquals(HomeStore.Outcome.MOVED, HomeStore.decide(2, true, 2), "moving an existing home works at the limit");
        assertEquals(HomeStore.Outcome.MOVED, HomeStore.decide(5, true, 2), "and above it, after a rank was lost");
        assertEquals(HomeStore.Outcome.LIMIT, HomeStore.decide(5, false, 2));
        assertEquals(HomeStore.Outcome.LIMIT, HomeStore.decide(0, false, 0), "a limit of zero allows nothing new");
        assertEquals(HomeStore.Outcome.CREATED, HomeStore.decide(1_000, false, Integer.MAX_VALUE), "unlimited");
    }
}
