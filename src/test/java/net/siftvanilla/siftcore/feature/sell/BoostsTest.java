package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Server sell boosters in prices: exactly their percent on top of the player's own multiplier, never stacked. */
class BoostsTest {

    private static final Mastery RULES = new Mastery(true, List.of(50_000L, 250_000L, 1_000_000L, 5_000_000L, 25_000_000L),
        new BigDecimal("0.05"));

    private static void same(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + ", got " + actual);
    }

    @Test
    void theFactorIsExact() {
        same("1.10", Boosts.factor(10));
        same("1", Boosts.factor(0));
        same("1.5", Boosts.factor(50));
        same("1.375", Boosts.apply(new BigDecimal("1.25"), 10));
        BigDecimal untouched = new BigDecimal("1.25");
        assertSame(untouched, Boosts.apply(untouched, 0), "no booster, no change");
        same("1.25", Boosts.remove(new BigDecimal("1.375"), 10));
        same("1.05", Boosts.remove(Boosts.apply(new BigDecimal("1.05"), 15), 15));
        assertThrows(IllegalArgumentException.class, () -> Boosts.factor(-1));
        assertEquals(1.5625, Boosts.guard(1.25, 25), 1e-12);
        assertEquals(1.25, Boosts.guard(1.25, 0), 1e-12);
    }

    @Test
    void aSalePaysExactlyThePercentMore() {
        WorthService.Rates plain = new WorthService.Rates(BigDecimal.ONE, Map.of(), RULES);
        WorthService.Rates boosted = new WorthService.Rates(BigDecimal.ONE, Map.of(), RULES, 10);
        SalePlan plan = SalePlan.of(List.of(new SalePlan.Line("minecraft:diamond", 64, 400, "mining")));
        long without = plan.total(plain::multiplier);
        long with = plan.total(boosted::multiplier);
        assertEquals(25_600, without);
        assertEquals(28_160, with, "64 diamonds at $400 pay $25,600, plus 10% is $28,160");
        assertEquals(without + without / 10, with);
        // Rounded down once per category, like every sale.
        SalePlan odd = SalePlan.of(List.of(new SalePlan.Line("minecraft:dirt", 7, 3, "other")));
        assertEquals(23, odd.total(new WorthService.Rates(BigDecimal.ONE, Map.of(), RULES, 10)::multiplier), "21 x 1.1 = 23.1 pays 23");
    }

    @Test
    void theBoosterComesOnTopOfRankAndMastery() {
        WorthService.Rates rates = new WorthService.Rates(new BigDecimal("1.2"), Map.of("mining", 300_000L), RULES, 10);
        same("1.3", rates.own("mining"));
        same("1.43", rates.multiplier("mining"));
        same("1.2", rates.own("farming"));
        same("1.32", rates.multiplier("farming"));
        // Spawner storage: the rank with the booster on top, no mastery.
        same("1.32", rates.flat());
        assertEquals(10, rates.boost());
        assertEquals(572, SaleMath.withMultiplier(400, rates.multiplier("mining")));
        assertThrows(IllegalArgumentException.class, () -> new WorthService.Rates(BigDecimal.ONE, Map.of(), RULES, -5));
    }

    @Test
    void theShopGuardIncludesTheLargestBooster() {
        Pricing pricing = new Pricing(WorthTable.EMPTY, 1.25, List.of());
        assertEquals(0, pricing.highestBoost());
        assertEquals(1.25, pricing.guardMultiplier(), 1e-12);
        Pricing boosted = pricing.withBoost(25);
        assertEquals(25, boosted.highestBoost());
        assertEquals(1.5625, boosted.guardMultiplier(), 1e-12);
        assertEquals(1.25, boosted.highestMultiplier(), 1e-12, "the own best multiplier is unchanged");
        assertSame(boosted, boosted.withBoost(25));
        assertThrows(IllegalArgumentException.class, () -> pricing.withBoost(-1));
    }
}
