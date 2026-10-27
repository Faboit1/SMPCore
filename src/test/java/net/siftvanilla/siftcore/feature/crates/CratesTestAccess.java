package net.siftvanilla.siftcore.feature.crates;

import java.time.Duration;
import java.util.List;
import net.siftvanilla.siftcore.core.player.PlayerSettings;

/** Lets tests of other packages register the crate settings (the kits share their settings group). */
public final class CratesTestAccess {

    private CratesTestAccess() {
    }

    /** The items, enchantments, mobs and worlds the shipped crates.yml may name (for parsing it in other packages). */
    public static CratesSettings.Catalog catalog() {
        return CratesSettingsTest.CATALOG;
    }

    /** Registers the crate settings as the shipped crates.yml offers them. */
    public static void registerShipped(PlayerSettings settings) {
        CratesSettings config = CratePlayerSettingsTest.config(10, true, true,
            CratePlayerSettingsTest.keyall(true, List.of(Duration.ofMinutes(5)), Duration.ofSeconds(10)),
            List.of(new Rarity("common", "Common", false, false), new Rarity("legendary", "Legendary", true, true)));
        CratePlayerSettings.register(settings, () -> config);
    }
}
