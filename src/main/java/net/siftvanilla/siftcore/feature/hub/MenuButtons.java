package net.siftvanilla.siftcore.feature.hub;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The buttons of the main menu and the pause-screen menu: a short label in the entry's colour with its icon in front
 * (the dialog style's group buttons), and the plain readers the pause-screen menu needs, because it is built before
 * the plugin loads its files.
 */
public final class MenuButtons {

    private MenuButtons() {
    }

    /**
     * A button label: the look's icon and a space (when {@code icons} knows it), then {@code text} in the look's colour,
     * or in {@code fallback} when the look has none.
     */
    public static Component label(String text, HubSettings.Look look, Function<String, Component> icons, TextColor fallback) {
        TextColor color = look.color() != null ? look.color() : fallback;
        Component name = Component.text(text, color);
        Component icon = look.icon() == null ? Component.empty() : icons.apply(look.icon());
        if (icon == null || Component.empty().equals(icon)) {
            return name;
        }
        return Component.text().append(icon).append(Component.space()).append(name).build();
    }

    /**
     * Pause-menu text from a lang entry (a tooltip or the body): gray, with the palette tags in their default colours
     * (a shard mention stays purple), or null when the text is blank.
     */
    public static Component text(String text, Icons icons) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Component.text().color(NamedTextColor.GRAY).append(new TextStyle(Palette.defaults(), icons).parse(text)).build();
    }

    /**
     * The values a server's file holds once SiftCore has updated it, as {@link net.siftvanilla.siftcore.core.config.YamlFiles}
     * would: keys the jar ships that the file lacks and never had are added, and entries the file still has exactly as
     * the last version shipped them ({@code previous}, the copy in {@code data/shipped/}) take the jar's value. Edited
     * entries stay. The pause-screen menu is built before the plugin updates the files, so without this a new default
     * would only show from the restart after the one that wrote it.
     *
     * @param server   the server's file, or null when there is none yet
     * @param previous the copy of the file the last version shipped, or null
     * @param jar      the file this version ships
     */
    public static YamlConfiguration effective(YamlConfiguration server, YamlConfiguration previous, YamlConfiguration jar) {
        if (server == null) {
            return jar;
        }
        YamlConfiguration result = new YamlConfiguration();
        for (String key : server.getKeys(true)) {
            if (!server.isConfigurationSection(key)) {
                result.set(key, server.get(key));
            }
        }
        for (String key : jar.getKeys(true)) {
            if (jar.isConfigurationSection(key)) {
                continue;
            }
            boolean shippedBefore = previous != null && previous.contains(key) && !previous.isConfigurationSection(key);
            if (!server.contains(key)) {
                if (!shippedBefore && !blocked(server, key)) {
                    result.set(key, jar.get(key));
                }
            } else if (shippedBefore && !server.isConfigurationSection(key) && Objects.equals(server.get(key), previous.get(key))) {
                result.set(key, jar.get(key));
            }
        }
        return result;
    }

    /** Whether a parent of {@code key} is a value on the server (so the key can't be added under it). */
    private static boolean blocked(YamlConfiguration server, String key) {
        String parent = key;
        while (parent.contains(".")) {
            parent = parent.substring(0, parent.lastIndexOf('.'));
            if (server.contains(parent) && !server.isConfigurationSection(parent)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code buttons} section of {@code features/hub.yml}: entry id to look. Invalid colours are left out. */
    public static Map<String, HubSettings.Look> looks(ConfigurationSection buttons) {
        Map<String, HubSettings.Look> looks = new LinkedHashMap<>();
        if (buttons == null) {
            return looks;
        }
        for (String id : buttons.getKeys(false)) {
            ConfigurationSection button = buttons.getConfigurationSection(id);
            if (button != null) {
                looks.put(id.toLowerCase(java.util.Locale.ROOT), HubSettings.Look.of(button.getString("icon"), button.getString("color")));
            }
        }
        return looks;
    }

    /** The {@code icons} section of {@code icons.yml} as sprites; entries that can't be read are skipped. */
    public static Map<String, Icons.Sprite> sprites(ConfigurationSection icons) {
        Map<String, Icons.Sprite> sprites = new LinkedHashMap<>();
        if (icons == null) {
            return sprites;
        }
        Set<String> seen = new HashSet<>();
        for (String name : icons.getKeys(false)) {
            ConfigurationSection icon = icons.getConfigurationSection(name);
            if (icon == null || !name.matches("[a-z0-9_]{1,32}") || !seen.add(name)) {
                continue;
            }
            try {
                Key atlas = Key.key(icon.getString("atlas", "minecraft:gui"));
                String sprite = icon.getString("sprite", "");
                Key spriteKey = sprite.contains(":") ? Key.key(sprite) : Key.key(Key.MINECRAFT_NAMESPACE, sprite);
                sprites.put(name, new Icons.Sprite(atlas, spriteKey, !"inherit".equals(icon.getString("tint", "keep"))));
            } catch (RuntimeException e) {
                // An invalid key: the plugin reports it when it loads icons.yml.
            }
        }
        return sprites;
    }
}
