package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The kill effect switch ({@code show-kill-effects}, Display group): its place after the display package's five
 * settings, and that it is only offered while kill effects can play, so it never does nothing.
 */
class KillEffectsSettingTest {

    private static YamlConfiguration shippedYaml() throws Exception {
        try (InputStream in = KillEffectsSettingTest.class.getClassLoader().getResourceAsStream("features/cosmetics.yml")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static CosmeticsSettings parse(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/cosmetics.yml", yaml);
        CosmeticsSettings settings = CosmeticsSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    @Test
    void theSwitchSitsInTheDisplayGroupAfterTheCatalogsFive() throws Exception {
        AtomicReference<CosmeticsSettings> current = new AtomicReference<>(parse(shippedYaml()));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        CosmeticsFeature.registerKillEffects(settings, SettingCategories.DISPLAY, current::get);
        Registry.Entry<?> entry = settings.registry().entry("show-kill-effects");
        assertEquals(SettingCategories.DISPLAY, entry.category());
        assertEquals(6, entry.options().order(), "after scoreboard, feedback, layout, money format and spawn holograms");
        assertEquals(SettingOptions.Apply.NEXT_USE, entry.options().apply(), "read where each effect plays");
        assertNull(entry.setting().permission());
        assertTrue(CosmeticsFeature.KILL_EFFECTS.defaultOn());
        assertTrue(entry.offered(), "the shipped config plays kill effects");
    }

    /** The /cosmetics switch is left out while the server locks or hides the setting, so it never only refuses. */
    @Test
    void theCosmeticsMenuSwitchFollowsLockedAndHidden() throws Exception {
        AtomicReference<CosmeticsSettings> current = new AtomicReference<>(parse(shippedYaml()));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        CosmeticsFeature.registerKillEffects(settings, SettingCategories.DISPLAY, current::get);
        assertTrue(CosmeticsDialogs.offersSwitch(settings, CosmeticsFeature.KILL_EFFECTS));
        settings.overrides(new net.siftvanilla.siftcore.core.player.Overrides(java.util.Map.of(), java.util.Map.of(),
            java.util.Set.of("show-kill-effects")));
        org.junit.jupiter.api.Assertions.assertFalse(CosmeticsDialogs.offersSwitch(settings, CosmeticsFeature.KILL_EFFECTS), "hidden");
        settings.overrides(new net.siftvanilla.siftcore.core.player.Overrides(java.util.Map.of(),
            java.util.Map.of("show-kill-effects", "false"), java.util.Set.of()));
        org.junit.jupiter.api.Assertions.assertFalse(CosmeticsDialogs.offersSwitch(settings, CosmeticsFeature.KILL_EFFECTS), "locked");
    }

    @Test
    void itIsOnlyOfferedWhileKillEffectsCanPlay() throws Exception {
        AtomicReference<CosmeticsSettings> current = new AtomicReference<>(parse(shippedYaml()));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        CosmeticsFeature.registerKillEffects(settings, null, current::get);
        Registry.Entry<?> entry = settings.registry().entry("show-kill-effects");
        assertEquals(SettingCategories.DISPLAY, entry.category(), "the Display group without a category passed in");

        YamlConfiguration off = shippedYaml();
        off.set("enabled", false);
        current.set(parse(off));
        assertFalse(entry.offered(), "every cosmetic off: nothing to hide");

        YamlConfiguration noEffects = shippedYaml();
        noEffects.set("kill-effects.enabled", false);
        current.set(parse(noEffects));
        assertFalse(entry.offered(), "kill effects off");

        YamlConfiguration empty = shippedYaml();
        empty.set("kill-effects.effects", List.of());
        current.set(parse(empty));
        assertFalse(entry.offered(), "no effect anyone can pick");

        current.set(parse(shippedYaml()));
        assertTrue(entry.offered(), "a reload that turns them back on offers the switch again");
    }
}
