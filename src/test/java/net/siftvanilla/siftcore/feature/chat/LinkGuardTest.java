package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinkGuardTest {

    private static final LinkGuard GUARD = new LinkGuard(new HashSet<>(ChatSettings.DEFAULT_TOP_LEVEL_DOMAINS),
        List.of("siftvanilla.com", "discord.gg/siftvanilla"));

    private static List<String> found(String text) {
        return GUARD.apply(text, ChatFilter.Action.BLOCK, "***").matched();
    }

    @Test
    void findsLinksIpAddressesAndDomains() {
        assertEquals(List.of("https://example.com/vote"), found("vote at https://example.com/vote please"));
        assertEquals(List.of("51.12.3.4:25565"), found("join 51.12.3.4:25565 now"));
        assertEquals(List.of("10.0.0.1"), found("ip is 10.0.0.1."));
        assertEquals(List.of("play.otherserver.net"), found("come to play.otherserver.net!"));
        assertEquals(List.of("PLAY.SHOUT.GG"), found("PLAY.SHOUT.GG"));
        assertEquals(List.of("discord.gg/other"), found("discord.gg/other"));
        assertEquals(List.of("mc.example.org:25566"), found("(mc.example.org:25566)"));
    }

    @Test
    void leavesNormalChatAlone() {
        assertTrue(found("on 1.21.5 the farm works").isEmpty(), "version numbers");
        assertTrue(found("e.g. i.e. etc. ok.so what").isEmpty(), "abbreviations");
        assertTrue(found("mail me at alex@example.com").isEmpty(), "e-mail addresses");
        assertTrue(found("open config.yml and file.txt").isEmpty(), "unknown endings");
        assertTrue(found("it costs 1.5k, then 2.25m").isEmpty(), "money");
        assertTrue(found("999.1.1.1 is not an address").isEmpty(), "octets over 255");
        assertTrue(found("I went home. Net worth is up").isEmpty(), "a sentence end and a word");
        assertTrue(found("1.2.3.4.5 is a list").isEmpty(), "five numbers");
    }

    @Test
    void theAllowListPassesTheServersOwnAddresses() {
        assertTrue(found("rules at https://siftvanilla.com/rules").isEmpty());
        assertTrue(found("buy at store.siftvanilla.com").isEmpty(), "subdomains of an allowed domain");
        assertTrue(found("join discord.gg/siftvanilla").isEmpty(), "the allowed invite");
        assertTrue(found("www.siftvanilla.com").isEmpty());
        assertEquals(List.of("discord.gg"), found("discord.gg"), "an entry with a page allows only that page");
        assertEquals(List.of("siftvanilla.com.evil.com"), found("siftvanilla.com.evil.com"), "not a suffix trick");
        assertEquals(List.of("fakesiftvanilla.com"), found("fakesiftvanilla.com"), "not a lookalike");
    }

    @Test
    void replacesOnlyTheAddresses() {
        LinkGuard.Result result = GUARD.apply("join play.other.net or 1.2.3.4 now", ChatFilter.Action.REPLACE, "***");
        assertEquals("join *** or *** now", result.text());
        assertTrue(result.changed());
        assertFalse(result.blocked());
        LinkGuard.Result blocked = GUARD.apply("join play.other.net", ChatFilter.Action.BLOCK, "***");
        assertTrue(blocked.blocked());
        assertEquals("join play.other.net", blocked.text());
    }

    @Test
    void aLinkIsOneMatchNotTwo() {
        assertEquals(List.of("https://www.example.com/a"), found("https://www.example.com/a"));
    }

    @Test
    void parsesAllowListEntries() {
        assertEquals(new LinkGuard.Allowed("example.net", ""), LinkGuard.allowed("https://www.Example.net/"));
        assertEquals(new LinkGuard.Allowed("discord.gg", "/name"), LinkGuard.allowed("discord.gg/name"));
        assertNull(LinkGuard.allowed("not an address"));
        assertNull(LinkGuard.allowed("localhost"));
    }

    @Test
    void offLetsEverythingThrough() {
        assertTrue(LinkGuard.none().apply("play.other.net 1.2.3.4", ChatFilter.Action.BLOCK, "***").clean());
        assertFalse(LinkGuard.none().enabled());
        assertTrue(GUARD.enabled());
    }
}
