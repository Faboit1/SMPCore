package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The pure part of the settings dialog, unit tested: grouping switches by category, paging, dialog input keys, and
 * which switches a player changed.
 * <p>
 * Dialog input keys may only hold letters, digits and {@code _}, so a toggle id like {@code tpa-requests} becomes
 * {@code tpa_requests} (made unique if two ids would collide). Only switches the player flipped in the dialog are
 * saved: a value changed elsewhere since the dialog opened (a command, another dialog) is never overwritten by the
 * stale value the dialog still showed. Flips are carried from page to page of a category as "pending" changes and
 * saved together.
 */
final class SettingsForm {

    /**
     * One switch.
     *
     * @param key    the dialog input key
     * @param toggle the toggle id
     * @param shown  the value the dialog showed
     */
    record Field(String key, String toggle, boolean shown) {
    }

    /**
     * One group of switches.
     *
     * @param category the category id
     * @param items    the group's switches, in registration order
     */
    record Group<T>(String category, List<T> items) {
    }

    private SettingsForm() {
    }

    /**
     * Groups switches by category id. Groups come in the order of {@code order} (category ids, first to last); a
     * category not in it comes after the listed ones, in the order its first switch appears.
     */
    static <T> List<Group<T>> group(List<T> items, Function<T, String> category, List<String> order) {
        Map<String, List<T>> byCategory = new LinkedHashMap<>();
        for (String id : order) {
            byCategory.put(id, new ArrayList<>());
        }
        for (T item : items) {
            byCategory.computeIfAbsent(category.apply(item), k -> new ArrayList<>()).add(item);
        }
        List<Group<T>> groups = new ArrayList<>(byCategory.size());
        byCategory.forEach((id, list) -> {
            if (!list.isEmpty()) {
                groups.add(new Group<>(id, List.copyOf(list)));
            }
        });
        return groups;
    }

    /** How many pages {@code count} switches fill, at least one. */
    static int pages(int count, int pageSize) {
        return Math.max(1, (count + pageSize - 1) / pageSize);
    }

    /** A page number brought into range (1 to the last page). */
    static int clampPage(int page, int count, int pageSize) {
        return Math.clamp(page, 1, pages(count, pageSize));
    }

    /** The switches on one page (1-based, clamped). */
    static <T> List<T> page(List<T> items, int page, int pageSize) {
        int current = clampPage(page, items.size(), pageSize);
        int from = (current - 1) * pageSize;
        return List.copyOf(items.subList(Math.min(from, items.size()), Math.min(from + pageSize, items.size())));
    }

    /** Fields for toggles (id and the value to show), in order. */
    static List<Field> fields(List<Map.Entry<String, Boolean>> toggles) {
        List<Field> fields = new ArrayList<>(toggles.size());
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, Boolean> toggle : toggles) {
            String base = key(toggle.getKey());
            String key = base;
            for (int n = 2; !used.add(key); n++) {
                key = base + "_" + n;
            }
            fields.add(new Field(key, toggle.getKey(), toggle.getValue()));
        }
        return fields;
    }

    /** A toggle id as a dialog input key. */
    static String key(String toggle) {
        StringBuilder sb = new StringBuilder(toggle.length());
        for (int i = 0; i < toggle.length(); i++) {
            char c = toggle.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ? c : '_');
        }
        return sb.isEmpty() ? "_" : sb.toString();
    }

    /**
     * The toggles the player flipped on one page: toggle id to its new value, in dialog order. Inputs missing from the
     * submission, and values that are not real booleans, count as unchanged.
     */
    static Map<String, Boolean> changes(List<Field> fields, Map<String, Object> submitted) {
        return merge(Map.of(), fields, submitted);
    }

    /**
     * The pending changes after a page was submitted: the earlier pending changes plus the switches flipped on this
     * page (a switch flipped back to what the player had pending is pending with its new value).
     */
    static Map<String, Boolean> merge(Map<String, Boolean> pending, List<Field> fields, Map<String, Object> submitted) {
        Map<String, Boolean> merged = new LinkedHashMap<>(pending);
        for (Field field : fields) {
            if (submitted.get(field.key()) instanceof Boolean value && value != field.shown()) {
                merged.put(field.toggle(), value);
            }
        }
        return merged;
    }

    /** The pending changes that still change something: those whose value differs from the stored one now. */
    static Map<String, Boolean> effective(Map<String, Boolean> pending, Predicate<String> current) {
        Map<String, Boolean> effective = new LinkedHashMap<>();
        pending.forEach((toggle, value) -> {
            if (value != current.test(toggle)) {
                effective.put(toggle, value);
            }
        });
        return effective;
    }
}
