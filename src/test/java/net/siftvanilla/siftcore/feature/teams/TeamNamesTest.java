package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Team name validation and the block list. */
class TeamNamesTest {

    private static final List<String> NONE = List.of();

    @Test
    void lengthLimits() {
        assertEquals(TeamProblem.NAME_TOO_SHORT, TeamNames.validate("ab", 3, 16, NONE));
        assertNull(TeamNames.validate("abc", 3, 16, NONE));
        assertNull(TeamNames.validate("abcdefghijklmnop", 3, 16, NONE));
        assertEquals(TeamProblem.NAME_TOO_LONG, TeamNames.validate("abcdefghijklmnopq", 3, 16, NONE));
        assertEquals(TeamProblem.NAME_TOO_SHORT, TeamNames.validate("", 3, 16, NONE));
        assertEquals(TeamProblem.NAME_TOO_SHORT, TeamNames.validate(null, 3, 16, NONE));
        assertEquals(TeamProblem.NAME_TOO_LONG, TeamNames.validate("abcdefghijklmnopq", 3, 40, NONE),
            "the database column holds 16 characters whatever the config says");
        assertEquals(TeamProblem.NAME_TOO_LONG, TeamNames.validate("abcdefgh", 3, 6, NONE));
    }

    @Test
    void onlyLettersDigitsAndUnderscores() {
        assertNull(TeamNames.validate("Team_42", 3, 16, NONE));
        for (String bad : List.of("my team", "team-1", "team.x", "tëam", "team§c", "<red>x", "team​", "ＴＥＡＭ")) {
            assertEquals(TeamProblem.NAME_CHARACTERS, TeamNames.validate(bad, 3, 16, NONE), bad);
        }
    }

    @Test
    void uniquenessKeyIgnoresCase() {
        assertEquals(TeamNames.key("Testers"), TeamNames.key("tEsTeRs"));
    }

    @Test
    void blockListCatchesCommonWorkarounds() {
        List<String> blocked = List.of("bad");
        for (String name : List.of("bad", "BAD", "xxBadxx", "B_A_D", "b4d", "baaad", "bbaadd", "B_4_A_D")) {
            assertEquals(TeamProblem.NAME_BLOCKED, TeamNames.validate(name, 3, 16, blocked), name);
        }
        for (String name : List.of("bat", "abd", "bxad", "dab", "b_x_a_d")) {
            assertFalse(TeamNames.blocked(name, blocked), name);
        }
    }

    @Test
    void stretchingNeverMatchesMoreThanASubstringWould() {
        List<String> blocked = List.of("ass");
        assertTrue(TeamNames.blocked("class", blocked), "a plain substring still matches");
        assertFalse(TeamNames.blocked("clas", blocked), "a double letter in a blocked word must stay double");
        assertFalse(TeamNames.blocked("Basic", blocked));
        assertTrue(TeamNames.blocked("a55", blocked), "digits read as letters");
    }

    @Test
    void blockListIsCleaned() {
        assertEquals(List.of("bad", "worse"), TeamNames.cleanBlockList(Arrays.asList(" Bad ", "", null, "worse", "BAD")));
        assertFalse(TeamNames.blocked("anything", List.of()));
    }
}
