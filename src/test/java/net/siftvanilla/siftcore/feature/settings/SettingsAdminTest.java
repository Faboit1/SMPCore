package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.player.Registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code /sift settings <player> reset ...} puts back, leaves alone and names. */
class SettingsAdminTest {

    @TempDir
    Path dir;

    @Test
    void aResetTakesStoredRowsAndNamesTheLockedOnes() throws Exception {
        try (SettingsDb db = new SettingsDb(this.dir)) {
            Registry registry = db.settings.registry();
            List<Registry.Entry<?>> targets = List.of(registry.entry("sound-volume"), registry.entry("quiet-in-combat"),
                registry.entry("sound-clicks"), registry.entry("friends-tpa"));
            // An offline player: a volume, a locked setting's row, and the old TPA switch standing in for friends-tpa.
            Set<String> stored = LegacyRows.resolve(Map.of("sound-volume", "30", "quiet-in-combat", "true", "tpa-friends", "true",
                "auction-sort", "price"), registry).rows().keySet();
            SettingsAdmin.ResetPlan plan = SettingsAdmin.plan(targets, stored, setting -> setting.id().equals("quiet-in-combat"));
            assertEquals(List.of("sound-volume", "friends-tpa"), plan.ids(), "rows of their own or under an old id; nothing for unset ones");
            assertEquals(List.of("quiet-in-combat"), plan.locked(), "a locked setting is left alone and named");

            SettingsAdmin.ResetPlan none = SettingsAdmin.plan(targets, Set.of(), setting -> true);
            assertTrue(none.reset().isEmpty() && none.locked().isEmpty(), "nothing stored, nothing to say about locks");
        }
    }
}
