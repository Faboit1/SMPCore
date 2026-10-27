package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

/** The bundled {@code lang/spawners.yml} is complete, has no leftovers and its setting texts read well. */
class SpawnersResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = SpawnersResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = SpawnersResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(SpawnersMessages.class);
        return lang;
    }

    @Test
    void langFileIsCompleteAndHasNoLeftovers() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/spawners.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/spawners.yml");
        assertEquals(List.of(), problems);
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "Word")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + " leaves a tag behind: " + plain);
            assertFalse(plain.isBlank(), key.path() + " is empty");
        }
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }

    @Test
    void settingTextsReadWell() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/spawners.yml");
        lang.load(file, file, "lang/spawners.yml");
        assertEquals("Open spawner storage with", lang.plain(SpawnerPlayerSettings.OPEN_CLICK.label()));
        assertEquals("Sneak + right-click", lang.plain(SpawnerPlayerSettings.OpenClick.SNEAK_RIGHT_CLICK.label()));
        assertEquals("Click again to add 3 to Alex's pig stack. The spawners you add become theirs.", TextStyle.plain(lang.get(
            SpawnersMessages.GIVE_CONFIRM, Arg.number("amount", 3), Arg.text("owner", "Alex"), Arg.text("mob", "pig"))));
        assertEquals("Your zombie spawner is full. New loot is lost until you empty or sell it (/spawners).",
            lang.plain(SpawnersMessages.FULL_ONE, Arg.text("mob", "zombie")));
        assertEquals("Sold 1,200 items for $3,400", lang.plain(SpawnersMessages.SOLD_SHORT, Arg.number("count", 1200),
            Arg.money("total", 3400), Arg.component("booster", net.kyori.adventure.text.Component.empty())));
        assertEquals("Sold 1,200 items for $3,400 incl. +10% booster", lang.plain(SpawnersMessages.SOLD_SHORT, Arg.number("count", 1200),
            Arg.money("total", 3400), Arg.component("booster", lang.get(SpawnersMessages.BOOSTER_NOTE, Arg.number("percent", 10)))));
    }
}
