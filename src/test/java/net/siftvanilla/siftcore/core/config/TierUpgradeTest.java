package net.siftvanilla.siftcore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.feature.crates.Crate;
import net.siftvanilla.siftcore.feature.crates.CratesSettings;
import net.siftvanilla.siftcore.feature.crates.CratesTestAccess;
import net.siftvanilla.siftcore.feature.crates.Reward;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * A live server that never edited its crates.yml or shards.yml gets exactly the seven crate tiers and the new shard shop
 * when it updates: YamlFiles adds the new keys and gives unedited entries the new values, but never removes a key, so
 * every reward the old file shipped must still exist with the same kind (a removed or retyped one would stay behind and
 * break its crate). The files from before the tiers are in {@code src/test/resources/upgrade}.
 */
class TierUpgradeTest {

    private static YamlConfiguration resource(String path) throws Exception {
        try (InputStream in = TierUpgradeTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is there");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    /** What YamlFiles does to a server file that equals the previous shipped copy. */
    private static YamlConfiguration upgrade(String before, String shipped) throws Exception {
        YamlConfiguration server = resource(before);
        YamlConfiguration previous = resource(before);
        YamlConfiguration jar = resource(shipped);
        YamlFiles.addNewKeys(server, jar, YamlFiles.leafKeys(previous));
        YamlFiles.updateUnedited(server, jar, previous);
        return server;
    }

    @Test
    void anUneditedCratesFileBecomesTheSevenTiers() throws Exception {
        YamlConfiguration upgraded = upgrade("upgrade/crates-before-tiers.yml", "features/crates.yml");
        assertEquals(YamlFiles.leafKeys(resource("features/crates.yml")).stream().sorted().toList(),
            YamlFiles.leafKeys(upgraded).stream().sorted().toList(), "no key of the old file is left behind");
        ConfigReader reader = new ConfigReader("features/crates.yml", upgraded);
        CratesSettings live = CratesSettings.parse(reader, CratesTestAccess.catalog(), MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        ConfigReader shippedReader = new ConfigReader("features/crates.yml", resource("features/crates.yml"));
        CratesSettings shipped = CratesSettings.parse(shippedReader, CratesTestAccess.catalog(), MoneyFormat.defaults());
        assertEquals(describe(shipped), describe(live), "the upgraded file means exactly what the shipped one does");
        assertEquals(List.of("basic", "uncommon", "rare", "epic", "legendary", "mythic", "celestial"),
            live.crates().stream().map(Crate::id).toList(), "listed by tier although the new crates are at the end of the old file");
    }

    @Test
    void anUneditedShardsFileSellsTheNewKeys() throws Exception {
        YamlConfiguration upgraded = upgrade("upgrade/shards-before-tiers.yml", "features/shards.yml");
        YamlConfiguration shipped = resource("features/shards.yml");
        for (String key : YamlFiles.leafKeys(shipped)) {
            assertEquals(shipped.get(key), upgraded.get(key), key + " has the shipped value after the update");
        }
    }

    /** Every crate's rewards, with what they give, by id (file order does not matter). */
    private static Map<String, List<String>> describe(CratesSettings settings) {
        Map<String, List<String>> crates = new LinkedHashMap<>();
        for (Crate crate : settings.crates()) {
            List<String> rewards = new ArrayList<>();
            for (Reward reward : crate.rewards()) {
                rewards.add(reward.id() + " " + reward.weight() + " " + reward.rarity() + " " + reward.display() + " " + reward.kind());
            }
            rewards.sort(null);
            crates.put(crate.id() + " " + crate.name() + " " + crate.tier() + " " + crate.color() + " " + crate.icon(), rewards);
        }
        return crates;
    }
}
