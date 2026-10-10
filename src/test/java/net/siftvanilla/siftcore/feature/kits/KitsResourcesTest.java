package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
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

/** The bundled {@code lang/kits.yml} is complete, follows the design system and reads well. */
class KitsResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = KitsResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = KitsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(KitsMessages.class);
        return lang;
    }

    private static Lang loaded() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/kits.yml");
        assertEquals(List.of(), lang.load(file, file, "lang/kits.yml"));
        return lang;
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/kits.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/kits.yml");
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
            assertFalse(raw.contains("<bold>") || raw.contains("<b>") || raw.contains("<gradient"), key.path() + " is decorated: " + raw);
        }
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/kits.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }

    @Test
    void everyPerkHasALabel() throws Exception {
        Lang lang = loaded();
        for (Perk perk : Perk.values()) {
            String label = lang.plain(KitsMessages.name(perk));
            assertFalse(label.isBlank(), perk + " has a label");
            assertTrue(Character.isUpperCase(label.charAt(0)), perk + " label is in sentence case: " + label);
        }
    }

    @Test
    void statusesAndKeysReadNaturally() throws Exception {
        Lang lang = loaded();
        KitText text = new KitText(lang);
        assertEquals("ready", TextStyle.plain(text.status(KitStatus.READY, true)));
        assertEquals("in 3h 20m", TextStyle.plain(text.status(new KitStatus.Waiting(Duration.ofMinutes(200), 0), true)));
        assertEquals("in 45s", TextStyle.plain(text.status(new KitStatus.Waiting(Duration.ofMillis(44_100), 0), true)));
        assertEquals("claimed", TextStyle.plain(text.status(new KitStatus.Claimed(5), true)));
        assertEquals("locked", TextStyle.plain(text.status(KitStatus.READY, false)));
        Map<String, Integer> keys = new LinkedHashMap<>();
        keys.put("basic", 1);
        keys.put("rare", 2);
        assertEquals("1 Basic key, 2 Rare keys", TextStyle.plain(text.keys(keys)));
        KitText named = new KitText(lang, crate -> crate.equals("basic") ? net.kyori.adventure.text.Component.text("Common")
            : net.kyori.adventure.text.Component.text(crate));
        assertEquals("1 Common key, 2 Rare keys", TextStyle.plain(named.keys(keys)), "keys use the crates' names");
        Kit daily = new Kit("daily", "Daily", null, "minecraft:bread", true, Cooldown.parse("24h"), List.of(), Map.of("basic", 1));
        Kit legend = new Kit("legend", "Legend", null, "minecraft:bread", false, Cooldown.parse("72h"), List.of(), Map.of("epic", 1));
        assertEquals("Daily, Legend", TextStyle.plain(text.names(List.of(daily, legend))));
        assertEquals("Kits ready to claim: Daily, Legend. Click to claim them.", TextStyle.plain(lang.get(KitsMessages.REMINDER_JOIN,
            Arg.component("kits", text.names(List.of(daily, legend))))));
        assertEquals("The Daily kit is ready again in 23h 59m.", TextStyle.plain(lang.get(KitsMessages.NOT_READY,
            Arg.text("name", "Daily"), Arg.time("time", Duration.ofMinutes(23 * 60 + 59)))));
        assertEquals("You claimed the Starter kit.", TextStyle.plain(lang.get(KitsMessages.CLAIMED, Arg.text("name", "Starter"))));
        assertEquals("Daily (daily): in 2h", TextStyle.plain(lang.get(KitsMessages.ADMIN_CHECK_LINE, Arg.text("name", "Daily"),
            Arg.text("id", "daily"), Arg.component("status", text.status(new KitStatus.Waiting(Duration.ofHours(2), 0), true)))));
    }

    @Test
    void theTrashTitleSaysItDeletes() throws Exception {
        Lang lang = loaded();
        String title = lang.plain(KitsMessages.TRASH_TITLE);
        assertTrue(title.toLowerCase(java.util.Locale.ROOT).contains("deleted"), title);
        assertEquals("Alex's ender chest", lang.plain(KitsMessages.EC_OTHERS_TITLE, Arg.text("name", "Alex")));
        String button = lang.plain(KitsMessages.TRASH_TITLE_BUTTON);
        assertTrue(button.contains("Delete"), "the Delete button bin says how it deletes: " + button);
        assertEquals("Items deleted: 5. Protected items given back: 2", lang.plain(KitsMessages.TRASH_DELETED_KEPT,
            Arg.number("count", 5), Arg.number("kept", 2)));
    }

    @Test
    void settingTextsReadWell() throws Exception {
        Lang lang = loaded();
        assertEquals("Kit reminders", lang.plain(KitPlayerSettings.REMINDERS.label()));
        assertEquals("Trash protection", lang.plain(KitPlayerSettings.TRASH_PROTECT.label()));
        for (KitPlayerSettings.ReminderWhen when : KitPlayerSettings.ReminderWhen.values()) {
            assertFalse(lang.plain(when.label()).isBlank(), when.name());
        }
        for (KitPlayerSettings.TrashProtect protect : KitPlayerSettings.TrashProtect.values()) {
            assertFalse(lang.plain(protect.label()).isBlank(), protect.name());
        }
        for (KitPlayerSettings.TrashMode mode : KitPlayerSettings.TrashMode.values()) {
            assertFalse(lang.plain(mode.label()).isBlank(), mode.name());
        }
        // The short reminders have nothing to click: they name the command instead.
        assertEquals("Your Daily kit is ready (/kits)", lang.plain(KitsMessages.REMINDER_READY_SHORT, Arg.text("name", "Daily")));
    }
}
