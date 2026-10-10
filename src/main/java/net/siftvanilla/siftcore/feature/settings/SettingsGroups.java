package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategory;

/**
 * Which groups and settings a player sees (pure, unit tested): the groups holding at least one setting they may see,
 * in the order the server set ({@code categories} in {@code features/settings.yml}, else the built-in order), with
 * the icon and button colour the server set or the built-in ones.
 */
final class SettingsGroups {

    /** A group as one player sees it: the category and the settings in it they may see, in dialog order. */
    record Shown(SettingCategory category, List<Registry.Entry<?>> entries) {
        Shown {
            entries = List.copyOf(entries);
        }

        /** The id of the group. */
        String id() {
            return this.category.id();
        }
    }

    private SettingsGroups() {
    }

    /** The groups the player sees, each with at least one setting, in the server's order. */
    static List<Shown> groups(Registry registry, Predicate<Registry.Entry<?>> visible, Map<String, SettingsConfig.CategoryOverride> overrides) {
        List<Shown> shown = new ArrayList<>();
        for (SettingCategory category : ordered(registry.categories(), overrides)) {
            List<Registry.Entry<?>> entries = new ArrayList<>();
            for (Registry.Entry<?> entry : registry.in(category.id())) {
                if (visible.test(entry)) {
                    entries.add(entry);
                }
            }
            if (!entries.isEmpty()) {
                shown.add(new Shown(category, entries));
            }
        }
        return shown;
    }

    /**
     * Categories in the server's order. A stable sort of the built-in order: groups the server did not move keep their
     * built-in order among themselves.
     */
    static List<SettingCategory> ordered(List<SettingCategory> categories, Map<String, SettingsConfig.CategoryOverride> overrides) {
        List<SettingCategory> sorted = new ArrayList<>(categories);
        sorted.sort(Comparator.comparingInt(category -> order(category, overrides)));
        return sorted;
    }

    /** A group's place in the list: the server's order, else the built-in one. */
    static int order(SettingCategory category, Map<String, SettingsConfig.CategoryOverride> overrides) {
        SettingsConfig.CategoryOverride override = overrides.get(category.id());
        return override != null && override.order() != null ? override.order() : category.order();
    }

    /** A group's icon name: the server's, else the built-in one (null for none). */
    static String icon(SettingCategory category, Map<String, SettingsConfig.CategoryOverride> overrides) {
        SettingsConfig.CategoryOverride override = overrides.get(category.id());
        return override != null && override.icon() != null ? override.icon() : category.icon();
    }

    /** A group's button colour: the server's, else the built-in one (null for the primary text colour). */
    static TextColor color(SettingCategory category, Map<String, SettingsConfig.CategoryOverride> overrides) {
        SettingsConfig.CategoryOverride override = overrides.get(category.id());
        return override != null && override.color() != null ? override.color() : category.color();
    }

    /** The group with this id among the shown ones (case ignored), or null. */
    static Shown find(List<Shown> groups, String id) {
        for (Shown group : groups) {
            if (group.id().equalsIgnoreCase(id)) {
                return group;
            }
        }
        return null;
    }

    /** The group a setting is shown in, or null when the player doesn't see it. */
    static Shown holding(List<Shown> groups, String settingId) {
        for (Shown group : groups) {
            for (Registry.Entry<?> entry : group.entries()) {
                if (entry.id().equals(settingId)) {
                    return group;
                }
            }
        }
        return null;
    }
}
