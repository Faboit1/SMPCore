package net.siftvanilla.siftcore.feature.hub;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/hub.yml}.
 *
 * @param buttons how each entry's button looks in the main menu and the pause-screen menu, by entry id
 */
public record HubSettings(boolean pauseMenu, List<String> pauseEntries, int columns, List<Link> links, Map<String, Look> buttons) {

    /** A server link shown in the pause menu's links screen and the hub. */
    public record Link(String label, URI url) {
    }

    /**
     * The look of a menu button: an icon (an {@code icons.yml} name) in front of the label, and the label's colour.
     * Either may be null (no icon, the primary colour).
     */
    public record Look(String icon, TextColor color) {

        public static final Look PLAIN = new Look(null, null);

        /** A look from the raw config values; a blank icon is none, an invalid colour is null. */
        public static Look of(String icon, String color) {
            String name = icon == null || icon.isBlank() ? null : icon.strip().toLowerCase(Locale.ROOT);
            return new Look(name, color(color));
        }

        /** A "#RRGGBB" colour, or null when blank or not one. */
        public static TextColor color(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return TextColor.fromHexString(value.strip());
        }
    }

    public HubSettings {
        pauseEntries = List.copyOf(pauseEntries);
        links = List.copyOf(links);
        buttons = Map.copyOf(buttons);
    }

    /** The look of an entry's button ({@link Look#PLAIN} when the file gives none). */
    public Look look(String id) {
        return this.buttons.getOrDefault(id, Look.PLAIN);
    }

    public static HubSettings parse(ConfigReader r) {
        ConfigReader pause = r.section("pause-menu");
        boolean enabled = pause.bool("enabled", true);
        List<String> entries = pause.stringList("entries", List.of("menu"));
        int columns = r.integer("columns", 1, 4, 2);
        List<Link> links = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : r.children("server-links").entrySet()) {
            ConfigReader link = entry.getValue();
            String label = link.string("label", entry.getKey());
            URI url = link.custom("url", value -> {
                URI uri = URI.create(value.trim());
                if (uri.getScheme() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
                    throw new IllegalArgumentException("must start with https:// or http://");
                }
                return uri;
            }, "a web address", null);
            if (url != null) {
                links.add(new Link(label, url));
            }
        }
        Map<String, Look> buttons = new LinkedHashMap<>();
        r.children("buttons").forEach((id, button) -> {
            String icon = button.has("icon") ? button.string("icon", "") : null;
            TextColor color = button.has("color") ? button.custom("color", value -> {
                TextColor parsed = Look.color(value);
                if (parsed == null) {
                    throw new IllegalArgumentException("is not a hex colour");
                }
                return parsed;
            }, "a hex colour like #55FFFF", null) : null;
            buttons.put(id.strip().toLowerCase(Locale.ROOT), new Look(icon == null || icon.isBlank() ? null
                : icon.strip().toLowerCase(Locale.ROOT), color));
        });
        return new HubSettings(enabled, entries, columns, links, buttons);
    }
}
