package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ExpectedValueTest {

    private static Reward reward(String id, double weight, Reward.Kind kind) {
        return new Reward(id, weight, "common", id, true, null, kind);
    }

    @Test
    void averagesFollowTheChances() {
        List<Reward> rewards = List.of(
            reward("cash", 1, new Reward.Money(1_000)),
            reward("shards", 1, new Reward.Shards(40)),
            reward("diamonds", 2, new Reward.Item("minecraft:diamond", 4, null, List.of(), Map.of())),
            reward("keys", 4, new Reward.Keys("rare", 2)),
            reward("rank", 2, new Reward.Command(List.of("say hi"))));
        ExpectedValue value = ExpectedValue.of(rewards, r -> r.id().equals("diamonds") ? 1_600 : 0);
        assertEquals(100, value.money(), 1e-9);
        assertEquals(4, value.shards(), 1e-9);
        assertEquals(320, value.itemWorth(), 1e-9);
        assertEquals(0.8, value.keys().get("rare"), 1e-9);
        assertEquals(0.2, value.commandShare(), 1e-9);
    }

    @Test
    void nothingToWinIsZero() {
        ExpectedValue value = ExpectedValue.of(List.of(), r -> 0);
        assertEquals(0, value.money());
        assertEquals(Map.of(), value.keys());
    }

    /** The shipped crates keep money a small extra; these are the numbers docs/features/crates.md gives. */
    @Test
    void shippedMoneyAndShardsPerKey() throws Exception {
        YamlConfiguration yaml = CratesSettingsTest.bundled();
        ConfigReader reader = new ConfigReader("features/crates.yml", yaml);
        CratesSettings settings = CratesSettings.parse(reader, CratesSettingsTest.CATALOG, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        double[][] expected = {{175, 0.6}, {810, 3.2}, {3_800, 12}, {20_750, 40}};
        String[] crates = {"basic", "rare", "epic", "legendary"};
        for (int i = 0; i < crates.length; i++) {
            ExpectedValue value = ExpectedValue.of(settings.crate(crates[i]).rewards(), r -> 0);
            assertEquals(expected[i][0], value.money(), 1e-6, crates[i] + " money per key");
            assertEquals(expected[i][1], value.shards(), 1e-6, crates[i] + " shards per key");
        }
    }
}
