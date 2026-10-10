package net.siftvanilla.siftcore.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CommandSettingsTest {

    @Test
    void bundledDefaultsChangeNothing() throws Exception {
        YamlConfiguration yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("commands.yml")) {
            assertNotNull(in, "commands.yml is bundled");
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        ConfigReader reader = new ConfigReader("commands.yml", yaml);
        CommandSettings settings = CommandSettings.parse(reader);
        assertTrue(reader.problems().isEmpty(), reader.problems().toString());
        assertEquals(CommandSettings.DEFAULT, settings.get("ah"));
        assertNull(settings.get("ah").yieldTo(), "no command yields by default");
    }

    @Test
    void yieldToNamesAPlugin() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("commands.ah.yield-to", "AxAuctions");
        yaml.set("commands.pay.cooldown", "3s");
        yaml.set("commands.pay.aliases", List.of("send"));
        ConfigReader reader = new ConfigReader("commands.yml", yaml);
        CommandSettings settings = CommandSettings.parse(reader);
        assertTrue(reader.problems().isEmpty(), reader.problems().toString());
        CommandSettings.Entry ah = settings.get("ah");
        assertEquals("AxAuctions", ah.yieldTo());
        assertTrue(ah.enabled(), "yielding keeps the command enabled for when the plugin is missing");
        assertNull(ah.aliases());
        assertNull(settings.get("pay").yieldTo());
        assertEquals(Duration.ofSeconds(3), settings.get("pay").cooldown());
    }

    @Test
    void badPluginNamesAreReportedAndIgnored() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("commands.ah.yield-to", "Ax Auctions!");
        yaml.set("commands.sell.yield-to", "");
        ConfigReader reader = new ConfigReader("commands.yml", yaml);
        CommandSettings settings = CommandSettings.parse(reader);
        assertEquals(2, reader.problems().size(), reader.problems().toString());
        assertNull(settings.get("ah").yieldTo());
        assertNull(settings.get("sell").yieldTo());
        assertFalse(reader.problems().getFirst().toString().isBlank());
    }
}
