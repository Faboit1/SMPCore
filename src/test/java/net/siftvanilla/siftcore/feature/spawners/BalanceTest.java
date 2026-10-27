package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The balance target: at the base sell price, a spawner bought in the shop pays its price back in 50 to 80 hours of
 * a player being nearby. Reads the bundled spawner, shop and sell configs, so changing one of them without the
 * others fails here. The table in docs/features/spawners.md shows the same numbers.
 */
class BalanceTest {

    /**
     * Prices the sell feature derives from recipes (they are not base prices): a gold nugget is a ninth of an ingot,
     * sugar comes from sugar cane, white wool from the wool tag, and glass bottles, sticks and poppies are worth
     * less than a dollar or nothing (they don't sell).
     */
    private static final Map<String, Long> DERIVED = Map.of(
        "gold_nugget", 3L, "sugar", 5L, "white_wool", 3L, "glass_bottle", 0L, "stick", 0L, "poppy", 0L);

    private static YamlConfiguration load(String path) throws Exception {
        try (InputStream in = BalanceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static long amount(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        double factor = 1;
        if (value.endsWith("k")) {
            factor = 1_000;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            factor = 1_000_000;
            value = value.substring(0, value.length() - 1);
        }
        return Math.round(Double.parseDouble(value) * factor);
    }

    @Test
    void shopSpawnersPayBackInFiftyToEightyHours() throws Exception {
        SpawnersSettings settings = SpawnersSettingsTest.parseBundled();
        ConfigurationSection base = load("features/sell.yml").getConfigurationSection("base-prices");
        ConfigurationSection shop = load("features/shop.yml").getConfigurationSection("categories.spawners.items");
        assertNotNull(base);
        assertNotNull(shop);
        double cyclesPerHour = MobDef.cyclesPerHour(settings.interval().toMillis() / 1000.0);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String entry : shop.getKeys(false)) {
            String mob = shop.getString(entry + ".spawner");
            long price = amount(shop.getString(entry + ".price"));
            MobDef def = settings.mob(mob);
            if (def == null) {
                problems.add("the shop sells " + mob + " spawners but features/spawners.yml has no " + mob);
                continue;
            }
            double valuePerKill = 0;
            for (DropEntry drop : def.drops()) {
                String item = drop.item().substring("minecraft:".length());
                long unit = base.contains(item) ? base.getLong(item) : DERIVED.getOrDefault(item, -1L);
                if (unit < 0) {
                    problems.add(mob + " drops " + item + ", which has no known price");
                    continue;
                }
                valuePerKill += unit * drop.expectedPerKill();
            }
            double perHour = cyclesPerHour * def.killsPerCycle() * valuePerKill;
            double hours = price / perHour;
            if (hours < 50 || hours > 80) {
                problems.add(String.format(Locale.ROOT, "%s pays back $%,d in %.1f hours ($%.0f an hour)", mob, price, hours, perHour));
            }
            checked++;
        }
        assertTrue(checked >= 15, "every shop spawner was checked (" + checked + ")");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void storagesHoldAtLeastAnHourOfLoot() throws Exception {
        SpawnersSettings settings = SpawnersSettingsTest.parseBundled();
        double interval = settings.interval().toMillis() / 1000.0;
        for (MobDef mob : settings.mobs().values()) {
            double perHour = mob.itemsPerHour(interval);
            long capacity = StorageMath.capacity(1, settings.slots(mob.id()));
            assertTrue(capacity >= perHour, mob.id() + " makes " + perHour + " items an hour but holds " + capacity);
        }
    }
}
