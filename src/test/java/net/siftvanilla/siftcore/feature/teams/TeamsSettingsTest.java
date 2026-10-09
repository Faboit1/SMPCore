package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled teams config, and the worlds where team homes are turned off. */
class TeamsSettingsTest {

    private static final MoneyFormat MONEY = MoneyFormat.defaults();
    private static final Set<String> WORLDS = Set.of("world", "world_nether", "world_the_end");

    @Test
    void theBundledConfigParsesWithTeamHomesEverywhere() {
        ConfigReader reader = new ConfigReader("features/teams.yml", Fakes.yaml("features/teams.yml"));
        TeamsSettings settings = TeamsSettings.parse(reader, MONEY, WORLDS::contains);
        assertEquals(List.of(), reader.problems());
        assertEquals(Set.of(), settings.homeDisabledWorlds());
        assertFalse(settings.homeDisabled("world"));
    }

    @Test
    void disabledWorldsAreReadAndUnknownWorldsReported() {
        YamlConfiguration yaml = Fakes.yaml("features/teams.yml");
        yaml.set("home.disabled-worlds", List.of("world_the_end", "events"));
        ConfigReader reader = new ConfigReader("features/teams.yml", yaml);
        TeamsSettings settings = TeamsSettings.parse(reader, MONEY, WORLDS::contains);
        assertTrue(settings.homeDisabled("world_the_end"));
        assertFalse(settings.homeDisabled("world"));
        assertEquals(1, reader.problems().size(), "events is not a loaded world: " + reader.problems());

        yaml.set("home.disabled-worlds", null);
        ConfigReader older = new ConfigReader("features/teams.yml", yaml);
        assertEquals(Set.of(), TeamsSettings.parse(older, MONEY, WORLDS::contains).homeDisabledWorlds());
        assertEquals(List.of(), older.problems(), "an older teams.yml without the key is fine");
    }
}
