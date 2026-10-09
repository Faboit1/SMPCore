package net.siftvanilla.siftcore.feature.scoreboard;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;

/** Shared fixtures: the bundled YAML files and the real text system with the shipped icons and lang. */
final class ScoreboardTestSupport {

    private ScoreboardTestSupport() {
    }

    static YamlConfiguration yaml(String resource) {
        InputStream in = ScoreboardTestSupport.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(resource + " is not bundled");
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static TextStyle style() {
        try {
            Icons icons = new Icons(Icons.readIndex(ScoreboardTestSupport.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
            ConfigReader reader = new ConfigReader("icons.yml", yaml("icons.yml"));
            if (!icons.load(IconSettings.parse(reader).icons()).isEmpty()) {
                throw new IllegalStateException("bundled icons point to missing sprites");
            }
            return new TextStyle(Palette.defaults(), icons);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The real lang with lang/scoreboard.yml loaded; throws if it has any problem. */
    static Lang lang() {
        Lang lang = new Lang(style(), MoneyFormat::defaults);
        lang.register(ScoreboardMessages.class);
        YamlConfiguration file = yaml("lang/scoreboard.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/scoreboard.yml");
        if (!problems.isEmpty()) {
            throw new IllegalStateException("lang/scoreboard.yml has problems: " + problems);
        }
        return lang;
    }
}
