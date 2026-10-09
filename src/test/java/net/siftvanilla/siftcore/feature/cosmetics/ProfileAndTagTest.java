package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** Profiles keep choices; tags decide who may pick them, monthly exclusives included. */
class ProfileAndTagTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private static ChatTag tag(String id, YearMonth month) {
        return new ChatTag(id, id, Component.text(id), id, "", "siftcore.tags.tycoon", month, "");
    }

    @Test
    void profilesChangeOneThingAtATime() {
        Profile profile = Profile.EMPTY.withChatStyle(ChatStyle.parse("gold")).withTag("miner").withKillEffect("hearts")
            .withJoinMessage("rolls in").withLeaveMessage("{name} left the building");
        assertEquals("gold", profile.chatStyle().serialize());
        assertEquals("miner", profile.tag());
        assertEquals("hearts", profile.killEffect());
        assertNull(profile.nick());
        Profile nicked = profile.withNick("Shadow", ChatStyle.parse("#FF6AD5:#B26BFF"), 1_000L);
        assertEquals("Shadow", nicked.nick());
        assertEquals(1_000L, nicked.nickSeen());
        assertEquals("gold", nicked.chatStyle().serialize(), "other choices are kept");
        assertEquals(0L, nicked.withNick(null, nicked.nickStyle(), 5_000L).nickSeen(), "no nickname, no hold");
        assertEquals(2_000L, nicked.withNickSeen(2_000L).nickSeen());
        assertFalse(Profile.EMPTY.withNickLost("Shadow").empty(), "a lost nickname is kept until the player is told");
        assertTrue(Profile.EMPTY.withNickLost("Shadow").withNickLost(null).empty());
        assertTrue(Profile.EMPTY.empty());
        assertFalse(profile.empty());
        assertTrue(profile.withChatStyle(ChatStyle.NONE).withTag(null).withKillEffect(" ").withJoinMessage(null).withLeaveMessage("").empty());
    }

    @Test
    void aResetKeepsOnlyOwnedTags() {
        Profile full = Profile.EMPTY.withChatStyle(ChatStyle.parse("gold")).withNick("Shadow", ChatStyle.parse("aqua"), 5L).withTag("spooky")
            .withOwnedTag("spooky").withJoinMessage("rolls in").withLeaveMessage("bye").withKillEffect("totem").withNickLost("Old");
        Profile reset = full.reset();
        assertEquals(Set.of("spooky"), reset.ownedTags());
        assertEquals(Profile.EMPTY.withOwnedTag("spooky"), reset);
        assertTrue(Profile.EMPTY.withTag("miner").reset().empty());
        assertEquals(Set.of("festive"), Profile.EMPTY.withOwnedTag("spooky").withOwnedTag("festive").withoutOwnedTag("spooky").ownedTags());
    }

    @Test
    void describeListsEveryChoiceForTheAuditLog() {
        Profile full = Profile.EMPTY.withChatStyle(ChatStyle.parse("#55FFFF:#5555FF")).withNick("Shadow", ChatStyle.parse("gold"), 5L)
            .withTag("mogul").withOwnedTag("spooky").withOwnedTag("festive").withJoinMessage("{name} rolls in").withLeaveMessage("bye")
            .withKillEffect("hearts");
        assertEquals("chat=#55FFFF:#5555FF; nick=Shadow gold; tag=mogul; owned=festive,spooky; join='{name} rolls in'; leave='bye'; "
            + "effect=hearts", full.describe());
        assertEquals("nick=Shadow", Profile.EMPTY.withNick("Shadow", ChatStyle.NONE, 1L).describe());
        assertEquals("nothing", Profile.EMPTY.describe());
    }

    @Test
    void ownedTagsAreStoredSortedAndParsedSafely() {
        Profile profile = Profile.EMPTY.withOwnedTag("spooky").withOwnedTag("festive");
        assertEquals("festive,spooky", profile.ownedTagsText());
        assertEquals(Set.of("festive", "spooky"), Profile.parseOwnedTags("festive, spooky,,Bad Tag,"));
        assertEquals(Set.of(), Profile.parseOwnedTags(null));
    }

    @Test
    void aMonthlyExclusiveIsOnlyPickedInItsMonthAndThenKept() {
        ChatTag spooky = tag("spooky", OCTOBER);
        assertTrue(spooky.usable(true, Set.of(), OCTOBER), "Tycoon in October");
        assertFalse(spooky.usable(false, Set.of(), OCTOBER), "no rank");
        assertFalse(spooky.usable(true, Set.of(), NOVEMBER), "too late without having picked it");
        assertTrue(spooky.usable(false, Set.of("spooky"), NOVEMBER), "picked in October: kept for good, even without the rank");
        assertTrue(spooky.claimable(OCTOBER));
        assertFalse(spooky.claimable(NOVEMBER));
        assertTrue(spooky.shownLocked(OCTOBER));
        assertFalse(spooky.shownLocked(NOVEMBER), "a missed exclusive is not advertised");
    }

    @Test
    void anOrdinaryTagFollowsThePermission() {
        ChatTag miner = tag("miner", null);
        assertTrue(miner.usable(true, Set.of(), NOVEMBER));
        assertFalse(miner.usable(false, Set.of(), NOVEMBER), "an expired rank falls back to no tag");
        assertFalse(miner.claimable(OCTOBER));
        assertTrue(miner.shownLocked(NOVEMBER));
        assertTrue(ChatTag.validId("night-owl_2"));
        assertFalse(ChatTag.validId("Night Owl"));
    }

    @Test
    void killEffectsAreFoundById() {
        assertEquals(KillEffect.TOTEM, KillEffect.byId(" Totem "));
        assertNull(KillEffect.byId("fireworks"));
        assertNull(KillEffect.byId(null));
        assertEquals("lightning", KillEffect.LIGHTNING.id());
    }
}
