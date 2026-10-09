package net.siftvanilla.siftcore.feature.shards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.text.Arg;
import org.junit.jupiter.api.Test;

/** The shard shop settings: group and order, the confirmation thresholds and how they combine with the server's rule. */
class ShardSettingsTest {

    @Test
    void settingsFollowTheAfkSettingsInTheirGroup() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        ShardsFeature.registerSettings(settings);
        List<Registry.Entry<?>> group = settings.registry().in(SettingCategories.AFK.id());
        assertEquals(List.of("shard-confirm-above", "shard-shop-stay-open"), group.stream().map(Registry.Entry::id).toList());
        assertEquals(6, group.get(0).options().order(), "after the five AFK settings");
        assertEquals(7, group.get(1).options().order());
        assertTrue(group.stream().allMatch(Registry.Entry::offered));
        UUID player = new UUID(5, 5);
        assertEquals(ConfirmAbove.SERVER, settings.get(player, ShardsFeature.CONFIRM_ABOVE));
        assertFalse(settings.get(player, ShardsFeature.STAY_OPEN), "the shop closes after a purchase by default");
    }

    @Test
    void thresholdsUseTheSharedVocabulary() {
        Choice<ConfirmAbove> choice = ShardsFeature.CONFIRM_ABOVE;
        assertEquals(List.of("server", "always", "100", "1000", "5000", "never"), choice.optionIds());
        assertEquals(ConfirmAbove.SERVER, choice.defaultValue());
        assertEquals(1_000, choice.decodeOrNull("1000").amount());
        assertNull(choice.decodeOrNull("1k"), "only the listed presets are options");
        Choice.Option<ConfirmAbove> preset = choice.option("5000");
        assertEquals(List.of(Arg.number("amount", 5_000)), preset.args(), "shards are counted, not shown as money");
    }

    @Test
    void theChoiceDecidesAndServerDefaultFollowsTheConfig() {
        long server = 500;
        assertFalse(ShardMath.needsConfirmation(499, ConfirmAbove.SERVER, server));
        assertTrue(ShardMath.needsConfirmation(500, ConfirmAbove.SERVER, server), "shop.confirm-above: 500 asks from 500");
        assertTrue(ShardMath.needsConfirmation(1, ConfirmAbove.SERVER, 0), "a server rule of 0 always asks");
        assertTrue(ShardMath.needsConfirmation(1, ConfirmAbove.ALWAYS, server));
        assertFalse(ShardMath.needsConfirmation(10_000_000, ConfirmAbove.NEVER, server));
        ConfirmAbove thousand = ShardsFeature.CONFIRM_ABOVE.decodeOrNull("1000");
        assertFalse(ShardMath.needsConfirmation(999, thousand, server), "a player's own threshold replaces the server's");
        assertTrue(ShardMath.needsConfirmation(1_000, thousand, server));
        ConfirmAbove hundred = ShardsFeature.CONFIRM_ABOVE.decodeOrNull("100");
        assertTrue(ShardMath.needsConfirmation(100, hundred, server), "lower than the server's rule asks sooner");
        assertFalse(ShardMath.needsConfirmation(99, hundred, server));
    }
}
