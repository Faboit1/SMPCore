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
 * Player settings steps the crates, kits and spawners scenarios share: changing settings through the {@code /settings}
 * dialog (with every page of a group walked), through the API (the settings command of the settings dialog's next
 * version is not there yet), and reading what is stored.
 */
final class ItemSettingsSteps {

    private ItemSettingsSteps() {
    }

    /** Waits for a dialog titled exactly {@code title}. */
    static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        return SettingsSteps.page(e2e, bot, title);
    }

    /** {@code /settings <group>} and waits for the freshly sent first page. */
    static void openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        e2e.sleep(700);
        bot.command("settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
    }

    /**
     * Opens a settings group and sets settings on it the way a player does, on their buttons ({@link SettingsSteps#edit});
     * a choice must offer the wanted option.
     */
    static void edit(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        bot.clearMessages();
        SettingsSteps.edit(e2e, bot, group, title, wanted);
    }

    /** Every setting a group shows the player: a choice's option ids, or the kind ({@link SettingsSteps#inputs}). */
    static Map<String, List<String>> inputs(E2E e2e, Bot bot, String group, String title) {
        return SettingsSteps.inputs(e2e, bot, group, title);
    }

    /** Opens a settings group (all on one page) as the form it used to be, checking it shows {@code key}. */
    static Bot.SeenDialog pageWith(E2E e2e, Bot bot, String group, String title, String key) {
        Bot.SeenDialog page = SettingsSteps.form(e2e, SettingsSteps.openGroup(e2e, bot, group, title));
        e2e.expect(page.inputs().containsKey(key), key + " on " + title + ": " + page.inputs().keySet());
        return page;
    }

    /** Changes a player's setting as another plugin would (the API cause); the change must go through. */
    static <T> void set(E2E e2e, UUID player, PlayerSetting<T> setting, T value) {
        SetResult result = e2e.services().settings().set(player, setting, value, Change.api("e2e"));
        e2e.expect(result.succeeded(), setting.id() + " = " + value + " went through: " + result);
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

    /** Waits until a setting's stored row holds {@code value} (null: no row). */
    static void expectStored(E2E e2e, UUID player, String setting, String value) {
        e2e.eventually(() -> Objects.equals(value, stored(e2e, player, setting)), setting + " stored as " + value + " (stored: "
            + stored(e2e, player, setting) + ")");
    }
}
