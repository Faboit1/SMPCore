package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsPlaceholdersTest {

    private static final UUID ALEX = UUID.randomUUID();

    @TempDir
    Path dir;
    private SettingsDb db;

    @BeforeEach
    void open() throws Exception {
        this.db = new SettingsDb(this.dir);
    }

    @AfterEach
    void close() {
        this.db.close();
    }

    private String value(UUID player, String id) {
        return SettingsPlaceholders.resolve(this.db.settings, this.db.lang, player, id, false);
    }

    private String text(UUID player, String id) {
        return SettingsPlaceholders.resolve(this.db.settings, this.db.lang, player, id, true);
    }

    @Test
    void valuesInStoredFormAndAsText() throws Exception {
        PlayerSettings settings = this.db.settings;
        this.db.join(ALEX);
        assertEquals("100", value(ALEX, "sound-volume"));
        assertEquals("100%", text(ALEX, "sound-volume"));
        assertEquals("true", value(ALEX, "sound-notify"));
        assertEquals("on", text(ALEX, "sound-notify"));
        settings.set(ALEX, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        settings.set(ALEX, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.CHAT, Change.feature());
        assertEquals("30", value(ALEX, "sound-volume"));
        assertEquals("30%", text(ALEX, "sound-volume"));
        assertEquals("chat", value(ALEX, "feedback-channel"));
        assertEquals("Chat", text(ALEX, "feedback-channel"));
        assertEquals("chat", value(ALEX, "sell_receipts"), "ids with '_' work like ids with '-'");
        assertEquals("100", value(UUID.randomUUID(), "sound-volume"), "players who are not online read the default");
        assertEquals("100", value(null, "sound-volume"), "and so does no player at all");
    }

    @Test
    void unknownIdsArePassedOnAndPrivateOrHiddenSettingsAreEmpty() {
        assertNull(value(ALEX, "no-such-setting"), "PlaceholderAPI leaves unknown placeholders alone");
        assertNull(value(ALEX, null));
        assertEquals("", value(ALEX, "seen-privacy"), "privacy settings keep their value private");
        assertEquals("", text(ALEX, "balance-privacy"));
        assertEquals("", value(ALEX, "hide-from-leaderboards"), "settings that need a permission too");
        this.db.settings.overrides(new Overrides(Map.of(), Map.of(), Set.of("sound-volume")));
        assertEquals("", value(ALEX, "sound-volume"), "hidden by the server");
        Toggle open = new Toggle("public-perk", true, MessageKey.ui("test.label"), MessageKey.ui("test.description"), "some.perk");
        this.db.settings.register(SettingCategories.DISPLAY, open, SettingOptions.<Boolean>builder().placeholder(true).build());
        assertEquals("true", value(ALEX, "public-perk"), "a permission setting that opts in shows its value");
    }

    @Test
    void theChangedCountCountsVisibleChangedSettings() throws Exception {
        PlayerSettings settings = this.db.settings;
        this.db.join(ALEX);
        assertEquals(0, SettingsPlaceholders.changed(settings, ALEX, permission -> false));
        settings.set(ALEX, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        settings.set(ALEX, SharedSettings.SOUND_CLICKS, false, Change.feature());
        settings.set(ALEX, SharedSettings.HIDE_FROM_LEADERBOARDS, true, Change.feature());
        assertEquals(2, SettingsPlaceholders.changed(settings, ALEX, permission -> false), "not the one they can't see");
        settings.set(ALEX, SharedSettings.SOUND_VOLUME, 100L, Change.feature());
        assertEquals(1, SettingsPlaceholders.changed(settings, ALEX, permission -> false), "back to the default");
        settings.overrides(new Overrides(Map.of(), Map.of("sound-clicks", "true"), Set.of()));
        assertEquals(0, SettingsPlaceholders.changed(settings, ALEX, permission -> false), "locked settings never count");
    }

    @Test
    void registeredUnderTheirNames() {
        Placeholders placeholders = new Placeholders();
        new SettingsPlaceholders(this.db.settings, this.db.lang).register(placeholders);
        assertEquals("100", placeholders.resolve(null, "setting_sound-volume"));
        assertEquals("100%", placeholders.resolve(null, "settingtext_sound-volume"));
        assertEquals("0", placeholders.resolve(null, "settings_changed"));
        assertNull(placeholders.resolve(null, "setting_nothing"));
    }
}
