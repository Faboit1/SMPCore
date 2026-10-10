package net.siftvanilla.e2e;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.SetResult;

/**
 * Player settings steps shared by the AFK, shard shop and staff settings scenarios: changing a setting through the
 * settings dialog ({@code /settings <group>}), changing one as another plugin would (the API), and reading the stored
 * row.
 */
final class AfkStaffSettingSteps {

    /** The title of the AFK &amp; shards settings page. */
    static final String AFK_PAGE = "AFK & shards settings";
    /** The title of the Staff settings page. */
    static final String STAFF_PAGE = "Staff settings";

    private AfkStaffSettingSteps() {
    }

    /** Changes an online player's setting through the API; the change must go through. */
    static <T> void set(E2E e2e, String name, PlayerSetting<T> setting, T value) {
        SetResult result = e2e.services().settings().set(e2e.uuid(name), setting, value, Change.api("e2e"));
        e2e.expect(result.succeeded(), name + ": " + setting.id() + " = " + value + " went through: " + result);
    }

    /** A player's stored row for a setting, or null when none is stored (read after every queued write). */
    static String stored(E2E e2e, UUID player, String setting) {
        try {
            return e2e.services().database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, setting);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading " + setting + " failed: " + e);
        }
    }

    static void expectStored(E2E e2e, UUID player, String setting, String value) {
        e2e.eventually(() -> Objects.equals(value, stored(e2e, player, setting)), setting + " stored as " + value
            + " (is " + stored(e2e, player, setting) + ")");
    }

    /** Waits for a dialog titled exactly {@code title}. */
    static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        return SettingsSteps.page(e2e, bot, title);
    }

    /** The settings page open now as the form it used to be ({@link SettingsSteps#form}). */
    static Bot.SeenDialog form(E2E e2e, Bot bot) {
        return SettingsSteps.form(e2e, bot.dialog());
    }

    /** {@code /settings <group>} and waits for the freshly sent first page. */
    static void openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        bot.command("settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
    }

    /**
     * Opens a settings group and sets settings on it the way a player does, on their buttons ({@link SettingsSteps#edit});
     * a choice must offer the wanted option.
     */
    static void editSettings(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        bot.clearMessages();
        SettingsSteps.edit(e2e, bot, group, title, wanted);
    }

    /** Every setting a group shows the player: a choice's option ids, or the kind ({@link SettingsSteps#inputs}). */
    static Map<String, List<String>> groupInputs(E2E e2e, Bot bot, String group, String title) {
        return SettingsSteps.inputs(e2e, bot, group, title);
    }
}
