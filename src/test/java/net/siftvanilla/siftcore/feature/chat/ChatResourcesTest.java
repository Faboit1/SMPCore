package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped config and text load without a single problem and follow the design rules. */
class ChatResourcesTest {

    private static Lang lang;
    private static YamlConfiguration langYaml;

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = ChatResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void loadText() throws Exception {
        Icons icons = new Icons(Icons.readIndex(ChatResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(ChatMessages.class);
        langYaml = yaml("lang/chat.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/chat.yml");
        assertEquals(List.of(), problems);
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/chat.yml", yaml("features/chat.yml"));
        ChatSettings settings = ChatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertTrue(settings.hoverCard());
        assertTrue(settings.itemTag());
        assertEquals(new SpamGuard.Rules(200, Duration.ofSeconds(1), 5, Duration.ofSeconds(10), Duration.ofSeconds(30), 0.9, 3,
            0.6, 8, SpamGuard.CapsAction.LOWERCASE), settings.spam());
        assertEquals(3, settings.filter().size());
        assertEquals(ChatFilter.Action.REPLACE, settings.filterAction());
        assertEquals("***", settings.filterReplacement());
        assertTrue(settings.filterPrivate());
        assertTrue(settings.links().enabled());
        assertEquals(ChatFilter.Action.BLOCK, settings.linkAction());
        assertTrue(settings.linksPrivate());
        assertEquals(2, settings.links().allowedCount());
        assertTrue(settings.links().apply("join play.other.net", settings.linkAction(), "***").blocked());
        assertTrue(settings.links().apply("see discord.gg/siftvanilla and store.siftvanilla.net", settings.linkAction(), "***").clean());
        assertTrue(settings.mentions());
        assertTrue(settings.plainNameMentions());
        assertEquals(3, settings.minPlainLength());
        assertEquals(Duration.ofSeconds(3), settings.mentionCooldown());
        assertEquals(Duration.ofMinutes(10), settings.replyExpiry(), "the reply target expires after ten minutes");
        assertTrue(settings.logPrivate());
        assertEquals(100, settings.maxIgnores());
        assertEquals("you *** now", settings.filter().apply("you k y s now", settings.filterAction(), settings.filterReplacement()).text());
    }

    @Test
    void badValuesAreReportedPrecisely() throws Exception {
        YamlConfiguration broken = yaml("features/chat.yml");
        broken.set("anti-spam.max-length", 1000);
        broken.set("anti-spam.caps.action", "shout");
        broken.set("anti-spam.repeats.similarity", 0.1);
        broken.set("filter.words", List.of("kys", "???", "f*ck"));
        broken.set("private-messages.reply-expiry", "10s");
        broken.set("links.allowed", List.of("siftvanilla.net", "not an address"));
        broken.set("links.top-level-domains", List.of("net", ".gg", "c0m"));
        ConfigReader reader = new ConfigReader("features/chat.yml", broken);
        ChatSettings settings = ChatSettings.parse(reader);
        assertEquals(8, reader.problems().size(), reader.problems().toString());
        assertEquals(1, settings.links().allowedCount(), "valid addresses are kept");
        assertTrue(settings.links().apply("a.gg", ChatFilter.Action.BLOCK, "***").blocked(), "a leading dot is fine");
        assertTrue(settings.links().apply("a.com", ChatFilter.Action.BLOCK, "***").clean(), "only the listed endings");
        assertEquals(200, settings.spam().maxLength(), "fallback");
        assertEquals(SpamGuard.CapsAction.LOWERCASE, settings.spam().capsAction(), "fallback");
        assertEquals(0.9, settings.spam().similarity(), "fallback");
        assertEquals(1, settings.filter().size(), "valid words are kept");
        assertEquals(Duration.ofMinutes(10), settings.replyExpiry(), "fallback");
    }

    @Test
    void turningTheFilterOffLetsEverythingThrough() throws Exception {
        YamlConfiguration custom = yaml("features/chat.yml");
        custom.set("filter.enabled", false);
        custom.set("links.enabled", false);
        ConfigReader reader = new ConfigReader("features/chat.yml", custom);
        ChatSettings settings = ChatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertTrue(settings.filter().apply("kys", ChatFilter.Action.BLOCK, "***").clean());
        assertTrue(settings.links().apply("play.other.net", ChatFilter.Action.BLOCK, "***").clean());
    }

    @Test
    void everyLangEntryBelongsToAMessage() {
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("chat."))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inFile = langYaml.getKeys(true).stream().filter(path -> !langYaml.isConfigurationSection(path))
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(registered, inFile, "lang/chat.yml has exactly the registered messages");
    }

    @Test
    void playerTextIsInsertedLiterally() {
        Component line = lang.get(ChatMessages.FORMAT_UNRANKED, Arg.component("name", Component.text("Alex")),
            Arg.component("message", Component.text("<red>hi</red> <click:run_command:'/op me'>x")));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
        assertEquals("Alex: <red>hi</red> <click:run_command:'/op me'>x", plain);
        Component spy = lang.get(ChatMessages.PM_SPY, Arg.text("from", "<b>A"), Arg.text("to", "B"), Arg.component("message", Component.text("m")));
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(spy).contains("<b>A"));
    }

    @Test
    void designRules() {
        for (String path : langYaml.getKeys(true)) {
            if (langYaml.isConfigurationSection(path)) {
                continue;
            }
            List<String> values = langYaml.isList(path) ? langYaml.getStringList(path) : List.of(langYaml.getString(path));
            for (String value : values) {
                assertFalse(value.matches(".*\\s[.,!?:]\\s*$"), path + " has a space before punctuation: " + value);
                assertFalse(value.contains("<bold>") || value.contains("<b>"), path + " is bold");
                assertFalse(value.equals(value.toUpperCase()) && value.matches(".*[A-Z]{4,}.*"), path + " is in capitals");
            }
        }
    }
}
