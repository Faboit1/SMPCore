package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.feature.settings.SettingsConfig.CategoryOverride;
import net.siftvanilla.siftcore.feature.settings.SettingsGroups.Shown;
import org.junit.jupiter.api.Test;

class SettingsGroupsTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    private static Toggle toggle(String id, String permission) {
        return new Toggle(id, true, LABEL, DESCRIPTION, permission);
    }

    private static Registry registry() {
        return Registry.EMPTY
            .with(toggle("sound-a", null), SettingCategories.SOUND, SettingOptions.<Boolean>builder().order(2).build())
            .with(toggle("sound-b", null), SettingCategories.SOUND, SettingOptions.<Boolean>builder().order(1).build())
            .with(toggle("chat-a", null), SettingCategories.CHAT, SettingOptions.defaults())
            .with(toggle("spy", "staff.spy"), SettingCategories.STAFF, SettingOptions.defaults())
            .with(toggle("privacy-a", null), SettingCategories.PRIVACY, SettingOptions.defaults())
            .with(toggle("privacy-b", null), SettingCategories.PRIVACY, SettingOptions.defaults())
            .with(toggle("privacy-c", null), SettingCategories.PRIVACY, SettingOptions.defaults());
    }

    private static final Predicate<Registry.Entry<?>> PLAYER = entry -> entry.setting().permission() == null;

    private static List<String> ids(List<Shown> groups) {
        return groups.stream().map(Shown::id).toList();
    }

    @Test
    void groupsWithNothingVisibleAreLeftOut() {
        List<Shown> groups = SettingsGroups.groups(registry(), PLAYER, Map.of());
        assertEquals(List.of("chat", "sound", "privacy"), ids(groups), "built-in order; no staff group for players");
        assertEquals(List.of("chat", "sound", "privacy", "staff"), ids(SettingsGroups.groups(registry(), entry -> true, Map.of())));
        assertEquals(List.of("sound-b", "sound-a"), groups.get(1).entries().stream().map(Registry.Entry::id).toList(),
            "settings in their dialog order");
    }

    @Test
    void theServerCanMoveGroupsAndChangeTheirIcons() {
        Map<String, CategoryOverride> overrides = Map.of("privacy", new CategoryOverride(5, null), "sound", new CategoryOverride(null, "star"));
        assertEquals(List.of("privacy", "chat", "sound"), ids(SettingsGroups.groups(registry(), PLAYER, overrides)));
        assertEquals(5, SettingsGroups.order(SettingCategories.PRIVACY, overrides));
        assertEquals(SettingCategories.SOUND.order(), SettingsGroups.order(SettingCategories.SOUND, overrides), "no order: built-in");
        assertEquals("star", SettingsGroups.icon(SettingCategories.SOUND, overrides));
        assertEquals("privacy", SettingsGroups.icon(SettingCategories.PRIVACY, overrides), "no icon: built-in");
        // Equal orders keep the built-in order between them (a stable sort).
        Map<String, CategoryOverride> tie = Map.of("privacy", new CategoryOverride(SettingCategories.CHAT.order(), null));
        assertEquals(List.of("chat", "privacy", "sound"), ids(SettingsGroups.groups(registry(), PLAYER, tie)));
        // Every category (the API lists them all, also ones a player sees nothing in) in the same order.
        assertEquals(List.of("privacy", "chat", "sound", "staff"), SettingsGroups.ordered(registry().categories(), overrides).stream()
            .map(category -> category.id()).toList());
        assertEquals(List.of("chat", "sound", "privacy", "staff"), SettingsGroups.ordered(registry().categories(), Map.of()).stream()
            .map(category -> category.id()).toList());
    }

    @Test
    void findingGroupsAndPages() {
        List<Shown> groups = SettingsGroups.groups(registry(), PLAYER, Map.of());
        assertEquals("sound", SettingsGroups.find(groups, "SOUND").id());
        assertNull(SettingsGroups.find(groups, "staff"), "not visible");
        assertEquals("privacy", SettingsGroups.holding(groups, "privacy-b").id());
        assertNull(SettingsGroups.holding(groups, "spy"));
        List<Registry.Entry<?>> privacy = SettingsGroups.find(groups, "privacy").entries();
        assertEquals(1, SettingsGroups.pageOf(privacy, "privacy-a", 2));
        assertEquals(1, SettingsGroups.pageOf(privacy, "privacy-b", 2));
        assertEquals(2, SettingsGroups.pageOf(privacy, "privacy-c", 2));
        assertEquals(1, SettingsGroups.pageOf(privacy, "missing", 2));
        assertTrue(SettingsGroups.groups(Registry.EMPTY, PLAYER, Map.of()).isEmpty());
    }
}
