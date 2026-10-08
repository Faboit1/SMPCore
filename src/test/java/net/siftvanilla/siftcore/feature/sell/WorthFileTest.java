package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorthFileTest {

    private static final String SETTINGS = """
        craft-loss: 0.9
        recipe-types: [crafting, smelting]
        multipliers:
          vip: 1.1
          elite: 1.5
        sell-all:
          skip-unstackable: true
          skip-hotbar: false
        base-prices:
          "#minecraft:logs": 6
          birch_log: 4
          diamond: 400
          raw_iron: 20
        overrides:
          stick: 1
          iron_block: 0
        """;

    private static String rendered() {
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = SellSettingsTest.parse(SETTINGS, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        return WorthFile.render(settings, Instant.parse("2026-10-08T07:00:00.123Z"));
    }

    @Test
    void theFileIsYamlWithEveryPriceAndWhereItCameFrom() throws InvalidConfigurationException {
        String text = rendered();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        assertEquals(400, yaml.getLong("prices.minecraft:diamond"));
        assertEquals(18, yaml.getLong("prices.minecraft:iron_ingot"));
        assertEquals(1, yaml.getLong("prices.minecraft:stick"));
        assertEquals(6, yaml.getLong("prices.minecraft:oak_log"));
        assertEquals(4, yaml.getLong("prices.minecraft:birch_log"));
        // 4 planks from the cheapest log: $4 x 0.9 / 4 = $0.90 each
        assertEquals(0.9, yaml.getDouble("below-one-dollar.minecraft:oak_planks"), 1e-9);
        assertEquals(List.of("minecraft:iron_block"), yaml.getStringList("turned-off"));
        assertTrue(text.contains("\"minecraft:diamond\": 400  # base price\n"), text);
        assertTrue(text.contains("\"minecraft:stick\": 1  # override\n"), text);
        assertTrue(text.contains("\"minecraft:iron_ingot\": 18  # from recipe minecraft:iron_ingot_from_smelting_raw_iron\n"), text);
        assertTrue(text.contains("# generated: 2026-10-08T07:00:00Z\n"), text);
        assertTrue(text.contains("# best sell multiplier: 1.5\n"), text);
    }

    @Test
    void writingReplacesTheWholeFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data").resolve("worth-generated.yml");
        WorthFile.write(file, "old text that is longer than the new text\n");
        WorthFile.write(file, "new\n");
        assertEquals("new\n", Files.readString(file));
        assertEquals(List.of(file), Files.list(file.getParent()).toList());
    }
}
