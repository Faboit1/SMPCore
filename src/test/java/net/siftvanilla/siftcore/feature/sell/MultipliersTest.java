package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MultipliersTest {

    private static final Map<String, Double> TIERS = new TreeMap<>(Map.of("vip", 1.1, "mvp", 1.25, "elite", 1.5));

    @Test
    void noTierMeansOne() {
        assertEquals(1.0, Multipliers.select(TIERS, tier -> false));
        assertEquals(1.0, Multipliers.select(Map.of(), tier -> true));
    }

    @Test
    void highestGrantedTierWins() {
        assertEquals(1.25, Multipliers.select(TIERS, Set.of("vip", "mvp")::contains));
        assertEquals(1.5, Multipliers.select(TIERS, Set.of("vip", "elite")::contains));
        assertEquals(1.1, Multipliers.select(TIERS, Set.of("vip")::contains));
    }

    @Test
    void tiersAreTriedFromTheBestDown() {
        List<String> checked = new ArrayList<>();
        double result = Multipliers.select(Map.of("vip", 1.1, "mvp", 1.25, "elite", 1.5), tier -> {
            checked.add(tier);
            return tier.equals("mvp");
        });
        assertEquals(1.25, result);
        // vip can't beat mvp, so its permission is never read
        assertEquals(List.of("elite", "mvp"), checked);
    }

    @Test
    void tiersAtOrBelowOneNeverApply() {
        assertEquals(1.0, Multipliers.select(Map.of("none", 1.0), tier -> true));
    }

    @Test
    void highestIsTheBestConfiguredTierOrOne() {
        assertEquals(1.5, Multipliers.highest(TIERS));
        assertEquals(1.0, Multipliers.highest(Map.of()));
    }

    @Test
    void nodesAndFormatting() {
        assertEquals("siftcore.sell.multiplier.vip", Multipliers.node("vip"));
        assertEquals("1.5", Multipliers.format(1.5));
        assertEquals("1.25", Multipliers.format(1.25));
        assertEquals("2", Multipliers.format(2.0));
        assertEquals("1", Multipliers.format(Double.NaN));
        assertFalse(Multipliers.format(1.1).contains("000"));
    }
}
