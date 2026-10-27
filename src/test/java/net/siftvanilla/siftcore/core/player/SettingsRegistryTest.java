package net.siftvanilla.siftcore.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.Test;

class SettingsRegistryTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    private static Toggle toggle(String id) {
        return new Toggle(id, true, LABEL, DESCRIPTION, null);
    }

    private static PlayerSettings settings() {
        return new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
    }

    @Test
    void categoriesSortByOrderAndSettingsByOrderThenRegistration() {
        PlayerSettings settings = settings();
        Toggle late = toggle("late");
        Toggle first = toggle("first");
        Toggle second = toggle("second");
        settings.register(SettingCategories.CHAT, late);
        settings.register(SettingCategories.SOUND, second, SettingOptions.<Boolean>builder().order(2).build());
        settings.register(SettingCategories.SOUND, first, SettingOptions.<Boolean>builder().order(1).build());
        Toggle loose = toggle("loose");
        settings.register(loose);
        Registry registry = settings.registry();
        assertEquals(List.of(SettingCategories.CHAT, SettingCategories.SOUND, SettingCategories.GENERAL), registry.categories(),
            "by category order, General last, empty categories left out");
        assertEquals(List.of("first", "second"), registry.in("sound").stream().map(Registry.Entry::id).toList());
        assertEquals(List.of("late", "first", "second", "loose"), registry.entries().stream().map(Registry.Entry::id).toList());
        assertEquals(SettingCategories.GENERAL, settings.category(loose), "registered without a category");
        assertEquals(4, settings.toggles().size());
        assertSame(first, settings.toggle("first"));
        assertNull(settings.toggle("missing"));
    }

    @Test
    void theOldToggleApiStillWorks() {
        PlayerSettings settings = settings();
        MessageKey otherLabel = MessageKey.ui("test.other");
        SettingCategory chat = new SettingCategory("chat", 20, LABEL, DESCRIPTION);
        settings.register(chat, toggle("mentions"));
        settings.register(new SettingCategory("chat", 20, LABEL, DESCRIPTION), toggle("other"));
        assertEquals(chat, settings.category(settings.toggle("other")), "an equal category may be shared");
        assertThrows(IllegalStateException.class, () -> settings.register(new SettingCategory("chat", 5, otherLabel, DESCRIPTION),
            toggle("clash")), "one id, one category");
        assertThrows(IllegalStateException.class, () -> settings.register(toggle("mentions")), "one id, one setting");
        assertThrows(IllegalArgumentException.class, () -> new SettingCategory("Bad Id", 1, LABEL, DESCRIPTION));
    }

    @Test
    void reservedCategoryIdsAndMissingTextAreRefused() {
        PlayerSettings settings = settings();
        for (String reserved : SettingCategory.RESERVED) {
            assertThrows(IllegalArgumentException.class, () -> settings.register(new SettingCategory(reserved, 1, LABEL, DESCRIPTION),
                toggle("in-" + reserved)), reserved);
        }
        assertThrows(IllegalArgumentException.class, () -> settings.register(new Toggle("nolabel", true, null, DESCRIPTION, null)));
    }

    @Test
    void shortNamesDropTheCategoryPrefixAndMayNotCollide() {
        PlayerSettings settings = settings();
        Registry.Entry<Boolean> volume = settings.register(SettingCategories.SOUND, toggle("sound-volume"));
        assertEquals("volume", volume.shortName());
        assertEquals("sound_volume", volume.inputKey());
        assertEquals("friends-tpa", settings.register(SettingCategories.TELEPORT, toggle("friends-tpa")).shortName(),
            "no prefix to drop");
        assertEquals("receipts", settings.register(SettingCategories.SOUND, toggle("sound_receipts")).shortName(), "underscore too");
        assertThrows(IllegalStateException.class, () -> settings.register(SettingCategories.SOUND, toggle("volume")),
            "an id equal to another's short name");
        assertThrows(IllegalStateException.class, () -> settings.register(SettingCategories.SOUND, toggle("sound_volume")),
            "the same short name");
        settings.register(SettingCategories.CHAT, toggle("volume"));
        assertEquals("volume", settings.registry().find("volume", null).id(), "ids win over short names");
        assertEquals("sound-volume", settings.registry().find("volume", "sound").id(), "short names inside the category");
        assertEquals("sound-volume", settings.registry().find("SOUND_VOLUME", null).id(), "input keys");
        assertNull(settings.registry().find("nothing", null));
        assertNull(settings.registry().find("volume", "privacy"));
    }

    @Test
    void inputKeysStayUniqueAndValid() {
        PlayerSettings settings = settings();
        settings.register(toggle("tpa-requests"));
        settings.register(toggle("tpa_requests"));
        settings.register(toggle("death-messages"));
        Registry registry = settings.registry();
        assertEquals("tpa_requests", registry.entry("tpa-requests").inputKey());
        assertEquals("tpa_requests_2", registry.entry("tpa_requests").inputKey());
        assertEquals("death_messages", registry.entry("death-messages").inputKey());
        settings.register(toggle("sell-x"));
        assertEquals("tpa_requests_2", settings.registry().entry("tpa_requests").inputKey(), "earlier keys never change");
    }

    @Test
    void optionRulesAndLegacyIdsAreChecked() {
        PlayerSettings settings = settings();
        Choice<AlertStyle> style = Choices.alert("style", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.OFF).text(LABEL, DESCRIPTION).build();
        assertThrows(IllegalArgumentException.class, () -> settings.register(SettingCategories.CHAT, style,
            SettingOptions.<AlertStyle>builder().optionAvailableWhen("title", () -> true).build()), "a rule for an unknown option");
        assertThrows(IllegalArgumentException.class, () -> settings.register(SettingCategories.CHAT, toggle("rules"),
            SettingOptions.<Boolean>builder().optionAvailableWhen("on", () -> true).build()), "option rules need options");
        settings.register(SettingCategories.CHAT, toggle("new-one"), SettingOptions.<Boolean>builder().legacy("old-one", v -> v).build());
        assertThrows(IllegalStateException.class, () -> settings.register(SettingCategories.CHAT, toggle("newer-one"),
            SettingOptions.<Boolean>builder().legacy("old-one", v -> v).build()), "one old id, one new setting");
        assertThrows(IllegalArgumentException.class, () -> settings.register(SettingCategories.CHAT, toggle("self"),
            SettingOptions.<Boolean>builder().legacy("self", v -> v).build()));
        assertEquals("new-one", settings.registry().legacyIds().get("old-one"));
    }

    @Test
    void aSettingIsSupersededWhileItsOldIdIsStillRegistered() {
        PlayerSettings settings = settings();
        settings.register(SettingCategories.TELEPORT, toggle("friends-auto"),
            SettingOptions.<Boolean>builder().legacy("tpa-old", v -> v).build());
        assertFalse(settings.registry().entry("friends-auto").superseded());
        assertTrue(settings.registry().entry("friends-auto").offered());
        settings.register(toggle("tpa-old"));
        assertTrue(settings.registry().entry("friends-auto").superseded(), "the old setting is still in charge");
        assertFalse(settings.registry().entry("friends-auto").offered(), "so the new one is not offered");
    }

    @Test
    void availabilityAndListingDecideWhatIsOffered() {
        PlayerSettings settings = settings();
        boolean[] on = {false};
        settings.register(SettingCategories.CHAT, toggle("depends"), SettingOptions.<Boolean>builder().availableWhen(() -> on[0]).build());
        settings.register(SettingCategories.CHAT, toggle("unlisted"), SettingOptions.<Boolean>builder().listed(false).build());
        settings.register(SettingCategories.CHAT, toggle("broken"), SettingOptions.<Boolean>builder().availableWhen(() -> {
            throw new IllegalStateException("boom");
        }).build());
        assertFalse(settings.registry().entry("depends").offered());
        on[0] = true;
        assertTrue(settings.registry().entry("depends").offered(), "read live");
        assertFalse(settings.registry().entry("unlisted").offered());
        assertFalse(settings.registry().entry("broken").offered(), "a failing check hides the setting");
        assertTrue(settings.visible(settings.registry().entry("depends"), node -> false));
        Toggle gated = new Toggle("gated", false, LABEL, DESCRIPTION, "test.node");
        settings.register(SettingCategories.STAFF, gated);
        assertFalse(settings.visible(settings.registry().entry("gated"), node -> false), "permission");
        assertTrue(settings.visible(settings.registry().entry("gated"), "test.node"::equals));
        assertFalse(settings.registry().entry("gated").placeholder(), "permission-gated settings stay out of placeholders");
        assertTrue(settings.registry().entry("depends").placeholder());
    }

    @Test
    void registrationPublishesNewSnapshotsSafelyFromManyThreads() throws Exception {
        PlayerSettings settings = settings();
        Registry before = settings.registry();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 64; i++) {
            int n = i;
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                settings.register(SettingCategories.CHAT, toggle("t" + n));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(64, settings.registry().byId().size());
        assertEquals(0, before.byId().size(), "an old snapshot never changes");
        assertNotSame(before, settings.registry());
    }
}
