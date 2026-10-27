package net.siftvanilla.siftcore.feature.hub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The pause-screen menu is built before any feature loads, so every entry needs its text in lang/hub.yml. */
class PauseMenuResourcesTest {

    private static YamlConfiguration load(String resource) throws Exception {
        try (InputStream in = PauseMenuResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyPauseMenuEntryHasALabelAndDescription() throws Exception {
        List<String> entries = load("features/hub.yml").getStringList("pause-menu.entries");
        assertFalse(entries.isEmpty());
        YamlConfiguration lang = load("lang/hub.yml");
        for (String id : entries) {
            assertTrue(!lang.getString("hub.entries." + id + ".label", "").isBlank(), "a label for " + id);
            assertTrue(!lang.getString("hub.entries." + id + ".description", "").isBlank(), "a description for " + id);
        }
    }
}
