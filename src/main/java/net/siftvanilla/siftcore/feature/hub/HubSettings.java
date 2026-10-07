package net.siftvanilla.siftcore.feature.hub;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/hub.yml}. */
public record HubSettings(boolean pauseMenu, List<String> pauseEntries, int columns, List<Link> links) {

    /** A server link shown in the pause menu's links screen and the hub. */
    public record Link(String label, URI url) {
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
        return new HubSettings(enabled, List.copyOf(entries), columns, List.copyOf(links));
    }
}
