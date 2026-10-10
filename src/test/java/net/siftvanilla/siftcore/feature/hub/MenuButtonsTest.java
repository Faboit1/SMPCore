package net.siftvanilla.siftcore.feature.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The main menu's and the pause-screen menu's buttons: short coloured labels with their icon, no paragraph above, and
 * the pause screen built from the files as the plugin will update them.
 */
class MenuButtonsTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void aLabelIsTheIconThenTheTextInItsColour() {
        HubSettings.Look look = new HubSettings.Look("money", TextColor.color(0x1AFF1A));
        Component icon = Component.text("[i]");
        Component label = MenuButtons.label("Money", look, name -> "money".equals(name) ? icon : Component.empty(), NamedTextColor.WHITE);
        assertEquals("[i] Money", TextStyle.plain(label));
        Component text = label.children().getLast();
        assertEquals(TextColor.color(0x1AFF1A), text.color());
        Component plain = MenuButtons.label("Rules", HubSettings.Look.PLAIN, name -> icon, NamedTextColor.WHITE);
        assertEquals("Rules", TextStyle.plain(plain));
        assertEquals(NamedTextColor.WHITE, plain.color(), "no look: white, no icon");
        Component unknown = MenuButtons.label("Rules", new HubSettings.Look("nope", null), name -> Component.empty(), NamedTextColor.WHITE);
        assertEquals("Rules", TextStyle.plain(unknown), "an unknown icon is left out");
    }

    @Test
    void theShippedMenuHasALookForEveryEntryAndNoParagraph() throws Exception {
        YamlConfiguration hub = Fakes.yaml("features/hub.yml");
        HubSettings settings = HubSettings.parse(new ConfigReader("features/hub.yml", hub));
        Icons icons = new Icons(Icons.readIndex(MenuButtonsTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(MenuButtons.sprites(Fakes.yaml("icons.yml").getConfigurationSection("icons"))).isEmpty(), "every icon exists");
        for (String id : List.of("menu", "money", "shop", "sell", "auction", "orders", "spawners", "crates", "kits", "teams", "friends",
            "homes", "rtp", "spawn", "stats", "settings", "report", "shards", "rules", "tpa", "bounties", "claims", "prices", "links",
            "cosmetics", "booster")) {
            HubSettings.Look look = settings.look(id);
            assertNotNull(look.color(), "a colour for " + id);
            assertTrue(look.icon() != null && icons.has(look.icon()), "an icon that exists for " + id + ": " + look.icon());
        }
        assertEquals(TextColor.color(0x915DFF), settings.look("shards").color(), "shards purple");
        assertSame(HubSettings.Look.PLAIN, settings.look("unknown"));
        assertEquals(settings.buttons(), MenuButtons.looks(hub.getConfigurationSection("buttons")), "the bootstrap reads the same looks");
        YamlConfiguration lang = Fakes.yaml("lang/hub.yml");
        assertEquals("", lang.getString("hub.pause-menu.body"), "the pause screen shows buttons, not a paragraph");
        assertEquals(1, lang.getStringList("hub.body").size(), "the menu's one status line");
    }

    @Test
    void anInvalidColourIsAProblemAndLeftOut() throws Exception {
        ConfigReader reader = new ConfigReader("features/hub.yml", yaml("buttons:\n  money:\n    icon: Money\n    color: green\n"));
        HubSettings settings = HubSettings.parse(reader);
        assertEquals(1, reader.problems().stream().filter(problem -> problem.toString().contains("buttons")).count(),
            reader.problems().toString());
        assertEquals(new HubSettings.Look("money", null), settings.look("money"));
        assertEquals(Map.of("money", new HubSettings.Look("money", null)), MenuButtons.looks(yaml("buttons:\n  money:\n    icon: Money\n    color: green\n")
            .getConfigurationSection("buttons")));
    }

    @Test
    void thePauseScreenReadsFilesAsThePluginWillUpdateThem() throws Exception {
        YamlConfiguration jar = yaml("""
            hub:
              pause-menu:
                body: ""
              entries:
                money:
                  label: "Money"
                shards:
                  label: "Shards"
            """);
        YamlConfiguration previous = yaml("""
            hub:
              pause-menu:
                body: "Pick where to go."
              entries:
                money:
                  label: "Money"
            """);
        YamlConfiguration unedited = yaml("""
            hub:
              pause-menu:
                body: "Pick where to go."
              entries:
                money:
                  label: "Cash"
              custom: "kept"
            """);
        YamlConfiguration merged = MenuButtons.effective(unedited, previous, jar);
        assertEquals("", merged.getString("hub.pause-menu.body"), "an entry nobody edited takes the new default");
        assertEquals("Cash", merged.getString("hub.entries.money.label"), "an edited entry stays");
        assertEquals("Shards", merged.getString("hub.entries.shards.label"), "a new key is added");
        assertEquals("kept", merged.getString("hub.custom"), "the server's own keys stay");

        YamlConfiguration edited = yaml("""
            hub:
              pause-menu:
                body: "Our own words"
            """);
        assertEquals("Our own words", MenuButtons.effective(edited, previous, jar).getString("hub.pause-menu.body"));
        assertNull(MenuButtons.effective(edited, previous, jar).getString("hub.entries.money.label"),
            "a key shipped before that the owner deleted stays deleted");
        assertSame(jar, MenuButtons.effective(null, previous, jar), "no file yet: the jar's");
        YamlConfiguration noRecord = MenuButtons.effective(unedited, null, jar);
        assertEquals("Pick where to go.", noRecord.getString("hub.pause-menu.body"), "without a shipped copy nothing counts as unedited");
        assertFalse(noRecord.contains("hub.entries.shards") && !"Shards".equals(noRecord.getString("hub.entries.shards.label")));
    }
}
