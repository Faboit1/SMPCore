package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Nicknames: characters, length, reserved words, other players' names and nicknames, the word filter. */
class NickRulesTest {

    private static final UUID ALEX = new UUID(0, 1);
    private static final UUID SAM = new UUID(0, 2);
    private static final NickRules RULES = new NickRules(3, 16, CosmeticsSettings.DEFAULT_RESERVED_WORDS);

    /** Names are looked up ignoring case, like the player directory does. */
    private static Function<String, Optional<UUID>> lookup(Map<String, UUID> names) {
        return name -> Optional.ofNullable(names.get(name.toLowerCase(Locale.ROOT)));
    }

    private static NickRules.Problem check(UUID owner, String nick) {
        return RULES.check(owner, nick, lookup(Map.of("alex", ALEX, "sam", SAM)), lookup(Map.of("shadow", SAM)),
            text -> text.toLowerCase(Locale.ROOT).contains("idiot")).problem();
    }

    @Test
    void acceptsMinecraftStyleNames() {
        assertEquals(NickRules.Problem.OK, check(ALEX, "Night_Rider"));
        assertEquals(NickRules.Problem.OK, check(ALEX, "abc"));
        assertEquals(NickRules.Problem.OK, check(ALEX, "A234567890123456"));
    }

    @Test
    void refusesWrongLengthsAndCharacters() {
        assertEquals(NickRules.Problem.LENGTH, check(ALEX, "ab"));
        assertEquals(NickRules.Problem.LENGTH, check(ALEX, "A2345678901234567"));
        assertEquals(NickRules.Problem.LENGTH, check(ALEX, null));
        for (String bad : List.of("Ale x", "Alex!", "Al§ex", "Ålex", "<red>x", "Alex.")) {
            assertEquals(NickRules.Problem.CHARACTERS, check(ALEX, bad), bad);
        }
    }

    @Test
    void refusesStaffLikeNamesAnywhereAndInAnyCase() {
        for (String bad : List.of("Admin", "xXadminXx", "TheMod", "Mod_erator", "OWNER", "Staffy", "Helper99", "Console", "server_1",
            "SiftVanilla", "A_d_m_i_n", "m_o_d_x")) {
            assertEquals(NickRules.Problem.RESERVED, check(ALEX, bad), bad);
        }
        NickRules.Verdict verdict = RULES.check(ALEX, "BigAdmin", name -> Optional.empty(), nick -> Optional.empty(), text -> false);
        assertEquals("admin", verdict.word());
    }

    @Test
    void ownNameIsFineOtherNamesAreNot() {
        assertEquals(NickRules.Problem.OK, check(ALEX, "ALEX"), "your own name in another case");
        assertEquals(NickRules.Problem.PLAYER_NAME, check(ALEX, "sAm"), "another player's name");
        assertEquals(NickRules.Problem.TAKEN, check(ALEX, "SHADOW"), "another player's nickname");
        assertEquals(NickRules.Problem.OK, check(SAM, "Shadow"), "keeping your own nickname");
    }

    @Test
    void theWordFilterApplies() {
        assertEquals(NickRules.Problem.FILTERED, check(ALEX, "Big_Idiot"));
        NickRules.Problem spaced = RULES.check(ALEX, "kys_now", name -> Optional.empty(), nick -> Optional.empty(),
            text -> text.matches("(?i).*\\bkys\\b.*")).problem();
        assertEquals(NickRules.Problem.FILTERED, spaced, "underscores separate words for the filter");
        assertTrue(NickRules.nameChar('_'));
    }

    @Test
    void shorterLimitsComeFromTheConfig() {
        NickRules strict = new NickRules(4, 10, List.of("vip"));
        assertEquals(NickRules.Problem.LENGTH, strict.check(ALEX, "abc", n -> Optional.empty(), n -> Optional.empty(), t -> false).problem());
        assertEquals(NickRules.Problem.LENGTH, strict.check(ALEX, "abcdefghijk", n -> Optional.empty(), n -> Optional.empty(), t -> false).problem());
        assertEquals(NickRules.Problem.RESERVED, strict.check(ALEX, "MrVIP", n -> Optional.empty(), n -> Optional.empty(), t -> false).problem());
        assertEquals(NickRules.Problem.OK, strict.check(ALEX, "Admin", n -> Optional.empty(), n -> Optional.empty(), t -> false).problem(),
            "only the configured words are reserved");
    }

    @Test
    void aStoredNicknameIsHeldWhileItsHolderCanShowItAndForTheHoldAfter() {
        Duration hold = Duration.ofDays(14);
        long now = 1_790_000_000_000L;
        long day = Duration.ofDays(1).toMillis();
        assertTrue(NickRules.held(false, true, 0L, now, hold), "online with the perk");
        assertTrue(NickRules.held(false, false, now - 13 * day, now, hold), "offline, or the rank ran out, 13 days ago");
        assertTrue(NickRules.held(false, false, now - 14 * day, now, hold), "exactly at the end of the hold");
        assertFalse(NickRules.held(false, false, now - 14 * day - 1, now, hold), "the hold ran out");
        assertFalse(NickRules.held(false, false, 0L, now, hold), "never seen able to show it");
        assertFalse(NickRules.held(true, true, now, now, hold), "a real player's name now: the holder can never show it");
        assertFalse(NickRules.held(false, false, now - 1, now, Duration.ZERO), "no hold: free once it can't show");
        assertTrue(NickRules.held(false, false, now, now, Duration.ZERO), "seen this very moment");
    }

    @Test
    void aNicknameNobodyHoldsIsNotTaken() {
        // The nickname lookup only names holders who still hold it; a stale one is left out and the name is free.
        assertEquals(NickRules.Problem.OK, RULES.check(ALEX, "Shadow", lookup(Map.of()), name -> Optional.empty(), text -> false).problem());
        // Alex stored the nickname "sam" before Sam joined: shadowed by Sam's real name, Alex no longer holds it.
        boolean alexHolds = NickRules.held(true, true, 1L, 1L, Duration.ofDays(14));
        assertFalse(alexHolds);
        assertEquals(NickRules.Problem.OK, RULES.check(SAM, "SAM", lookup(Map.of("sam", SAM)),
            name -> alexHolds ? Optional.of(ALEX) : Optional.empty(), text -> false).problem(),
            "a real player can take their own name back from a nickname that can't show any more");
        assertEquals(NickRules.Problem.TAKEN, RULES.check(SAM, "Shadow", lookup(Map.of()), lookup(Map.of("shadow", ALEX)), text -> false)
            .problem(), "a held nickname stays taken");
    }
}
