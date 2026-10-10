package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Blocked commands: namespaces, aliases (both ways), subcommands, odd spacing and near misses. */
class CommandFilterTest {

    /** A fake command map: /h and /homes are aliases of /home, /t of /team, /tp of /teleport. */
    private static final Map<String, Set<String>> COMMANDS = Map.of(
        "home", Set.of("home", "h", "homes"),
        "h", Set.of("home", "h", "homes"),
        "homes", Set.of("home", "h", "homes"),
        "team", Set.of("team", "t"),
        "t", Set.of("team", "t"),
        "teleport", Set.of("teleport", "tp"),
        "tp", Set.of("teleport", "tp"));

    private static final Function<String, Set<String>> NAMES = label -> COMMANDS.getOrDefault(label, Set.of(label));

    private static CommandFilter filter(String... entries) {
        return new CommandFilter(java.util.Arrays.stream(entries).map(CommandFilter.Rule::parse).toList());
    }

    @Test
    void plainCommandsWithAndWithoutArguments() {
        CommandFilter filter = filter("spawn", "home");
        assertTrue(filter.blocks("/spawn", NAMES));
        assertTrue(filter.blocks("/home base", NAMES));
        assertTrue(filter.blocks("/HOME Base", NAMES), "case does not matter");
        assertTrue(filter.blocks("   /home    base  ", NAMES), "extra spaces do not matter");
        assertTrue(filter.blocks("home", NAMES), "the slash is optional");
        assertFalse(filter.blocks("/balance", NAMES));
        assertFalse(filter.blocks("/", NAMES));
        assertFalse(filter.blocks("", NAMES));
        assertFalse(filter.blocks(null, NAMES));
    }

    @Test
    void namespacesAreIgnored() {
        CommandFilter filter = filter("home", "tp");
        assertTrue(filter.blocks("/siftcore:home", NAMES));
        assertTrue(filter.blocks("/minecraft:tp Alex", NAMES));
        assertTrue(filter.blocks("/essentials:home base", NAMES));
    }

    @Test
    void aliasesWorkBothWays() {
        assertTrue(filter("home").blocks("/h base", NAMES), "an alias of a blocked command");
        assertTrue(filter("homes").blocks("/home", NAMES), "a blocked alias blocks its command");
        assertTrue(filter("tp").blocks("/teleport Alex", NAMES));
        assertTrue(filter("home").blocks("/siftcore:h", NAMES), "namespaced alias");
    }

    @Test
    void subcommandsNeedTheirWords() {
        CommandFilter filter = filter("team home");
        assertTrue(filter.blocks("/team home", NAMES));
        assertTrue(filter.blocks("/t home", NAMES), "through the alias of the command");
        assertTrue(filter.blocks("/siftcore:team HOME now", NAMES));
        assertFalse(filter.blocks("/team", NAMES));
        assertFalse(filter.blocks("/team info", NAMES));
        assertFalse(filter.blocks("/team homes", NAMES), "words must match exactly");
    }

    @Test
    void nearMissesAreNotBlocked() {
        CommandFilter filter = filter("ec", "tpa", "home");
        assertFalse(filter.blocks("/eco give Alex 5", NAMES), "a prefix of another command");
        assertFalse(filter.blocks("/tpaccept", NAMES));
        assertFalse(filter.blocks("/sethome", NAMES));
        assertTrue(filter.blocks("/tpa Alex", NAMES));
    }

    @Test
    void theResolverIsOnlyAskedWhenNeeded() {
        int[] calls = {0};
        Function<String, Set<String>> counting = label -> {
            calls[0]++;
            return NAMES.apply(label);
        };
        CommandFilter filter = filter("spawn", "home", "team home");
        assertTrue(filter.blocks("/spawn", counting));
        assertEquals(0, calls[0], "a direct hit needs no lookup");
        assertFalse(filter.blocks("/balance", counting));
        assertEquals(1, calls[0], "one lookup per typed command at most");
        assertTrue(filter.blocks("/h", counting));
    }

    @Test
    void entriesAreParsedAndValidated() {
        assertEquals(List.of("home"), CommandFilter.Rule.parse("/home").words());
        assertEquals(List.of("home"), CommandFilter.Rule.parse("siftcore:home").words());
        assertEquals(List.of("team", "home"), CommandFilter.Rule.parse("  Team   Home ").words());
        assertThrows(IllegalArgumentException.class, () -> CommandFilter.Rule.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CommandFilter.Rule.parse("/"));
        assertThrows(IllegalArgumentException.class, () -> CommandFilter.Rule.parse("home <name>"));
        assertFalse(new CommandFilter(List.of()).blocks("/home", NAMES), "an empty list blocks nothing");
    }
}
