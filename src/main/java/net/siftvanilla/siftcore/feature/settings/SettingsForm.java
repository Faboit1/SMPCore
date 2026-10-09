package net.siftvanilla.siftcore.feature.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;

/**
 * The pure part of the settings dialog, unit tested: paging, dialog input keys, and which settings a player changed.
 * <p>
 * Values travel as their stored form (a toggle {@code true}/{@code false}, an option id, a number), whatever the kind
 * of input shows them. Only settings the player changed in the dialog are saved: a value changed elsewhere since the
 * dialog opened (a command, another dialog) is never overwritten by the stale value the dialog still showed. Changes
 * are carried from page to page of a group as "pending" changes and saved together.
 */
final class SettingsForm {

    /**
     * One input of a settings page.
     *
     * @param key     the dialog input key
     * @param setting the setting id
     * @param kind    how it is shown
     * @param shown   the value the dialog showed, in stored form
     */
    record Field(String key, String setting, PlayerSetting.Kind kind, String shown) {
    }

    private SettingsForm() {
    }

    /** How many pages {@code count} settings fill, at least one. */
    static int pages(int count, int pageSize) {
        return Math.max(1, (count + pageSize - 1) / pageSize);
    }

    /** A page number brought into range (1 to the last page). */
    static int clampPage(int page, int count, int pageSize) {
        return Math.clamp(page, 1, pages(count, pageSize));
    }

    /** The items on one page (1-based, clamped). */
    static <T> List<T> page(List<T> items, int page, int pageSize) {
        int current = clampPage(page, items.size(), pageSize);
        int from = (current - 1) * pageSize;
        return List.copyOf(items.subList(Math.min(from, items.size()), Math.min(from + pageSize, items.size())));
    }

    /** A setting id as a dialog input key (letters, digits and {@code _}; the registry makes them unique). */
    static String key(String id) {
        return Registry.inputKey(id);
    }

    /**
     * A submitted input value in stored form, or null when it is not the kind of value its input sends (a toggle sends
     * a Boolean, a choice an option id, a slider a whole number).
     */
    static String encode(PlayerSetting.Kind kind, Object value) {
        return switch (kind) {
            case TOGGLE -> value instanceof Boolean b ? b.toString() : null;
            case CHOICE -> value instanceof String s ? s : null;
            case NUMBER -> value instanceof Long l ? l.toString() : null;
        };
    }

    /** The settings the player changed on one page: setting id to its new stored value, in dialog order. */
    static Map<String, String> changes(List<Field> fields, Map<String, Object> submitted) {
        return merge(Map.of(), fields, submitted);
    }

    /**
     * The pending changes after a page was submitted: the earlier pending changes plus the settings changed on this
     * page (compared with what the page showed, which already includes earlier pending changes). Inputs missing from
     * the submission, and values of the wrong kind, count as unchanged.
     */
    static Map<String, String> merge(Map<String, String> pending, List<Field> fields, Map<String, Object> submitted) {
        Map<String, String> merged = new LinkedHashMap<>(pending);
        for (Field field : fields) {
            String value = encode(field.kind(), submitted.get(field.key()));
            if (value != null && !value.equals(field.shown())) {
                merged.put(field.setting(), value);
            }
        }
        return merged;
    }

    /**
     * The pending changes that still change something: those whose value differs from the player's value now.
     * {@code current} gives a setting's value in stored form, or null for a setting that no longer exists (skipped).
     */
    static Map<String, String> effective(Map<String, String> pending, Function<String, String> current) {
        Map<String, String> effective = new LinkedHashMap<>();
        pending.forEach((setting, value) -> {
            String now = current.apply(setting);
            if (now != null && !value.equals(now)) {
                effective.put(setting, value);
            }
        });
        return effective;
    }
}
