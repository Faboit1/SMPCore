package net.siftvanilla.siftcore.testing;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A lang with several bundled lang files merged into one, like the server loads them (a dialog test needs the shared
 * button texts of {@code lang/core.yml} next to a feature's own), with the real icons and money format.
 */
public final class MergedLang {

    private MergedLang() {
    }

    /** Fails when a file has a problem, like the server would report it. */
    public static Lang of(List<String> files, Class<?>... keys) {
        Icons icons;
        try (InputStream in = MergedLang.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
            icons.load(IconSettings.parse(new ConfigReader("icons.yml", Fakes.yaml("icons.yml"))).icons());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the icons", e);
        }
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        for (Class<?> type : keys) {
            lang.register(type);
        }
        YamlConfiguration merged = new YamlConfiguration();
        for (String file : files) {
            YamlConfiguration yaml = Fakes.yaml(file);
            for (String key : yaml.getKeys(true)) {
                if (!yaml.isConfigurationSection(key)) {
                    merged.set(key, yaml.get(key));
                }
            }
        }
        List<?> problems = lang.load(merged, merged, "lang");
        if (!problems.isEmpty()) {
            throw new IllegalStateException(problems.toString());
        }
        return lang;
    }
}
