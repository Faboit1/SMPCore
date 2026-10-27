package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rows under a setting's old id, as the staff tools and the API read them for players who are not loaded. */
class LegacyRowsTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    @TempDir
    Path dir;

    /** {@code chat-new} took over the rows of {@code chat-old} ("yes" meant on); the old id is registered or not. */
    private static Registry registry(boolean oldStillRegistered) {
        Registry registry = Registry.EMPTY.with(new Toggle("chat-new", false, LABEL, DESCRIPTION, null), SettingCategories.CHAT,
            SettingOptions.<Boolean>builder().legacy("chat-old", stored -> "yes".equals(stored) ? "true" : stored).build());
        return oldStillRegistered
            ? registry.with(new Toggle("chat-old", false, LABEL, DESCRIPTION, null), SettingCategories.CHAT, SettingOptions.defaults())
            : registry;
    }

    @Test
    void anOldRowReadsAsItsSettingUntilTheSettingHasARow() {
        Registry registry = registry(false);
        LegacyRows.Resolved resolved = LegacyRows.resolve(Map.of("chat-old", "yes", "auction-sort", "price"), registry);
        assertEquals(Map.of("chat-new", "true", "auction-sort", "price"), resolved.rows(), "the old row is the setting's, UI state stays");
        assertEquals(Map.of("chat-new", "chat-old"), resolved.moved());

        LegacyRows.Resolved own = LegacyRows.resolve(Map.of("chat-old", "yes", "chat-new", "false"), registry);
        assertEquals(Map.of("chat-old", "yes", "chat-new", "false"), own.rows(), "a row of its own wins; the old row is left as it is");
        assertTrue(own.moved().isEmpty());

        LegacyRows.Resolved unreadable = LegacyRows.resolve(Map.of("chat-old", "maybe"), registry);
        assertEquals(Map.of("chat-old", "maybe"), unreadable.rows(), "an old value the setting can't read moves nowhere");
        assertTrue(unreadable.moved().isEmpty());
    }

    @Test
    void anOldIdStillRegisteredIsASettingOfItsOwn() {
        Registry registry = registry(true);
        assertTrue(registry.entry("chat-new").superseded());
        assertEquals(Map.of("chat-old", "yes"), LegacyRows.resolve(Map.of("chat-old", "yes"), registry).rows());
    }

    @Test
    void theSharedTeleportChoiceReadsTheOldFriendsSwitch() throws Exception {
        try (SettingsDb db = new SettingsDb(this.dir)) {
            Registry registry = db.settings.registry();
            // In the shared settings alone the old TPA switch is not registered, as once TPA reads the choice.
            assertEquals(Map.of("friends-tpa", "all"), LegacyRows.resolve(Map.of("tpa-friends", "true"), registry).rows());
            UUID gone = UUID.randomUUID();
            db.insert(gone, "tpa-friends", "true");
            db.insert(gone, "auction-sort", "price");
            assertEquals(SetResult.CHANGED, db.settings.set(gone, SharedSettings.FRIENDS_TPA, AutoAccept.NOBODY, Change.admin("Mod")));
            assertNull(db.row(gone, "tpa-friends"), "core deleted the old row with the change");
            assertNull(db.row(gone, "friends-tpa"), "the default needs no row");
            assertEquals("price", db.row(gone, "auction-sort"), "other rows stay");
            db.join(gone);
            assertEquals(AutoAccept.NOBODY, db.settings.get(gone, SharedSettings.FRIENDS_TPA), "nothing moved back over the change");
        }
    }
}
