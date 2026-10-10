package net.siftvanilla.siftcore.feature.settings;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingTexts;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;

/** A settings store on a fresh SQLite database with the shared settings registered, and the settings text loaded. */
final class SettingsDb implements AutoCloseable {

    static final Logger LOGGER = Logger.getLogger("siftcore-test");

    final JdbcDatabase database;
    final PlayerSettings settings;
    final Lang lang;

    SettingsDb(Path dir) throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = SettingsDb.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        SharedSettings.register(this.settings, new Relations());
        this.lang = lang();
    }

    /** The settings text and the shared settings' text (lang/settings.yml and lang/core.yml, merged like the server does). */
    static Lang lang() {
        Lang lang = Fakes.lang();
        for (Class<?> type : List.of(SettingsMessages.class, SettingCategories.class, SettingTexts.class, OptionTexts.class,
            SharedSettings.class, CoreMessages.class)) {
            lang.register(type);
        }
        YamlConfiguration merged = new YamlConfiguration();
        for (String file : List.of("lang/settings.yml", "lang/core.yml")) {
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

    /** Loads a player as if they logged in. */
    void join(UUID player) throws Exception {
        this.settings.load(player).get(5, TimeUnit.SECONDS);
    }

    /** A stored row, read after every queued write (null when none). */
    String row(UUID player, String setting) throws Exception {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(5, TimeUnit.SECONDS);
    }

    void insert(UUID player, String setting, String value) throws Exception {
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                ps.setString(3, value);
                ps.executeUpdate();
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        this.database.close();
    }
}
