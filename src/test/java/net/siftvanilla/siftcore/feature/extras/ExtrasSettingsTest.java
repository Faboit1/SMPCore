package net.siftvanilla.siftcore.feature.extras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The join and leave setting, who sees {@code /seen}, and the bundled extras text. */
class ExtrasSettingsTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = ExtrasSettingsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    @Test
    void eachChoiceShowsItsLines() {
        assertTrue(JoinLines.ALL.shows(true) && JoinLines.ALL.shows(false));
        assertTrue(JoinLines.FIRST_JOINS.shows(true), "new players only: the welcome");
        assertFalse(JoinLines.FIRST_JOINS.shows(false), "but no other join or leave line");
        assertFalse(JoinLines.OFF.shows(true) || JoinLines.OFF.shows(false));
        assertEquals(List.of("all", "first-joins", "off"), ExtrasFeature.JOIN_LEAVE_MESSAGES.optionIds());
        assertEquals(JoinLines.ALL, ExtrasFeature.JOIN_LEAVE_MESSAGES.defaultValue());
    }

    @Test
    void seenShowsTheLastOnlineTimeToTheRightPeople() {
        assertTrue(ExtrasFeature.seenShown(true, false, false, false), "the console");
        assertTrue(ExtrasFeature.seenShown(false, true, false, false), "the player themselves");
        assertTrue(ExtrasFeature.seenShown(false, false, true, false), "staff with /whois");
        assertFalse(ExtrasFeature.seenShown(false, false, false, Relations.allows(Audience.NOBODY, true, true)), "nobody");
        assertFalse(ExtrasFeature.seenShown(false, false, false, Relations.allows(Audience.FRIENDS, false, true)), "a stranger");
        assertTrue(ExtrasFeature.seenShown(false, false, false, Relations.allows(Audience.FRIENDS, true, false)), "a friend");
        assertTrue(ExtrasFeature.seenShown(false, false, false, Relations.allows(Audience.EVERYONE, false, false)), "everyone");
    }

    @Test
    void theSettingIsOfferedWhileTheServerShowsLinesAndFallsBackSafely() {
        AtomicReference<ExtrasSettings> config = new AtomicReference<>(new ExtrasSettings(false, false, true));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Registry.Entry<JoinLines> entry = ExtrasFeature.registerSettings(settings, config::get, () -> false);
        assertEquals("announcements", entry.category().id());
        assertTrue(entry.offered(), "the welcome is on by default");
        assertTrue(settings.hasReader(SharedSettings.SEEN_PRIVACY), "/seen acts on seen privacy");
        UUID player = UUID.randomUUID();
        settings.overrides(new Overrides(Map.of("join-leave-messages", "first-joins"), Map.of(), Set.of()));
        assertEquals(JoinLines.FIRST_JOINS, settings.get(player, ExtrasFeature.JOIN_LEAVE_MESSAGES));
        config.set(new ExtrasSettings(true, true, false));
        assertEquals(JoinLines.OFF, settings.get(player, ExtrasFeature.JOIN_LEAVE_MESSAGES),
            "without welcomes, new players only reads as off");
        config.set(new ExtrasSettings(false, false, false));
        assertFalse(entry.offered(), "no line to choose from");
        assertFalse(new ExtrasSettings(false, false, false).anyLines());
        assertTrue(new ExtrasSettings(false, true, false).anyLines());
    }

    @Test
    void rankLinesAloneOfferTheSettingAndALockToOffIsHonoured() {
        AtomicReference<ExtrasSettings> config = new AtomicReference<>(new ExtrasSettings(false, false, false));
        AtomicBoolean rankLines = new AtomicBoolean(true);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Registry.Entry<JoinLines> entry = ExtrasFeature.registerSettings(settings, config::get, rankLines::get);
        assertTrue(entry.offered(), "plain lines off, but rank and custom join lines can show: the switch does something");
        assertTrue(new ExtrasSettings(false, false, false).offersSetting(true));
        rankLines.set(false);
        assertFalse(entry.offered(), "no plain line and no rank line: nothing to choose from");
        assertFalse(new ExtrasSettings(false, false, false).offersSetting(false));
        rankLines.set(true);

        UUID viewer = UUID.randomUUID();
        assertEquals(JoinLines.ALL, settings.get(viewer, ExtrasFeature.JOIN_LEAVE_MESSAGES));
        assertTrue(ExtrasFeature.receives(false, false, JoinLines.ALL, false), "by default a rank leave line reaches everyone");
        settings.overrides(new Overrides(Map.of(), Map.of("join-leave-messages", "off"), Set.of()));
        JoinLines locked = settings.get(viewer, ExtrasFeature.JOIN_LEAVE_MESSAGES);
        assertEquals(JoinLines.OFF, locked, "the server's lock wins");
        assertFalse(ExtrasFeature.receives(false, false, locked, false), "a rank join or leave line is hidden under the lock");
        assertFalse(ExtrasFeature.receives(false, true, locked, true), "and so is the welcome");
        assertTrue(ExtrasFeature.receives(true, true, locked, false), "the joining player still reads their own line");
        assertFalse(ExtrasFeature.receives(true, false, JoinLines.ALL, false), "nobody reads their own leave line");

        // "locked: join-leave-messages: off" unquoted: YAML hands the settings config the boolean false.
        settings.overrides(new Overrides(Map.of(), Map.of("join-leave-messages", "false"), Set.of()));
        assertEquals(JoinLines.OFF, settings.get(viewer, ExtrasFeature.JOIN_LEAVE_MESSAGES), "an unquoted off locks it off too");
        assertTrue(settings.locked(ExtrasFeature.JOIN_LEAVE_MESSAGES));

        settings.overrides(new Overrides(Map.of(), Map.of("join-leave-messages", "first-joins"), Set.of()));
        assertEquals(JoinLines.OFF, settings.get(viewer, ExtrasFeature.JOIN_LEAVE_MESSAGES),
            "a lock to new players only reads as off while the server welcomes nobody");
        config.set(new ExtrasSettings(false, false, true));
        JoinLines firstJoins = settings.get(viewer, ExtrasFeature.JOIN_LEAVE_MESSAGES);
        assertEquals(JoinLines.FIRST_JOINS, firstJoins);
        assertTrue(ExtrasFeature.receives(false, false, firstJoins, true) && !ExtrasFeature.receives(false, false, firstJoins, false),
            "with welcomes on: the welcome only");
    }

    @Test
    void theBundledTextAndConfigLoad() throws Exception {
        Icons icons;
        try (InputStream in = ExtrasSettingsTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(ExtrasMessages.class);
        YamlConfiguration text = yaml("lang/extras.yml");
        List<ConfigProblem> problems = lang.load(text, text, "lang/extras.yml");
        assertEquals(List.of(), problems);
        assertEquals("Alex keeps their last online time private.", lang.plain(ExtrasMessages.SEEN_HIDDEN,
            net.siftvanilla.siftcore.core.text.Arg.text("name", "Alex")));
        // /help is buttons: every place it offers says in its tooltip what it is, and nothing names the plugin.
        for (Map.Entry<String, net.siftvanilla.siftcore.core.text.MessageKey> place : ExtrasFeature.HELP) {
            String tooltip = lang.plain(place.getValue());
            assertFalse(tooltip.isBlank(), place.getKey());
            assertFalse(tooltip.toLowerCase(java.util.Locale.ROOT).contains("siftcore"), tooltip);
        }
        assertEquals(List.of("rtp", "sell", "shop", "auction", "homes", "teams", "friends", "spawn", "rules"),
            ExtrasFeature.HELP.stream().map(Map.Entry::getKey).toList(), "the main menu's ids, in the order a new player needs them");
        assertTrue(text.getStringList("extras.help.body").isEmpty(), "no paragraph above the buttons any more");
        assertEquals("New players only", lang.plain(ExtrasMessages.JOIN_LINES_FIRST));
        ConfigReader reader = new ConfigReader("features/extras.yml", yaml("features/extras.yml"));
        ExtrasSettings parsed = ExtrasSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(new ExtrasSettings(false, false, true), parsed);
    }
}
