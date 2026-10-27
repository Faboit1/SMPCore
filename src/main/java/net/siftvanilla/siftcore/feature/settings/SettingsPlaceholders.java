package net.siftvanilla.siftcore.feature.settings;

import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The settings placeholders: {@code setting_<id>} (the value as stored: true/false, an option id, a number),
 * {@code settingtext_<id>} (as players read it: "on", "Everyone", "60%") and {@code settings_changed} (how many of the
 * settings the player sees they changed). An unknown id resolves to null (PlaceholderAPI leaves the text alone); a
 * setting that keeps its value private ({@code placeholder(false)}: privacy settings and settings that need a
 * permission) or that the server hides resolves to an empty text. Players who are not online read the defaults.
 * Resolvers only read memory, so they are safe on any thread.
 */
final class SettingsPlaceholders {

    static final String VALUE_PREFIX = "setting_";
    static final String TEXT_PREFIX = "settingtext_";
    static final String CHANGED = "settings_changed";

    private final PlayerSettings settings;
    private final Lang lang;

    SettingsPlaceholders(PlayerSettings settings, Lang lang) {
        this.settings = settings;
        this.lang = lang;
    }

    void register(Placeholders placeholders) {
        placeholders.registerPrefix(VALUE_PREFIX, VALUE_PREFIX + "<id>",
            "A setting's value as stored (true or false, an option id, a number); empty for private settings",
            (player, id) -> resolve(this.settings, this.lang, id(player), id, false));
        placeholders.registerPrefix(TEXT_PREFIX, TEXT_PREFIX + "<id>",
            "A setting's value as players read it (on, Everyone, 60%); empty for private settings",
            (player, id) -> resolve(this.settings, this.lang, id(player), id, true));
        placeholders.register(CHANGED, "How many of their settings the player changed from the server's defaults",
            player -> Integer.toString(changed(player)));
    }

    private static UUID id(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    /**
     * A setting placeholder's value: null for an unknown id, empty for a private or hidden setting, otherwise the
     * player's value (the default for a null or unloaded player), stored form or as text.
     */
    static String resolve(PlayerSettings settings, Lang lang, UUID player, String id, boolean text) {
        Registry.Entry<?> entry = id == null ? null : settings.registry().entry(id);
        if (entry == null) {
            return null;
        }
        if (!entry.placeholder() || settings.hidden(entry.setting())) {
            return "";
        }
        return value(settings, lang, entry, player, text);
    }

    private static <T> String value(PlayerSettings settings, Lang lang, Registry.Entry<T> entry, UUID player, boolean text) {
        PlayerSetting<T> setting = entry.setting();
        T value = player == null ? settings.defaultValue(setting) : settings.get(player, setting);
        return text ? setting.display(lang, value) : setting.encode(value);
    }

    /** How many settings the player sees and changed (0 when they are not online). */
    private int changed(OfflinePlayer offline) {
        Player player = offline == null ? null : offline.getPlayer();
        if (player == null) {
            return 0;
        }
        return changed(this.settings, player.getUniqueId(), player::hasPermission);
    }

    static int changed(PlayerSettings settings, UUID player, Predicate<String> permissions) {
        int count = 0;
        for (Registry.Entry<?> entry : settings.registry().byId().values()) {
            if (settings.visible(entry, permissions) && settings.changed(player, entry.setting())) {
                count++;
            }
        }
        return count;
    }
}
