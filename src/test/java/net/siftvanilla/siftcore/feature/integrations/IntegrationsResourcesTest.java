package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled {@code features/integrations.yml} parses cleanly and {@code lang/integrations.yml} follows the design system. */
class IntegrationsResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = IntegrationsResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = IntegrationsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(IntegrationsMessages.class);
        return lang;
    }

    @Test
    void configParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/integrations.yml", yaml("features/integrations.yml"));
        IntegrationsSettings settings = IntegrationsSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        assertTrue(settings.placeholderApi());
        assertTrue(settings.floodgate());
        assertEquals("siftcore-rank", settings.luckPerms().labelMeta());
        assertEquals(Set.of("default"), settings.luckPerms().hiddenGroups());
        assertEquals(Duration.ofHours(24), settings.backups().interval());
        assertEquals(7, settings.backups().keep());
        assertEquals(100_000_000L, settings.store().maxMoney());
        assertEquals(Set.of("prospector", "baron", "tycoon"), settings.store().rankGroups());
        assertTrue(settings.store().allowsGroup("baron"));
        assertFalse(settings.store().allowsGroup("admin"), "staff groups can't be bought");
        assertTrue(settings.store().notifyPlayer());
        assertFalse(settings.store().announce());
    }

    @Test
    void badConfigValuesAreReported() throws Exception {
        YamlConfiguration yaml = yaml("features/integrations.yml");
        yaml.set("store.rank-groups", List.of("elite", "group.admin"));
        yaml.set("store.min-rank-duration", "10d");
        yaml.set("store.max-rank-duration", "1d");
        yaml.set("backups.interval", "soon");
        ConfigReader reader = new ConfigReader("features/integrations.yml", yaml);
        IntegrationsSettings settings = IntegrationsSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(3, reader.problems().size(), String.valueOf(reader.problems()));
        assertEquals(Set.of("elite"), settings.store().rankGroups());
        assertTrue(settings.store().maxRankDuration().compareTo(settings.store().minRankDuration()) >= 0);
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/integrations.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/integrations.yml");
        assertEquals(List.of(), problems);
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "Word")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + " leaves a tag behind: " + plain);
            assertFalse(plain.matches("(?s).*\\s[.,!?;:].*"), key.path() + " has a space before punctuation: " + plain);
            assertFalse(plain.isBlank(), key.path() + " is empty");
            String raw = String.join("\n", file.isList(key.path()) ? file.getStringList(key.path()) : List.of(file.getString(key.path())));
            assertFalse(raw.matches("(?s).*[A-Z]{3,}.*"), key.path() + " uses capitals: " + raw);
            assertFalse(raw.contains("!"), key.path() + " shouts: " + raw);
        }
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/integrations.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }

    @Test
    void storeMessagesReadNaturally() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/integrations.yml");
        lang.load(file, file, "lang/integrations.yml");
        assertEquals("Delivered $25,000 to Alex (ref tbx-1).", TextStyle.plain(lang.get(IntegrationsMessages.STORE_DELIVERED,
            Arg.component("what", lang.get(IntegrationsMessages.WHAT_MONEY, Arg.money("amount", 25_000))), Arg.text("name", "Alex"),
            Arg.text("ref", "tbx-1"))));
        assertEquals("Elite for 30d", TextStyle.plain(lang.get(IntegrationsMessages.WHAT_RANK, Arg.text("rank", "Elite"),
            Arg.time("time", Duration.ofDays(30)))));
        // Keys read the way the crates feature words them (CrateKeys.keysText): the crate's name and a singular.
        assertEquals("Your store purchase arrived: 1 Vote key. Open them with /crates.", TextStyle.plain(lang.get(
            IntegrationsMessages.NOTIFY_KEYS, Arg.component("keys", Component.text("1 Vote key")), Arg.number("amount", 1),
            Arg.text("crate", "vote"))));
        assertEquals("1 vote key", TextStyle.plain(lang.get(IntegrationsMessages.WHAT_KEYS,
            Arg.component("keys", CrateKeys.NONE.keysText("vote", 1)), Arg.number("amount", 1), Arg.text("crate", "vote"))));
    }
}
