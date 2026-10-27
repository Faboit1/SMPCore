package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsSearchTest {

    private static final List<SettingsSearch.Doc> DOCS = List.of(
        new SettingsSearch.Doc("mentions", "Mention alerts",
            List.of("mentions", "mentions", "How you are told someone mentioned you", "Chat", "Above the hotbar", "Chat", "Title", "Off")),
        new SettingsSearch.Doc("private-messages", "Who can message me",
            List.of("private-messages", "private-messages", "Who can send you private messages", "Chat", "Everyone", "Friends", "Nobody")),
        new SettingsSearch.Doc("sound-volume", "Sound volume",
            List.of("sound-volume", "volume", "How loud menu clicks, chimes and pings are", "Sounds", "%")),
        new SettingsSearch.Doc("sound-mention", "Mention sound",
            List.of("sound-mention", "mention", "The sound when someone mentions you", "Sounds", "Default", "Bell", "Pling")),
        new SettingsSearch.Doc("seen-privacy", "Who sees when I was last online",
            List.of("seen-privacy", "seen-privacy", "Who can see when you were last online", "Privacy", "Everyone", "Friends", "Nobody")));

    @Test
    void textIsNormalizedToLowercaseWords() {
        assertEquals("sound volume", SettingsSearch.normalize("  Sound-VOLUME!! "));
        assertEquals("who can message me", SettingsSearch.normalize("Who can\tmessage me?"));
        assertEquals("", SettingsSearch.normalize(" -_- "));
        assertEquals(List.of("abc", "def"), SettingsSearch.words("ABC, def"));
        assertEquals(List.of(), SettingsSearch.words("   "));
        assertEquals(SettingsSearch.MAX_WORDS, SettingsSearch.words("a b c d e f g h i j k").size(), "extra words are ignored");
    }

    @Test
    void queriesAreCutToTheFormLength() {
        assertEquals("volume", SettingsSearch.clean("  volume  "));
        assertEquals(SettingsSearch.MAX_QUERY, SettingsSearch.clean("x".repeat(80)).length());
        assertEquals("", SettingsSearch.clean(null));
    }

    @Test
    void everyWordMustMatchSomewhere() {
        assertEquals(List.of("sound-volume"), SettingsSearch.match(DOCS, "volume"));
        assertEquals(List.of("sound-volume"), SettingsSearch.match(DOCS, "vol"), "part of a word");
        assertEquals(List.of("sound-volume"), SettingsSearch.match(DOCS, "sound volume"), "id words");
        assertEquals(List.of("sound-volume"), SettingsSearch.match(DOCS, "sound-volume"), "the id itself");
        assertEquals(List.of(), SettingsSearch.match(DOCS, "volume teleport"), "a word that matches nothing");
        assertEquals(List.of(), SettingsSearch.match(DOCS, ""), "an empty query finds nothing");
        assertEquals(List.of(), SettingsSearch.match(DOCS, "?!"), "nor punctuation alone");
    }

    @Test
    void optionLabelsDescriptionsAndGroupsAreSearched() {
        assertEquals(List.of("private-messages", "seen-privacy"), SettingsSearch.match(DOCS, "everyone"), "an option label");
        assertEquals(List.of("mentions"), SettingsSearch.match(DOCS, "hotbar"));
        assertEquals(List.of("sound-volume", "sound-mention"), SettingsSearch.match(DOCS, "SOUNDS"), "a group label, any case");
        assertEquals(List.of("seen-privacy"), SettingsSearch.match(DOCS, "last online"), "the description");
    }

    @Test
    void labelPrefixRanksFirstThenLabelThenTheRest() {
        // "mention": the label of Mention sound starts with it, Mention alerts too (dialog order between them), and
        // nothing else holds it in the label.
        List<String> mention = SettingsSearch.match(DOCS, "mention");
        assertEquals(List.of("mentions", "sound-mention"), mention);
        // "sound": the label of Sound volume starts with it, Mention sound only holds it.
        assertEquals(List.of("sound-volume", "sound-mention"), SettingsSearch.match(DOCS, "sound"));
        // "pling": no label holds it, only Mention sound's option.
        assertEquals(List.of("sound-mention"), SettingsSearch.match(DOCS, "pling"));
        // "who": both who-can settings start with it.
        assertEquals(List.of("private-messages", "seen-privacy"), SettingsSearch.match(DOCS, "who"));
        // "can": the label of Who can message me holds it (rank 1); Who sees... only in its description (rank 2).
        assertEquals(List.of("private-messages", "seen-privacy"), SettingsSearch.match(DOCS, "can"));
        assertTrue(SettingsSearch.match(DOCS, "friends").containsAll(List.of("private-messages", "seen-privacy")));
    }
}
