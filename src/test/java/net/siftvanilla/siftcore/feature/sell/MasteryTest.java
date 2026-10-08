package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MasteryTest {

    private static final Mastery RULES = Mastery.DEFAULT;

    private static Mastery parse(String yaml, List<ConfigProblem> problems) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ConfigReader reader = new ConfigReader("features/sell.yml", config);
        Mastery mastery = Mastery.parse(reader, MoneyFormat.defaults());
        problems.addAll(reader.problems());
        return mastery;
    }

    @Test
    void levelsAreReachedAtTheirThresholds() {
        assertEquals(0, RULES.level(0));
        assertEquals(0, RULES.level(49_999));
        assertEquals(1, RULES.level(50_000));
        assertEquals(2, RULES.level(250_000));
        assertEquals(4, RULES.level(24_999_999));
        assertEquals(5, RULES.level(25_000_000));
        assertEquals(5, RULES.level(Long.MAX_VALUE));
        assertEquals(5, RULES.maxLevel());
        assertEquals(1_000_000, RULES.threshold(3));
        assertEquals(-1, RULES.threshold(6));
        assertEquals(-1, RULES.threshold(0));
    }

    @Test
    void theBonusAddsToTheRankMultiplier() {
        assertEquals(0, new BigDecimal("0.15").compareTo(RULES.bonus(3)));
        assertEquals(0, new BigDecimal("0.25").compareTo(RULES.maxBonus()));
        // legend 1.5 + max mastery 0.25 = 1.75, exactly
        assertEquals(0, new BigDecimal("1.75").compareTo(Mastery.multiplier(new BigDecimal("1.5"), RULES.maxBonus())));
        assertEquals(0, BigDecimal.ZERO.compareTo(RULES.bonus(0)));
        assertEquals(0, BigDecimal.ZERO.compareTo(Mastery.OFF.bonus(5)));
        assertEquals(0, Mastery.OFF.level(Long.MAX_VALUE));
    }

    @Test
    void creditCountsBaseWorthAndCapsWhatOrdersPaid() {
        // 64 to the server at $400: 25,600 counts, whatever the multiplier
        assertEquals(25_600, Mastery.credit(64, 400, 0, 0));
        // 10 to an order that paid $9,000 after tax: capped at their base worth of $4,000
        assertEquals(25_600 + 4_000, Mastery.credit(64, 400, 10, 9_000));
        // an order that paid less than the worth counts what it paid
        assertEquals(1_000, Mastery.credit(0, 400, 10, 1_000));
        // items the server doesn't buy add nothing, even when an order paid a lot for them
        assertEquals(0, Mastery.credit(0, 0, 100, 1_000_000));
        assertThrows(ArithmeticException.class, () -> Mastery.credit(Long.MAX_VALUE, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Mastery.credit(-1, 400, 0, 0));
    }

    @Test
    void parsesTheSectionAndFallsBackToDefaults() {
        List<ConfigProblem> problems = new ArrayList<>();
        Mastery parsed = parse("""
            mastery:
              enabled: true
              levels: [10k, 20k, 1m]
              step: 0.1
            """, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of(10_000L, 20_000L, 1_000_000L), parsed.levels());
        assertEquals(3, parsed.maxLevel());
        assertEquals(0, new BigDecimal("0.3").compareTo(parsed.maxBonus()));

        assertEquals(Mastery.DEFAULT, parse("other: 1\n", problems));
        assertTrue(problems.isEmpty());

        Mastery off = parse("mastery:\n  enabled: false\n", problems);
        assertFalse(off.enabled());
        assertEquals(0, off.maxLevel());
        assertEquals(0, BigDecimal.ZERO.compareTo(off.maxBonus()));
    }

    @Test
    void levelsMustGoUp() {
        List<ConfigProblem> problems = new ArrayList<>();
        assertEquals(Mastery.DEFAULT, parse("mastery:\n  levels: [50k, 50k]\n", problems));
        assertEquals(1, problems.size(), problems.toString());
        problems.clear();
        assertEquals(Mastery.DEFAULT, parse("mastery:\n  levels: [50k, lots]\n", problems));
        assertEquals(1, problems.size(), problems.toString());
        assertThrows(IllegalArgumentException.class, () -> new Mastery(true, List.of(5L, 4L), BigDecimal.ONE));
    }

    @Test
    void ratesCombineRankAndEachCategorysLevel() {
        WorthService.Rates rates = new WorthService.Rates(new BigDecimal("1.2"), Map.of("mining", 300_000L, "farming", 1L), RULES);
        assertEquals(2, rates.level("mining"));
        assertEquals(0, new BigDecimal("1.3").compareTo(rates.multiplier("mining")));
        assertEquals(0, new BigDecimal("1.2").compareTo(rates.multiplier("farming")));
        assertEquals(0, new BigDecimal("1.2").compareTo(rates.multiplier("unknown")));
    }
}
