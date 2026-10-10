package net.siftvanilla.siftcore.feature.displays;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Shared fixtures: the real text style with the shipped icons, YAML loading and a fake server context. */
final class DisplaysTestSupport {

    private DisplaysTestSupport() {
    }

    /** The design-system text style with the icons from the bundled icons.yml. */
    static TextStyle style() {
        try {
            Set<String> index;
            try (InputStream in = DisplaysTestSupport.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
                index = Icons.readIndex(in);
            }
            Icons icons = new Icons(index);
            IconSettings settings = IconSettings.parse(new ConfigReader("icons.yml", bundled("icons.yml")));
            Set<String> invalid = icons.load(settings.icons());
            if (!invalid.isEmpty()) {
                throw new IllegalStateException("bundled icons point to missing sprites: " + invalid);
            }
            return new TextStyle(Palette.defaults(), icons);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static YamlConfiguration bundled(String resource) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = DisplaysTestSupport.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the class path");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    /** A server with the worlds {@code world} and {@code world_nether} and configurable placeholders. */
    static final class FakeContext implements DisplaysSettings.Context {

        final TextStyle style;
        final Set<String> unknown = new HashSet<>();
        final Set<String> perPlayer = new HashSet<>();
        final Map<String, String> placed = new HashMap<>();

        FakeContext(TextStyle style) {
            this.style = style;
        }

        @Override
        public TextStyle style() {
            return this.style;
        }

        @Override
        public boolean worldExists(String world) {
            return worlds().contains(world);
        }

        @Override
        public List<String> worlds() {
            return List.of("world", "world_nether");
        }

        @Override
        public DisplaysSettings.PlaceholderStatus placeholder(String name) {
            if (this.unknown.contains(name)) {
                return DisplaysSettings.PlaceholderStatus.UNKNOWN;
            }
            return this.perPlayer.contains(name) ? DisplaysSettings.PlaceholderStatus.PER_PLAYER : DisplaysSettings.PlaceholderStatus.KNOWN;
        }

        @Override
        public Map<String, String> placedTemplates() {
            return this.placed;
        }
    }
}
