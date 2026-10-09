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
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals(title), bot.name + " sees '" + title + "': "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** {@code /settings <group>} and waits for the freshly sent first page. */
    static void openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        bot.command("settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
    }

    /**
     * Opens a settings group and changes inputs wherever they are: walks the pages with Next page (changes carried
     * along), sets each key on the page that shows it (checking a choice offers the wanted option), and saves on the
     * page where the last one was found.
     */
    static void editSettings(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        bot.clearMessages();
        openGroup(e2e, bot, group, title);
        Set<String> left = new HashSet<>(wanted.keySet());
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            Map<String, Object> values = current.values();
            for (String key : List.copyOf(left)) {
                if (current.inputs().containsKey(key)) {
                    Object value = wanted.get(key);
                    if (value instanceof String option) {
                        e2e.expect(current.options().getOrDefault(key, List.of()).contains(option), key + " offers " + option + ": "
                            + current.options().get(key));
                    }
                    values.put(key, value);
                    left.remove(key);
                }
            }
            if (left.isEmpty()) {
                e2e.click(bot, "Save", values);
                return;
            }
            e2e.expect(current.button("Next page") != null, "inputs " + left + " on a later page of " + title + " (last page: "
                + current.inputs().keySet() + ")");
            e2e.click(bot, "Next page", values);
        }
        throw new E2E.Failure("too many pages in " + title);
    }

    /** Every input of a settings group across its pages: a choice's option ids, or the input kind. */
    static Map<String, List<String>> groupInputs(E2E e2e, Bot bot, String group, String title) {
        openGroup(e2e, bot, group, title);
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            current.inputs().forEach((key, kind) -> inputs.put(key, current.options().getOrDefault(key, List.of(kind))));
            if (current.button("Next page") == null) {
                return inputs;
            }
            e2e.click(bot, "Next page", current.values());
        }
        throw new E2E.Failure("too many pages in " + title);
    }
}
