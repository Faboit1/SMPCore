package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Notes, names, times, list order and suggestions. */
class FriendTextTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void notesAreCleaned() {
        assertEquals("builds farms", NoteText.clean("  builds farms  "));
        assertEquals("credagreen", NoteText.clean("§cred\u0000§agreen"), "the section sign goes, so nothing turns into a colour");
        assertEquals("ab", NoteText.clean("a​b‮"), "zero width and direction marks");
        assertEquals("", NoteText.clean(null));
        assertEquals("", NoteText.clean("   "));
        assertEquals("<b>bold</b>", NoteText.clean("<b>bold</b>"), "kept literally, never parsed");
        String emoji = "😀".repeat(70);
        String cut = NoteText.clean(emoji);
        assertEquals(NoteText.MAX_LENGTH, cut.codePointCount(0, cut.length()), "cut by code points, never inside a pair");
        assertEquals(NoteText.MAX_LENGTH, NoteText.clean("x".repeat(65)).length());
    }

    @Test
    void labelsAreCutByCodePoints() {
        assertEquals("Legend", NoteText.cut("Legend", FriendStore.RANK_LABEL_LENGTH));
        String label = "a" + "😀".repeat(40);
        String cut = NoteText.cut(label, FriendStore.RANK_LABEL_LENGTH);
        assertEquals(FriendStore.RANK_LABEL_LENGTH, cut.codePointCount(0, cut.length()));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "never ends inside a pair");
        assertEquals(null, NoteText.cut(null, 5));
    }

    @Test
    void namesFollowOneRule() {
        assertTrue(PlayerNames.valid("Alex_99"));
        assertTrue(PlayerNames.valid(".Steve"), "Floodgate prefix");
        assertTrue(PlayerNames.valid("a"));
        assertFalse(PlayerNames.valid(""));
        assertFalse(PlayerNames.valid("has space"));
        assertFalse(PlayerNames.valid("x".repeat(18)));
        assertFalse(PlayerNames.valid("@a"));
        assertFalse(PlayerNames.valid(null));
    }

    @Test
    void timesAreShort() {
        assertEquals("1s", TimeText.ago(Duration.ZERO));
        assertEquals("40s", TimeText.ago(Duration.ofSeconds(40)));
        assertEquals("12m", TimeText.ago(Duration.ofMinutes(12).plusSeconds(50)));
        assertEquals("5h", TimeText.ago(Duration.ofHours(5).plusMinutes(59)));
        assertEquals("3d", TimeText.ago(Duration.ofDays(3).plusHours(23)));
        assertEquals("2h", TimeText.ago(1_000L, 1_000L + Duration.ofHours(2).toMillis()));
        assertEquals("1s", TimeText.ago(5_000L, 1_000L), "never negative");
        assertEquals("8 Oct 2026", TimeText.date(1_791_417_600_000L, ZoneId.of("UTC")));
    }

    @Test
    void namesJoinNaturally() {
        List<Component> names = List.of(Component.text("Alex"), Component.text("Bob"), Component.text("Cara"), Component.text("Dan"));
        assertEquals("Alex", plain(NameList.join(names.subList(0, 1), 2, "and", n -> n + " more")));
        assertEquals("Alex and Bob", plain(NameList.join(names.subList(0, 2), 2, "and", n -> n + " more")));
        assertEquals("Alex, Bob and 1 more", plain(NameList.join(names.subList(0, 3), 2, "and", n -> n + " more")));
        assertEquals("Alex, Bob and 2 more", plain(NameList.join(names, 2, "and", n -> n + " more")));
        assertEquals("Alex, Bob, Cara and Dan", plain(NameList.join(names, 5, "and", n -> n + " more")));
        assertEquals("", plain(NameList.join(List.of(), 2, "and", n -> n + " more")));
    }

    @Test
    void listOrder() {
        long now = 10_000_000L;
        ListOrder.Row favOnline = new ListOrder.Row(UUID.randomUUID(), "zed", true, ListOrder.Status.ONLINE, now, 1);
        ListOrder.Row afk = new ListOrder.Row(UUID.randomUUID(), "Amy", false, ListOrder.Status.AFK, now, 1);
        ListOrder.Row online = new ListOrder.Row(UUID.randomUUID(), "bob", false, ListOrder.Status.ONLINE, now, 1);
        ListOrder.Row favOld = new ListOrder.Row(UUID.randomUUID(), "Ann", true, ListOrder.Status.OFFLINE, now - 5_000, 1);
        ListOrder.Row favRecent = new ListOrder.Row(UUID.randomUUID(), "Zoe", true, ListOrder.Status.OFFLINE, now - 1_000, 1);
        ListOrder.Row recent = new ListOrder.Row(UUID.randomUUID(), "Cid", false, ListOrder.Status.OFFLINE, now - 2_000, 1);
        ListOrder.Row old = new ListOrder.Row(UUID.randomUUID(), "Abe", false, ListOrder.Status.OFFLINE, now - 9_000, 1);
        List<ListOrder.Row> sorted = ListOrder.sort(List.of(old, recent, favOld, online, afk, favRecent, favOnline));
        assertEquals(List.of(favOnline, afk, online, favRecent, favOld, recent, old), sorted);
        assertEquals(3, ListOrder.online(sorted));
        assertEquals(List.of(afk, favOld, old), ListOrder.filter(sorted, "a"));
        assertEquals(sorted, ListOrder.filter(sorted, " "));
        assertEquals(1, ListOrder.pages(0, 16));
        assertEquals(2, ListOrder.pages(17, 16));
        assertEquals(List.of(favOld, recent), ListOrder.page(sorted, 3, 2));
        assertEquals(List.of(old), ListOrder.page(sorted, 4, 2));
        assertEquals(List.of(old), ListOrder.page(sorted, 99, 2), "past the end shows the last page");
        assertEquals(List.of(favOnline, afk), ListOrder.page(sorted, 0, 2));
    }

    @Test
    void suggestionsRankBySharedFriends() {
        UUID me = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        java.util.Set<UUID> mine = java.util.Set.of(f1, f2);
        UUID two = UUID.randomUUID();
        UUID one = UUID.randomUUID();
        UUID mate = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID shy = UUID.randomUUID();
        UUID choosy = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        List<Suggestions.Candidate> candidates = List.of(
            new Suggestions.Candidate(one, "One", java.util.Set.of(f1), false, Privacy.EVERYONE, false),
            new Suggestions.Candidate(two, "Two", java.util.Set.of(f1, f2), false, Privacy.EVERYONE, false),
            new Suggestions.Candidate(mate, "Mate", java.util.Set.of(), true, Privacy.KNOWN, false),
            new Suggestions.Candidate(stranger, "Stranger", java.util.Set.of(), false, Privacy.EVERYONE, false),
            new Suggestions.Candidate(shy, "Shy", java.util.Set.of(f1, f2), false, Privacy.NOBODY, false),
            new Suggestions.Candidate(choosy, "Choosy", java.util.Set.of(f2), false, Privacy.KNOWN, false),
            new Suggestions.Candidate(blocked, "Blocked", java.util.Set.of(f1, f2), false, Privacy.EVERYONE, true),
            new Suggestions.Candidate(f1, "Friend", java.util.Set.of(f2), false, Privacy.EVERYONE, false),
            new Suggestions.Candidate(me, "Me", java.util.Set.of(f1, f2), false, Privacy.EVERYONE, false));
        List<Suggestions.Suggestion> ranked = Suggestions.rank(me, mine, candidates, 6);
        assertEquals(List.of(two, choosy, one, mate), ranked.stream().map(Suggestions.Suggestion::id).toList());
        assertEquals(2, ranked.getFirst().mutual());
        assertEquals(2, Suggestions.rank(me, mine, candidates, 2).size());
        assertTrue(Suggestions.rank(me, mine, candidates, 0).isEmpty(), "0 turns them off");
    }

    @Test
    void privacyAndSettingValues() {
        assertEquals(Privacy.KNOWN, Privacy.parse(" known "));
        assertEquals(Privacy.EVERYONE, Privacy.parse("garbage"), "invalid values read as the default");
        assertEquals(Privacy.EVERYONE, Privacy.parse(null));
        assertEquals(FriendPrefs.JoinAlerts.ALL, FriendPrefs.JoinAlerts.parse("nope"));
        assertEquals(FriendPrefs.JoinAlerts.FAVOURITES, FriendPrefs.JoinAlerts.parse("favourites"));
        assertEquals(FriendPrefs.AutoTpa.NOBODY, FriendPrefs.AutoTpa.parse(null));
        assertEquals(FriendPrefs.AutoTpa.ALL, FriendPrefs.AutoTpa.parse("ALL"));
        assertEquals(null, FriendPrefs.Key.parse("tpa"), "teleport auto-accept is not offered while TPA has its own toggle");
        assertEquals(FriendPrefs.Key.JOIN_ALERTS, FriendPrefs.Key.parse("Join-Alerts"));
        assertEquals(List.of("everyone", "known", "nobody"), FriendPrefs.Key.REQUESTS.options());
        assertEquals(null, FriendPrefs.Key.parse("colour"));
    }
}
