package net.siftvanilla.siftcore.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Loads YAML files from the plugin folder. Missing files are copied verbatim from the jar so the shipped comments
 * survive. Syntax errors become a {@link ConfigException} with the parser's line information.
 */
public final class YamlFiles {

    private final Plugin plugin;

    public YamlFiles(Plugin plugin) {
        this.plugin = plugin;
    }

    public Path dataFolder() {
        return this.plugin.getDataFolder().toPath();
    }

    /** Copies {@code resource} from the jar to the data folder if it does not exist yet. */
    public Path ensure(String resource) throws IOException {
        Path target = dataFolder().resolve(resource);
        if (Files.notExists(target)) {
            Files.createDirectories(target.getParent());
            try (InputStream in = this.plugin.getResource(resource)) {
                if (in == null) {
                    throw new IOException("Missing bundled resource " + resource);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /** Loads a file from the data folder, creating it from the jar first when missing. */
    public YamlConfiguration load(String resource) throws ConfigException {
        Path path;
        try {
            path = ensure(resource);
        } catch (IOException e) {
            throw new ConfigException(List.of(new ConfigProblem(resource, "(file)", "could not be created: " + e.getMessage())));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException(List.of(new ConfigProblem(resource, "(file)", "could not be read: " + e.getMessage())));
        } catch (InvalidConfigurationException e) {
            String message = e.getMessage() == null ? "is not valid YAML" : "is not valid YAML: " + e.getMessage().replace('\n', ' ');
            throw new ConfigException(List.of(new ConfigProblem(resource, "(file)", message)));
        }
        return yaml;
    }

    /** Loads the copy bundled in the jar (used as the source of defaults and for validation fallbacks). */
    public YamlConfiguration bundled(String resource) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = this.plugin.getResource(resource)) {
            if (in == null) {
                return yaml;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Bundled resource " + resource + " is broken", e);
        }
        return yaml;
    }
}
