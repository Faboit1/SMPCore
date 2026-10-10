package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Custom join and leave messages: cleaning, the rules and where the name goes. */
class JoinTextTest {

    private static final Predicate<String> NO_LINK = text -> text.contains(".net") || text.contains("http");
    private static final Predicate<String> FILTER = text -> text.toLowerCase().contains("idiot");

    private static JoinText.Problem check(String message) {
        return JoinText.check(message, 40, NO_LINK, FILTER);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void cleaningRemovesFormattingAndSqueezesSpaces() {
        assertEquals("rolls in", JoinText.clean("  rolls \t  in \n"));
        assertEquals("acbc", JoinText.clean("a§cb​c"), "the legacy colour sign and invisible characters go");
        assertEquals("", JoinText.clean(null));
    }

    @Test
    void acceptsShortFriendlyMessages() {
        assertEquals(JoinText.Problem.OK, check("rolls in"));
        assertEquals(JoinText.Problem.OK, check("Make way, {name} is here"));
        assertEquals(JoinText.Problem.OK, check("{name} has arrived."));
    }

    @Test
    void refusesWhatShouldNotReachEveryone() {
        assertEquals(JoinText.Problem.EMPTY, check(""));
        assertEquals(JoinText.Problem.EMPTY, check("{name}"), "only the name says nothing");
        assertEquals(JoinText.Problem.TOO_LONG, check("x".repeat(41)));
        assertEquals(JoinText.Problem.OK, check("x".repeat(40)));
        assertEquals(JoinText.Problem.TOKENS, check("{name} and {name}"));
        assertEquals(JoinText.Problem.CHARACTERS, check("{player} is here"));
        assertEquals(JoinText.Problem.CHARACTERS, check("<red>hacked"));
        assertEquals(JoinText.Problem.LINK, check("join play.other.net"));
        assertEquals(JoinText.Problem.FILTERED, check("{name} the IDIOT"));
    }

    @Test
    void theNameGoesWhereTheTokenIsOrFirst() {
        assertEquals("{name} rolls in", JoinText.withName("rolls in"));
        assertEquals("Make way, {name}", JoinText.withName("Make way, {name}"));
        Component name = Component.text("Alex", NamedTextColor.GOLD);
        Component line = JoinText.render("Make way, {name} is here", name);
        assertEquals("Make way, Alex is here", plain(line));
        assertEquals(NamedTextColor.GOLD, line.children().get(1).color(), "the name keeps its own colour");
        assertEquals("Alex rolls in", plain(JoinText.render("rolls in", name)));
        assertEquals("<red> Alex", plain(JoinText.render("<red> {name}", name)), "player text is never parsed");
        assertEquals(2, JoinText.count("{name}{name}"));
    }
}
