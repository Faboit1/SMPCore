package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled text and config load without a single problem, follow the design rules and read well. */
class FriendsResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = FriendsResourcesTest.class.getClassLoader().getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    private static Lang lang() throws Exception {
        Set<String> index;
        try (InputStream in = FriendsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            index = Icons.readIndex(in);
        }
        Icons icons = new Icons(index);
        assertEquals(Set.of(), icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()));
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), () -> null);
        lang.register(FriendsMessages.class);
        YamlConfiguration friends = yaml("lang/friends.yml");
        List<ConfigProblem> problems = lang.load(friends, friends, "lang/friends.yml");
        assertEquals(List.of(), problems);
        return lang;
    }

    @Test
    void everyMessageExistsAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        for (MessageKey key : lang.registered().values()) {
            String text = lang.plain(key);
            assertFalse(text.equals(key.path()), key.path() + " has text");
            assertFalse(text.contains(" ."), key.path() + " has a stray space before a full stop: " + text);
            assertFalse(text.contains(" ,"), key.path() + " has a stray space before a comma: " + text);
            assertFalse(text.equals(text.toUpperCase()) && text.chars().anyMatch(Character::isLetter) && text.length() > 3,
                key.path() + " is in capitals");
        }
    }

    @Test
    void messagesReadWell() throws Exception {
        Lang lang = lang();
        assertEquals("Friend request sent to Alex.", lang.plain(FriendsMessages.REQUEST_SENT, Arg.text("name", "Alex")));
        assertEquals("Alex sent you a friend request. Accept or Deny, or type /friend requests.",
            lang.plain(FriendsMessages.ALERT_REQUEST, Arg.text("name", "Alex"), Arg.component("accept", Component.text("Accept")),
                Arg.component("deny", Component.text("Deny"))));
        assertEquals("New friend requests: 3. View, or type /friend requests.",
            lang.plain(FriendsMessages.ALERT_REQUESTS, Arg.number("count", 3), Arg.component("view", Component.text("View"))));
        assertEquals("Friend requests waiting: 2. View, or type /friend requests.",
            lang.plain(FriendsMessages.SUMMARY_REQUESTS, Arg.number("count", 2), Arg.component("view", Component.text("View"))));
        assertEquals("Your friend list is full (50). Ranks raise this limit.",
            lang.plain(FriendsMessages.REQUEST_SENDER_FULL, Arg.number("limit", 50)));
        assertEquals("You have 20 requests waiting. Cancel some with /friend requests.",
            lang.plain(FriendsMessages.REQUEST_OUTGOING_FULL, Arg.number("count", 20)));
        assertEquals("You can send friend requests in 9m 30s.", lang.plain(FriendsMessages.REQUEST_TOO_NEW,
            Arg.time("time", Duration.ofSeconds(570))));
        assertEquals("2 of 5 online, 5 of 50 friends.", lang.plain(FriendsMessages.LIST_SUMMARY, Arg.number("online", 2),
            Arg.number("total", 5), Arg.number("limit", 50)));
        assertEquals("Alex, seen 3d ago", lang.plain(FriendsMessages.LIST_ROW, Arg.text("name", "Alex"),
            Arg.text("status", lang.plain(FriendsMessages.STATUS_SEEN, Arg.text("ago", "3d")))));
        assertEquals("Cancel: Cara, 1d ago", lang.plain(FriendsMessages.REQUESTS_OUTGOING_ROW, Arg.text("name", "Cara"),
            Arg.text("ago", "1d")));
        assertEquals("Remove Alex? They won't be told.", lang.plain(FriendsMessages.PROFILE_REMOVE_BODY, Arg.text("name", "Alex")));
        assertEquals("Your note: <b>x</b>", lang.plain(FriendsMessages.PROFILE_NOTE, Arg.text("note", "<b>x</b>")),
            "player text stays literal");
        assertEquals("Requests (4)", lang.plain(FriendsMessages.LIST_REQUESTS, Arg.number("count", 4)));
        assertEquals("Mutual friends: 3 (Bob, Cara and 1 more)", lang.plain(FriendsMessages.PROFILE_MUTUAL_NAMES,
            Arg.number("count", 3), Arg.text("names", "Bob, Cara and 1 more")));
        assertEquals("Your friend list is full (500).", lang.plain(FriendsMessages.REQUEST_SENDER_FULL_MAX, Arg.number("limit", 500)),
            "no rank hint at the hard cap");
        assertEquals("Your favourites are full (1).", lang.plain(FriendsMessages.PROFILE_FAVOURITES_FULL, Arg.number("count", 1)));
        assertEquals("Alex went offline.", lang.plain(FriendsMessages.ALERT_OFFLINE, Arg.component("name", Component.text("Alex"))));
        assertEquals("Page 2 of 3.", lang.plain(FriendsMessages.PAGE, Arg.number("page", 2), Arg.number("pages", 3)));
        assertEquals("Cancel your request to Cara?", lang.plain(FriendsMessages.REQUESTS_CANCEL_BODY, Arg.text("name", "Cara")));
        assertEquals("Use one of these for requests: everyone, known, nobody.", lang.plain(FriendsMessages.SETTINGS_UNKNOWN_VALUE,
            Arg.text("key", "requests"), Arg.text("values", "everyone, known, nobody")));
        assertEquals("That didn't work. Run /tpa Alex instead.", lang.plain(FriendsMessages.LINK_COMMAND,
            Arg.component("command", Component.text("/tpa Alex"))));
    }

    @Test
    void settingsLabelsFitTheirButtons() throws Exception {
        // A choice renders as "Label: Option" in a 250 px button; longer text scrolls. 40 characters stay well inside.
        Lang lang = lang();
        for (MessageKey label : List.of(FriendsMessages.SETTINGS_REQUESTS, FriendsMessages.SETTINGS_JOIN_ALERTS)) {
            for (MessageKey option : List.of(FriendsMessages.SETTINGS_REQUESTS_EVERYONE, FriendsMessages.SETTINGS_REQUESTS_KNOWN,
                FriendsMessages.SETTINGS_REQUESTS_NOBODY, FriendsMessages.SETTINGS_JOIN_ALERTS_ALL,
                FriendsMessages.SETTINGS_JOIN_ALERTS_FAVOURITES, FriendsMessages.SETTINGS_JOIN_ALERTS_OFF)) {
                String shown = lang.plain(label) + ": " + lang.plain(option);
                assertTrue(shown.length() <= 40, "too long for its button: " + shown);
            }
        }
    }

    @Test
    void bundledConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/friends.yml", yaml("features/friends.yml"));
        FriendsSettings settings = FriendsSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(50, settings.defaultLimit());
        assertEquals(500, settings.hardCap());
        assertEquals(10, settings.favourites());
        assertEquals(Duration.ofDays(7), settings.expireAfter());
        assertEquals(Duration.ofDays(7), settings.denyMemory());
        assertEquals(20, settings.maxOutgoing());
        assertEquals(50, settings.maxIncoming());
        assertEquals(5, settings.perMinute());
        assertEquals(30, settings.perDay());
        assertEquals(Duration.ofMinutes(10), settings.minAccountAge());
        assertTrue(settings.blockWhileVanished());
        assertEquals(Duration.ofSeconds(3), settings.summaryDelay());
        assertEquals(Duration.ofSeconds(3), settings.joinDelay());
        assertEquals(Duration.ofMinutes(2), settings.relogGrace());
        assertEquals(Duration.ofSeconds(30), settings.leaveDelay());
        assertEquals(Duration.ofSeconds(60), settings.startupQuiet());
        assertEquals(Duration.ofSeconds(10), settings.requestBatch());
        assertEquals(16, settings.pageSize());
        assertEquals(6, settings.suggestions());
        assertTrue(settings.sneakClick());
        assertEquals(Duration.ofSeconds(1), settings.sneakClickCooldown());
        assertEquals(Duration.ofSeconds(60), settings.memoryGrace());
        assertEquals(Duration.ofDays(7), settings.antiFarmRemember());
        assertEquals(Duration.ofDays(90), settings.logKeep());
        assertEquals(75, settings.limit(75));
        assertEquals(500, settings.limit(FriendRules.UNLIMITED));
    }

    @Test
    void badValuesFallBackAndAreReported() throws Exception {
        YamlConfiguration yaml = yaml("features/friends.yml");
        yaml.set("limits.default", 900);
        yaml.set("list.page-size", 2);
        yaml.set("log.keep", "1d");
        ConfigReader reader = new ConfigReader("features/friends.yml", yaml);
        FriendsSettings settings = FriendsSettings.parse(reader);
        assertEquals(3, reader.problems().size(), reader.problems().toString());
        assertEquals(50, settings.defaultLimit());
        assertEquals(16, settings.pageSize());
        assertEquals(settings.antiFarmRemember(), settings.logKeep(), "the history keeps at least the anti-farm memory");
    }

    @Test
    void selfTestSpotChecksPass() {
        assertNull(FriendsSelfTest.decisions());
        assertNull(FriendsSelfTest.notes());
    }
}
