package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.text.Lang;
import org.junit.jupiter.api.Test;

class SettingsTableTest {

    @Test
    void everySettingIsOneRowInDialogOrder() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        settings.reads(SharedSettings.SOUND_MENTION);
        Lang lang = SettingsDb.lang();
        String table = SettingsTable.markdown(settings.registry(), key -> lang.plain(key), "1.0.0");
        List<String> rows = table.lines().filter(line -> line.startsWith("| ") && !line.startsWith("| Group")).toList();
        assertEquals(settings.registry().byId().size(), rows.size(), table);
        assertTrue(table.contains("Every player setting SiftCore 1.0.0 registers (" + rows.size() + ")"));
        assertEquals("| Sounds (`sound`) | `sound-volume` | number | 0-100 step 10 (%) | `100` | SiftCore volume |  |  |",
            rows.stream().filter(row -> row.contains("`sound-volume`")).findFirst().orElseThrow());
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("| Sounds (`sound`) | `sound-mention` | choice | default, bell, pling, chime, off"
            + " | `default` | Mention sound |")), table);
        assertTrue(rows.stream().anyMatch(row -> row.contains("`sound-pm`") && row.contains("not offered now")), "nothing plays it yet");
        assertTrue(rows.stream().anyMatch(row -> row.contains("`hide-from-leaderboards`") && row.contains("`siftcore.stats.hide`")),
            "the permission");
        assertTrue(rows.stream().anyMatch(row -> row.contains("`sound-notify` | toggle | true, false | `true`")));
        int sound = table.indexOf("`sound-volume`");
        int display = table.indexOf("`feedback-channel`");
        assertTrue(sound > 0 && display > sound, "groups in dialog order");
    }
}
