package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MentionsTest {

    private static final List<String> ONLINE = List.of("Alex", "Steve_99", "ok", "Notch");

    @Test
    void atMentionsIgnoreCase() {
        assertEquals(Set.of("Alex"), Mentions.find("hey @ALEX come here", ONLINE, false, 3));
        assertEquals(Set.of("Steve_99"), Mentions.find("@steve_99!", ONLINE, false, 3));
    }

    @Test
    void plainNamesAsWholeWords() {
        assertEquals(Set.of("Alex", "Notch"), Mentions.find("alex and notch, look", ONLINE, true, 3));
        assertTrue(Mentions.find("alexander and notches", ONLINE, true, 3).isEmpty(), "never inside a longer word");
        assertTrue(Mentions.find("alex and notch", ONLINE, false, 3).isEmpty(), "only with @ when plain names are off");
    }

    @Test
    void shortNamesNeedTheAt() {
        assertTrue(Mentions.find("ok sure", ONLINE, true, 3).isEmpty());
        assertEquals(Set.of("ok"), Mentions.find("@ok sure", ONLINE, true, 3));
    }

    @Test
    void anAtInsideAWordIsNotAMention() {
        assertTrue(Mentions.find("mail me at bob@alex.net", ONLINE, false, 3).isEmpty());
        assertEquals(Set.of("Alex"), Mentions.find("(@alex)", ONLINE, false, 3));
        assertEquals(Set.of("Alex"), Mentions.find("@@alex", ONLINE, false, 3));
    }

    @Test
    void eachPlayerOnceInOrder() {
        assertEquals(List.of("Notch", "Alex"), List.copyOf(Mentions.find("@notch @alex notch alex", ONLINE, true, 3)));
    }

    @Test
    void nothingToFind() {
        assertTrue(Mentions.find("", ONLINE, true, 3).isEmpty());
        assertTrue(Mentions.find("@alex", List.of(), true, 3).isEmpty());
        assertTrue(Mentions.find("@ @ @", ONLINE, true, 3).isEmpty());
    }
}
