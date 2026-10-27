package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The opening animation's timing and reel, and the glass colours of its frame. */
class AnimationPlanTest {

    @Test
    void theRollStartsFastSlowsDownAndLastsAboutAsLongAsAsked() {
        for (int length : new int[] {20, 40, 80, 120, 200}) {
            int[] delays = AnimationPlan.delays(length);
            assertEquals(1, delays[0], "the first steps are a tick apart");
            for (int i = 1; i < delays.length; i++) {
                assertTrue(delays[i] >= delays[i - 1], "never speeds up again (" + length + " ticks, step " + i + ")");
            }
            assertTrue(delays[delays.length - 1] <= AnimationPlan.SLOWEST, "the slowest step is at most " + AnimationPlan.SLOWEST);
            assertTrue(delays[delays.length - 1] >= 5, "it really slows down at the end: " + delays[delays.length - 1]);
            int total = 0;
            for (int delay : delays) {
                total += delay;
            }
            assertTrue(total >= length && total < length + AnimationPlan.SLOWEST, length + " ticks asked, " + total + " planned");
        }
        assertEquals(1, AnimationPlan.delays(0).length, "always at least one step");
    }

    @Test
    void theReelEndsOnTheRewardWonWithTheRarestRightAfterIt() {
        double[] weights = {40, 30, 20, 9, 1};
        SplittableRandom random = new SplittableRandom(3);
        for (int won = 0; won < weights.length; won++) {
            for (int round = 0; round < 50; round++) {
                AnimationPlan plan = AnimationPlan.of(80, weights, won, random);
                assertEquals(won, plan.pointed(plan.steps()), "the pointer ends on the reward won");
                assertEquals(plan.steps() + AnimationPlan.WINDOW, plan.reel().length);
                if (won != 4) {
                    assertEquals(4, plan.shown(plan.steps(), AnimationPlan.POINTER + 1), "the rarest reward just misses the pointer");
                }
                for (int index : plan.reel()) {
                    assertTrue(index >= 0 && index < weights.length, "only rewards that can be won roll by");
                }
            }
        }
    }

    @Test
    void likelyRewardsRollByMoreOften() {
        double[] weights = {90, 10};
        int[] seen = new int[2];
        SplittableRandom random = new SplittableRandom(11);
        for (int round = 0; round < 200; round++) {
            AnimationPlan plan = AnimationPlan.of(80, weights, 0, random);
            for (int i = 0; i < plan.steps(); i++) {
                seen[plan.reel()[i]]++;
            }
        }
        double share = seen[0] / (double) (seen[0] + seen[1]);
        assertTrue(share > 0.85 && share < 0.95, "the reel follows the chances: " + share);
    }

    @Test
    void progressAndShapeChecks() {
        AnimationPlan plan = AnimationPlan.of(40, new double[] {1, 1}, 1, new SplittableRandom(1));
        assertEquals(0.0, plan.progress(0));
        assertEquals(1.0, plan.progress(plan.steps()));
        assertThrows(IllegalArgumentException.class, () -> new AnimationPlan(new int[] {1, 1}, new int[] {0, 0}),
            "a reel must cover every step and the last window");
    }

    @Test
    void framesTakeTheRaritysGlassOrTheNearestPane() {
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Panes.nearest(TextColor.color(0xC8C8C8)));
        assertEquals(Material.LIME_STAINED_GLASS_PANE, Panes.nearest(TextColor.color(0x5BE36B)));
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Panes.nearest(TextColor.color(0x4DA6FF)));
        assertEquals(Material.MAGENTA_STAINED_GLASS_PANE, Panes.nearest(TextColor.color(0xC65BFF)));
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, Panes.nearest(TextColor.color(0x000000)));
        assertEquals(0.0, Panes.distance(0x123456, 0x123456));
        Rarity mythic = new Rarity("mythic", "Mythic", true, true, TextColor.color(0xFF4D6A), "minecraft:red_stained_glass_pane");
        assertEquals(Material.RED_STAINED_GLASS_PANE, Panes.of(mythic), "the rarity's own glass wins");
        Rarity plain = new Rarity("rare", "Rare", false, false, TextColor.color(0x4DA6FF));
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Panes.of(plain), "else the nearest pane");
    }
}
