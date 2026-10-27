package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Show my rank: where it sits, when it is offered, and how the shared rank labels hide a hidden rank. */
class ShowMyRankTest {

    private static final UUID TYCOON = new UUID(1, 1);
    private static final UUID HIDER = new UUID(2, 2);

    /** LuckPerms as the rank integration sees it: everyone is a Tycoon. */
    private static final Ranks LUCKPERMS = new Ranks() {
        @Override
        public String label(UUID player) {
            return "Tycoon";
        }

        @Override
        public Component component(Player player) {
            return Component.text("Tycoon");
        }

        @Override
        public String group(UUID player) {
            return "tycoon";
        }
    };

    @Test
    void theSwitchIsAPrivacySettingForRankGroups() {
        AtomicBoolean connected = new AtomicBoolean(true);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        IntegrationsFeature.registerSettings(settings, connected::get);
        Registry.Entry<?> entry = settings.registry().entry("show-my-rank");
        assertEquals(SettingCategories.PRIVACY, entry.category());
        assertEquals(5, entry.options().order(), "after hide-coordinates, the two privacy choices and the big-order switch");
        assertEquals(SharedSettings.HIDE_RANK_NODE, entry.setting().permission(), "only for players given siftcore.settings.hide-rank");
        assertEquals(Boolean.FALSE, entry.options().placeholder(), "a privacy setting is never a placeholder");
        assertEquals(SettingOptions.Apply.NEXT_USE, entry.options().apply(), "every reader looks it up each time");
        assertTrue(IntegrationsFeature.SHOW_MY_RANK.defaultOn());
        assertTrue(entry.offered());
        assertTrue(settings.visible(entry, Set.of(SharedSettings.HIDE_RANK_NODE)::contains));
        assertFalse(settings.visible(entry, permission -> false), "players without the node never see it");

        connected.set(false);
        assertFalse(entry.offered(), "without LuckPerms there are no rank labels to hide");
        assertEquals(List.of(), settings.registry().in(SettingCategories.PRIVACY.id()).stream().filter(Registry.Entry::offered).toList());
    }

    @Test
    void everyRankPlaceholderSaysItIsEmptyWithTheSwitchOff() {
        assertEquals(Set.of("rank", "rank_group", "rank_color"), IntegrationsFeature.RANK_PLACEHOLDERS.keySet());
        IntegrationsFeature.RANK_PLACEHOLDERS.forEach((name, description) ->
            assertTrue(description.contains("with Show my rank off"),
                "the generated placeholder docs of " + name + " name Show my rank: " + description));
    }

    @Test
    void aHiddenRankHasNoLabelButKeepsItsGroup() {
        Set<UUID> hiding = ConcurrentHashMap.newKeySet();
        hiding.add(HIDER);
        SwitchableRanks ranks = new SwitchableRanks(id -> !hiding.contains(id));
        assertEquals("", ranks.label(TYCOON), "nothing before LuckPerms is connected");
        assertFalse(ranks.connected());

        ranks.use(LUCKPERMS);
        assertTrue(ranks.connected());
        assertEquals("Tycoon", ranks.label(TYCOON));
        assertEquals("", ranks.label(HIDER), "chat, cards, join lines and %siftcore_rank% show no rank");
        assertEquals("tycoon", ranks.group(HIDER), "code still sees the real group (perks, limits, rank order)");
        assertEquals("default", ranks.shownGroup(HIDER), "%siftcore_rank_group% reads as an ordinary member");
        assertEquals("tycoon", ranks.shownGroup(TYCOON));
        assertTrue(ranks.shown(TYCOON) && !ranks.shown(HIDER));
        assertTrue(ranks.shown(null), "no player, nothing to hide");

        hiding.remove(HIDER);
        assertEquals("Tycoon", ranks.label(HIDER), "turning it back on shows the rank at once");

        ranks.use(null);
        assertEquals("", ranks.label(TYCOON));
        assertEquals("default", ranks.group(TYCOON));
    }
}
