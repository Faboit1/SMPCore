package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.OpenClick;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.PickupStorage;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.StackClick;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.TeamAction;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.TeamNotices;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings.XpMending;
import org.junit.jupiter.api.Test;

/** The spawner settings: their group and order, the sale receipt reader, the deciders and the give confirmation. */
class SpawnerPlayerSettingsTest {

    private static PlayerSettings registered() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        SpawnerPlayerSettings.register(settings);
        return settings;
    }

    @Test
    void settingsSitInSpawnersInTheCatalogOrder() {
        PlayerSettings settings = registered();
        List<String> ids = settings.registry().in(SettingCategories.SPAWNERS.id()).stream().map(Registry.Entry::id).toList();
        assertEquals(List.of("spawner-open-click", "spawner-full-alert", "spawner-stack-click", "spawner-xp-mending",
            "spawner-pickup-storage", "spawner-team-notices", "spawner-confirm-give"), ids);
        for (Registry.Entry<?> entry : settings.registry().in(SettingCategories.SPAWNERS.id())) {
            assertTrue(entry.offered(), entry.id() + " is offered");
            assertEquals(null, entry.setting().permission(), entry.id() + " is for everyone");
            if (entry.setting() instanceof Choice<?> choice) {
                assertTrue(choice.options().size() <= Choice.MAX_OPTIONS, entry.id());
            }
        }
        assertTrue(settings.hasReader(SharedSettings.SELL_RECEIPTS), "selling a storage follows the sale receipts");
        assertTrue(settings.registry().entry(SharedSettings.SELL_RECEIPTS.id()).offered());
    }

    @Test
    void defaultsAndOptionsMatchTheCatalog() {
        assertEquals(List.of("server", "sneak-right-click", "right-click"), SpawnerPlayerSettings.OPEN_CLICK.optionIds());
        assertEquals(OpenClick.SERVER, SpawnerPlayerSettings.OPEN_CLICK.defaultValue());
        assertEquals(List.of("chat", "actionbar", "off"), SpawnerPlayerSettings.FULL_ALERT.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, SpawnerPlayerSettings.FULL_ALERT.defaultValue());
        assertEquals(List.of("one", "whole-hand"), SpawnerPlayerSettings.STACK_CLICK.optionIds());
        assertEquals(StackClick.ONE, SpawnerPlayerSettings.STACK_CLICK.defaultValue());
        assertEquals(List.of("server", "repair-first", "levels-only"), SpawnerPlayerSettings.XP_MENDING.optionIds());
        assertEquals(List.of("server", "claim-box", "sell"), SpawnerPlayerSettings.PICKUP_STORAGE.optionIds());
        assertEquals(List.of("pickups", "all", "off"), SpawnerPlayerSettings.TEAM_NOTICES.optionIds());
        assertEquals(TeamNotices.PICKUPS, SpawnerPlayerSettings.TEAM_NOTICES.defaultValue());
        assertTrue(SpawnerPlayerSettings.CONFIRM_GIVE.defaultOn());
    }

    @Test
    void openingFollowsThePlayerOrTheServer() {
        assertTrue(SpawnerPlayerSettings.requiresSneak(OpenClick.SERVER, true));
        assertFalse(SpawnerPlayerSettings.requiresSneak(OpenClick.SERVER, false));
        assertTrue(SpawnerPlayerSettings.requiresSneak(OpenClick.SNEAK_RIGHT_CLICK, false));
        assertFalse(SpawnerPlayerSettings.requiresSneak(OpenClick.RIGHT_CLICK, true));
    }

    @Test
    void stackingAddsOneOrTheWholeHandAndSneakingSwaps() {
        assertFalse(SpawnerPlayerSettings.wholeStack(StackClick.ONE, false));
        assertTrue(SpawnerPlayerSettings.wholeStack(StackClick.ONE, true));
        assertTrue(SpawnerPlayerSettings.wholeStack(StackClick.WHOLE_HAND, false));
        assertFalse(SpawnerPlayerSettings.wholeStack(StackClick.WHOLE_HAND, true));
    }

    @Test
    void xpRepairsMendingGearAsChosen() {
        assertTrue(SpawnerPlayerSettings.mending(XpMending.SERVER, true));
        assertFalse(SpawnerPlayerSettings.mending(XpMending.SERVER, false));
        assertTrue(SpawnerPlayerSettings.mending(XpMending.REPAIR_FIRST, false));
        assertFalse(SpawnerPlayerSettings.mending(XpMending.LEVELS_ONLY, true));
    }

    @Test
    void onlyTheOwnersPickupFollowsTheirStorageChoice() {
        SpawnersSettings.BreakStorage box = SpawnersSettings.BreakStorage.CLAIM_BOX;
        SpawnersSettings.BreakStorage sell = SpawnersSettings.BreakStorage.SELL;
        assertEquals(box, SpawnerPlayerSettings.breakStorage(PickupStorage.SERVER, box, true));
        assertEquals(sell, SpawnerPlayerSettings.breakStorage(PickupStorage.SELL, box, true));
        assertEquals(box, SpawnerPlayerSettings.breakStorage(PickupStorage.CLAIM_BOX, sell, true));
        assertEquals(box, SpawnerPlayerSettings.breakStorage(PickupStorage.SELL, box, false), "a teammate follows the server");
        assertEquals(sell, SpawnerPlayerSettings.breakStorage(PickupStorage.CLAIM_BOX, sell, false));
    }

    @Test
    void teamNoticesTellPickupsEverythingOrNothing() {
        for (TeamAction action : TeamAction.values()) {
            assertEquals(action == TeamAction.PICKUP, SpawnerPlayerSettings.notifies(TeamNotices.PICKUPS, action), action.name());
            assertTrue(SpawnerPlayerSettings.notifies(TeamNotices.ALL, action), action.name());
            assertFalse(SpawnerPlayerSettings.notifies(TeamNotices.OFF, action), action.name());
        }
    }

    @Test
    void givingAwayNeedsASeparateSecondClick() {
        AtomicLong now = new AtomicLong(1_000_000);
        GiveConfirms confirms = new GiveConfirms(now::get);
        UUID player = new UUID(1, 1);
        assertEquals(GiveConfirms.Step.ASK, confirms.click(player, 7));
        now.addAndGet(200);
        assertEquals(GiveConfirms.Step.WAIT, confirms.click(player, 7), "holding the key repeats clicks: no give");
        now.addAndGet(200);
        assertEquals(GiveConfirms.Step.WAIT, confirms.click(player, 7), "still holding");
        now.addAndGet(800);
        assertEquals(GiveConfirms.Step.GIVE, confirms.click(player, 7), "released and clicked again");
        assertEquals(0, confirms.size(), "a give uses the confirmation up");
        assertEquals(GiveConfirms.Step.ASK, confirms.click(player, 7), "the next give asks again");

        now.addAndGet(GiveConfirms.WINDOW.toMillis() + 1);
        assertEquals(GiveConfirms.Step.ASK, confirms.click(player, 7), "too late: asks again");
        now.addAndGet(1_000);
        assertEquals(GiveConfirms.Step.ASK, confirms.click(player, 8), "another spawner asks on its own");
        now.addAndGet(1_000);
        assertEquals(GiveConfirms.Step.GIVE, confirms.click(player, 8));

        UUID other = new UUID(1, 2);
        assertEquals(GiveConfirms.Step.ASK, confirms.click(other, 9));
        confirms.forget(other);
        assertEquals(0, confirms.size(), "forgotten when they leave");
    }
}
