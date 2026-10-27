package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFilterTest {

    private static ChatFilter filter(boolean leetspeak, boolean joinLetters, String... words) {
        List<ChatFilter.Entry> entries = new ArrayList<>();
        for (String word : words) {
            String[] problem = new String[1];
            ChatFilter.Entry entry = ChatFilter.entry(word, leetspeak, problem);
            assertNotNull(entry, word + ": " + problem[0]);
            entries.add(entry);
        }
        return new ChatFilter(entries, leetspeak, joinLetters);
    }

    private static String replace(ChatFilter filter, String text) {
        return filter.apply(text, ChatFilter.Action.REPLACE, "***").text();
    }

    @Test
    void normalisationKeepsPositions() {
        String text = "H3LL0 W0rld! Çà $uçks";
        String normalized = ChatFilter.normalize(text, true);
        assertEquals(text.length(), normalized.length(), "one character for one character");
        assertEquals("hello worldi ca sucks", normalized);
    }

    @Test
    void normalisationWithoutLeetspeakOnlyLowercasesAndStripsAccents() {
        assertEquals("h3ll0 ea", ChatFilter.normalize("H3LL0 Éa", false));
        assertEquals("full", ChatFilter.normalize("ｆｕｌｌ", false), "full-width letters fold to ASCII");
    }

    @Test
    void wholeWordsOnlyIgnoringCase() {
        ChatFilter filter = filter(false, false, "ass");
        assertEquals("you ***", replace(filter, "you ASS"));
        assertEquals("class assignment passes", replace(filter, "class assignment passes"), "never inside words");
        assertEquals("***, ***!", replace(filter, "ass, Ass!"));
    }

    @Test
    void leetspeakAndSymbolsAreCaught() {
        ChatFilter filter = filter(true, false, "kill");
        assertEquals("i will ***", replace(filter, "i will k1ll"));
        assertEquals("***", replace(filter, "K!LL"));
        assertEquals("killer", replace(filter, "killer"), "a longer word is not the entry");
        assertEquals("i will k1ll", replace(filter(false, false, "kill"), "i will k1ll"), "no leetspeak, no match");
    }

    @Test
    void symbolsAreAlsoReadAsPunctuation() {
        ChatFilter filter = filter(true, true, "kys", "kill yourself", "ass");
        assertEquals("***!", replace(filter, "kys!"));
        assertEquals("just ***!!", replace(filter, "just kill yourself!!"));
        assertEquals("you ***", replace(filter, "you a$$"), "a symbol inside a word is a letter");
        assertEquals("|***|", replace(filter, "|kys|"));
        assertEquals("hi! how are you", replace(filter, "hi! how are you"));
    }

    @Test
    void prefixEntriesMatchLongerWords() {
        ChatFilter filter = filter(true, false, "idiot*");
        assertEquals("you *** and ***", replace(filter, "you idiots and 1d10t"));
        assertEquals("anidiot", replace(filter, "anidiot"), "the prefix is still a word start");
    }

    @Test
    void phrasesMatchConsecutiveWords() {
        ChatFilter filter = filter(true, false, "kill yourself");
        assertEquals("just ***.", replace(filter, "just kill   yourself."));
        assertEquals("kill the boss yourself", replace(filter, "kill the boss yourself"));
        assertEquals("go ***", replace(filter, "go KILL-YOURSELF"), "any separator between the words");
    }

    @Test
    void lettersTypedWithGapsAreJoined() {
        ChatFilter joined = filter(true, true, "kys");
        assertEquals("***", replace(joined, "k y s"));
        assertEquals("ok *** now", replace(joined, "ok k.y.s now"));
        assertEquals("k y", replace(joined, "k y"));
        assertEquals("k y s", replace(filter(true, false, "kys"), "k y s"), "only when the option is on");
    }

    @Test
    void blockReportsWhatMatchedAndKeepsTheText() {
        ChatFilter filter = filter(true, true, "kys", "idiot*");
        ChatFilter.Result result = filter.apply("idiots should k y s", ChatFilter.Action.BLOCK, "***");
        assertTrue(result.blocked());
        assertFalse(result.changed());
        assertEquals("idiots should k y s", result.text());
        assertEquals(List.of("idiots", "k y s"), result.matched());
    }

    @Test
    void overlappingMatchesAreReplacedOnce() {
        ChatFilter filter = filter(true, false, "kill", "kill yourself");
        ChatFilter.Result result = filter.apply("kill yourself now", ChatFilter.Action.REPLACE, "#");
        assertEquals("# now", result.text());
        assertEquals(List.of("kill yourself"), result.matched());
    }

    @Test
    void cleanMessagesAreUntouched() {
        ChatFilter filter = filter(true, true, "kys");
        ChatFilter.Result result = filter.apply("selling diamonds at spawn", ChatFilter.Action.REPLACE, "***");
        assertTrue(result.clean());
        assertEquals("selling diamonds at spawn", result.text());
        assertTrue(ChatFilter.none().apply("kys", ChatFilter.Action.BLOCK, "***").clean());
    }

    @Test
    void entriesWithoutLettersAreRejected() {
        String[] problem = new String[1];
        assertNull(ChatFilter.entry("   ", true, problem));
        assertEquals("is empty", problem[0]);
        assertNull(ChatFilter.entry("??", false, problem));
        assertNull(ChatFilter.entry("f*ck", false, problem), "a star only ends a word");
        assertNotNull(ChatFilter.entry("a$$", true, problem), "symbols are letters with leetspeak");
        assertNull(ChatFilter.entry("a$$", false, problem), "but not without");
    }

    @Test
    void tokensAndJoinedLetters() {
        List<ChatFilter.Token> tokens = ChatFilter.tokens("a b c dd e f");
        assertEquals(6, tokens.size());
        List<ChatFilter.Token> joined = ChatFilter.joinedLetters(tokens);
        assertEquals(1, joined.size(), "a b c joins, e f is too short");
        assertEquals(new ChatFilter.Token("abc", 0, 5), joined.getFirst());
    }
}
