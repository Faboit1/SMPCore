package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Cleaning of team chat text before it is shown to others. */
class TeamChatTextTest {

    @Test
    void controlFormattingAndSectionSignsAreRemoved() {
        assertEquals("hello team", TeamChat.clean("  hello\u0000 team​ "));
        assertEquals("cred text", TeamChat.clean("§cred text"));
        assertEquals("<red>stays literal</red>", TeamChat.clean("<red>stays literal</red>"),
            "tags are kept as text; they are inserted literally, never parsed");
        assertEquals("", TeamChat.clean(null));
        assertEquals("", TeamChat.clean(" \t\n "));
    }
}
