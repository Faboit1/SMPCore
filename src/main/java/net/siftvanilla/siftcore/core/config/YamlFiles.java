package net.siftvanilla.siftcore.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Loads YAML files from the plugin folder. Missing files are copied verbatim from the jar so the shipped comments
 * survive. Syntax errors become a {@link ConfigException} with the parser's line information.
 * <p>
 * Updates add settings and texts. So that a server's existing files keep working, every key the jar ships that is
 * new since the file was last synced is written into the server's file (with its comments), and logged. Keys the
 * jar shipped before are never re-added, so entries an admin deleted on purpose (a crate, a shop item) stay
 * deleted. Which keys the jar shipped is remembered in {@code data/shipped-keys/}.
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

    /** Loads a file from the data folder, creating it from the jar first when missing, adding keys new in the jar. */
    public YamlConfiguration load(String resource) throws ConfigException {
        Path path;
        boolean created;
        try {
            created = Files.notExists(dataFolder().resolve(resource));
            path = ensure(resource);
        } catch (IOException e) {
            throw new ConfigException(List.of(new ConfigProblem(resource, "(file)", "could not be created: " + e.getMessage())));
        }
        syncNewKeys(resource, path, created);
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

    /**
     * Writes keys the jar ships for the first time into the server's file. Never fails the load: a problem here is
     * logged and the file is read as it is.
     */
    private void syncNewKeys(String resource, Path path, boolean created) {
        Path record = dataFolder().resolve("data").resolve("shipped-keys").resolve(resource.replace('/', '_') + ".txt");
        try {
            YamlConfiguration jar = new YamlConfiguration();
            jar.options().parseComments(true);
            try (InputStream in = this.plugin.getResource(resource)) {
                if (in == null) {
                    return;
                }
                jar.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            Set<String> shipped = leafKeys(jar);
            if (!created) {
                YamlConfiguration server = new YamlConfiguration();
                server.options().parseComments(true);
                server.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
                // Without a record (files from before this existed) the keys the server has stand in for it.
                Set<String> known = Files.exists(record) ? new HashSet<>(Files.readAllLines(record, StandardCharsets.UTF_8))
                    : leafKeys(server);
                List<String> added = addNewKeys(server, jar, known);
                if (!added.isEmpty()) {
                    Files.writeString(path, server.saveToString(), StandardCharsets.UTF_8);
                    this.plugin.getLogger().info("Added " + added.size() + (added.size() == 1 ? " new key" : " new keys")
                        + " from this version to " + resource + ": " + String.join(", ", added.size() > 12 ? added.subList(0, 12) : added)
                        + (added.size() > 12 ? ", ..." : ""));
                }
            }
            Files.createDirectories(record.getParent());
            Files.write(record, shipped, StandardCharsets.UTF_8);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not add new keys to " + resource + "; it is read as it is", e);
        }
    }

    /** Every key that holds a value (not a section), in file order. */
    static Set<String> leafKeys(YamlConfiguration yaml) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Copies into {@code server} every value key of {@code jar} that is neither in {@code known} (shipped before) nor
     * already on the server, with the jar's comments. Returns the keys added.
     */
    static List<String> addNewKeys(YamlConfiguration server, YamlConfiguration jar, Set<String> known) {
        List<String> added = new ArrayList<>();
        for (String key : leafKeys(jar)) {
            if (known.contains(key) || server.contains(key)) {
                continue;
            }
            // A key under a value the server turned into a scalar or list can't be added without breaking it.
            if (blockedByValue(server, key)) {
                continue;
            }
            List<String> newSections = new ArrayList<>();
            String parent = key;
            while (parent.contains(".")) {
                parent = parent.substring(0, parent.lastIndexOf('.'));
                if (!server.contains(parent)) {
                    newSections.add(parent);
                }
            }
            server.set(key, jar.get(key));
            server.setComments(key, jar.getComments(key));
            server.setInlineComments(key, jar.getInlineComments(key));
            for (String section : newSections) {
                server.setComments(section, jar.getComments(section));
            }
            added.add(key);
        }
        return added;
    }

    private static boolean blockedByValue(YamlConfiguration server, String key) {
        String parent = key;
        while (parent.contains(".")) {
            parent = parent.substring(0, parent.lastIndexOf('.'));
            if (server.contains(parent) && !server.isConfigurationSection(parent)) {
                return true;
            }
        }
        return false;
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
