package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsFormTest {

    @Test
    void toggleIdsBecomeValidUniqueKeys() {
        List<SettingsForm.Field> fields = SettingsForm.fields(List.of(
            Map.entry("tpa-requests", true),
            Map.entry("tpa_requests", false),
            Map.entry("death-messages", true),
            Map.entry("tpa-requests", true)));
        Set<String> keys = new HashSet<>();
        for (SettingsForm.Field field : fields) {
            assertTrue(field.key().matches("[A-Za-z0-9_]+"), field.key());
            assertTrue(keys.add(field.key()), "unique: " + field.key());
        }
        assertEquals("tpa_requests", fields.get(0).key());
        assertEquals("tpa_requests_2", fields.get(1).key());
        assertEquals("death_messages", fields.get(2).key());
        assertEquals("tpa_requests_3", fields.get(3).key());
        assertEquals("tpa_requests", fields.get(1).toggle(), "the field still knows its toggle");
    }

    @Test
    void onlyFlippedSwitchesAreSaved() {
        List<SettingsForm.Field> fields = SettingsForm.fields(List.of(
            Map.entry("mentions", true),
            Map.entry("private-messages", true),
            Map.entry("death-messages", false)));
        Map<String, Boolean> changes = SettingsForm.changes(fields, Map.of(
            "mentions", true,
            "private_messages", false,
            "death_messages", true));
        assertEquals(Map.of("private-messages", false, "death-messages", true), changes);
        assertEquals(List.of("private-messages", "death-messages"), List.copyOf(changes.keySet()), "in dialog order");
    }

    @Test
    void missingOrForgedValuesChangeNothing() {
        List<SettingsForm.Field> fields = SettingsForm.fields(List.of(Map.entry("mentions", true)));
        assertTrue(SettingsForm.changes(fields, Map.of()).isEmpty());
        assertTrue(SettingsForm.changes(fields, Map.of("mentions", "false")).isEmpty(), "only real booleans count");
        assertTrue(SettingsForm.changes(fields, Map.of("unknown", false)).isEmpty());
    }

    @Test
    void groupsFollowTheCategoryOrderWithUnlistedOnesLast() {
        List<String> items = List.of("mentions", "pay", "tpa", "private", "spy", "crates");
        Map<String, String> category = Map.of("mentions", "chat", "pay", "general", "tpa", "teleport", "private", "chat",
            "spy", "chat", "crates", "loot");
        List<SettingsForm.Group<String>> groups = SettingsForm.group(items, category::get, List.of("chat", "teleport", "empty", "general"));
        assertEquals(List.of("chat", "teleport", "general", "loot"), groups.stream().map(SettingsForm.Group::category).toList(),
            "listed order, empty groups left out, unlisted after");
        assertEquals(List.of("mentions", "private", "spy"), groups.get(0).items(), "registration order inside a group");
        assertTrue(SettingsForm.group(List.<String>of(), category::get, List.of("chat")).isEmpty());
    }

    @Test
    void pagesSplitLongGroups() {
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertEquals(3, SettingsForm.pages(items.size(), 8));
        assertEquals(1, SettingsForm.pages(0, 8), "an empty group is one page");
        assertEquals(1, SettingsForm.pages(8, 8));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), SettingsForm.page(items, 1, 8));
        assertEquals(List.of(17), SettingsForm.page(items, 3, 8));
        assertEquals(List.of(17), SettingsForm.page(items, 99, 8), "pages past the end show the last one");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), SettingsForm.page(items, -4, 8), "and before the start the first");
        assertEquals(3, SettingsForm.clampPage(7, items.size(), 8));
    }

    @Test
    void flipsAreCarriedBetweenPagesAndSavedOnlyWhenTheyChangeSomething() {
        // Page 1 shows mentions on and private messages on; the player turns mentions off.
        List<SettingsForm.Field> page1 = SettingsForm.fields(List.of(Map.entry("mentions", true), Map.entry("private-messages", true)));
        Map<String, Boolean> pending = SettingsForm.merge(Map.of(), page1, Map.of("mentions", false, "private_messages", true));
        assertEquals(Map.of("mentions", false), pending);
        // Page 2: death messages off, the player turns them on.
        List<SettingsForm.Field> page2 = SettingsForm.fields(List.of(Map.entry("death-messages", false)));
        pending = SettingsForm.merge(pending, page2, Map.of("death_messages", true));
        assertEquals(Map.of("mentions", false, "death-messages", true), pending);
        // Back on page 1 (showing the pending value), the player turns mentions on again.
        List<SettingsForm.Field> again = SettingsForm.fields(List.of(Map.entry("mentions", false), Map.entry("private-messages", true)));
        pending = SettingsForm.merge(pending, again, Map.of("mentions", true, "private_messages", true));
        assertEquals(Map.of("mentions", true, "death-messages", true), pending);
        // Stored now: mentions on (unchanged), death messages off: only death messages is saved.
        Map<String, Boolean> stored = Map.of("mentions", true, "death-messages", false);
        assertEquals(Map.of("death-messages", true), SettingsForm.effective(pending, stored::get));
    }

    @Test
    void aSwitchChangedElsewhereIsNotOverwrittenByAStalePage() {
        // The page showed private messages off; meanwhile /msgtoggle turned them on. The player touches nothing.
        List<SettingsForm.Field> page = SettingsForm.fields(List.of(Map.entry("private-messages", false)));
        Map<String, Boolean> pending = SettingsForm.merge(Map.of(), page, Map.of("private_messages", false));
        assertTrue(pending.isEmpty());
        assertTrue(SettingsForm.effective(pending, toggle -> true).isEmpty());
    }

    @Test
    void configDefaultsAndLimits() throws Exception {
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml("features/settings.yml"));
        SettingsConfig config = SettingsConfig.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(new SettingsConfig(8, true), config);
        YamlConfiguration broken = yaml("features/settings.yml");
        broken.set("page-size", 0);
        ConfigReader brokenReader = new ConfigReader("features/settings.yml", broken);
        assertEquals(8, SettingsConfig.parse(brokenReader).pageSize(), "fallback");
        assertEquals(1, brokenReader.problems().size());
    }

    @Test
    void theRegistryKeepsCategories() {
        PlayerSettings settings = new PlayerSettings(null);
        MessageKey label = MessageKey.ui("test.label");
        MessageKey description = MessageKey.ui("test.description");
        SettingCategory chat = new SettingCategory("chat", 20, label, description);
        SettingCategory teleport = new SettingCategory("teleport", 10, label, description);
        Toggle mentions = new Toggle("mentions", true, label, description, null);
        Toggle requests = new Toggle("tpa-requests", true, label, description, null);
        Toggle loose = new Toggle("loose", true, label, description, null);
        settings.register(chat, mentions);
        settings.register(teleport, requests);
        settings.register(loose);
        assertEquals(List.of(teleport, chat), settings.categories(), "by order");
        assertEquals(chat, settings.category(mentions));
        assertEquals(null, settings.category(loose), "registered without a category");
        assertEquals(3, settings.toggles().size());
        Toggle other = new Toggle("other", true, label, description, null);
        settings.register(new SettingCategory("chat", 20, label, description), other);
        assertEquals(chat, settings.category(other), "the same category may be shared");
        assertThrows(IllegalStateException.class, () -> settings.register(new SettingCategory("chat", 5, label, description),
            new Toggle("clash", true, label, description, null)), "one id, one category");
        assertThrows(IllegalArgumentException.class, () -> new SettingCategory("Bad Id", 1, label, description));
    }

    @Test
    void textLoadsWithoutProblems() throws Exception {
        Icons icons = new Icons(Icons.readIndex(SettingsFormTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(SettingsMessages.class);
        YamlConfiguration langYaml = yaml("lang/settings.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/settings.yml");
        assertEquals(List.of(), problems);
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("settings."))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inFile = langYaml.getKeys(true).stream().filter(path -> !langYaml.isConfigurationSection(path))
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(registered, inFile, "lang/settings.yml has exactly the registered messages");
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = SettingsFormTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
