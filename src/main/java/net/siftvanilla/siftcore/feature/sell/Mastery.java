package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Sell mastery: selling items of a category to the server raises that category's level, and each level adds
 * {@code step} to the player's multiplier for the category. Pure logic.
 * <p>
 * Levels count the base value sold (worth before any multiplier), so bonuses never speed up their own progress.
 * The multiplier of a sale line is the rank multiplier plus the category bonus (legend 1.5 + 0.25 = 1.75).
 *
 * @param enabled whether mastery counts and pays at all
 * @param levels  base value sold needed for each level, strictly increasing ({@code levels[0]} reaches level 1)
 * @param step    bonus per level
 */
public record Mastery(boolean enabled, List<Long> levels, BigDecimal step) {

    /** The defaults of {@code features/sell.yml}: five levels of +0.05 each. */
    public static final Mastery DEFAULT = new Mastery(true,
        List.of(50_000L, 250_000L, 1_000_000L, 5_000_000L, 25_000_000L), new BigDecimal("0.05"));
    /** Mastery switched off: no levels, no bonus. */
    public static final Mastery OFF = new Mastery(false, List.of(), BigDecimal.ZERO);

    /** The most levels a configuration may have. */
    public static final int MAX_LEVELS = 10;

    public Mastery {
        levels = List.copyOf(levels);
        long previous = 0;
        for (long threshold : levels) {
            if (threshold <= previous) {
                throw new IllegalArgumentException("Mastery levels must be positive and increasing");
            }
            previous = threshold;
        }
        if (levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException("At most " + MAX_LEVELS + " mastery levels");
        }
        if (step.signum() < 0 || step.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("The mastery step is 0 to 1");
        }
    }

    /** The highest level (0 when mastery is off). */
    public int maxLevel() {
        return this.enabled ? this.levels.size() : 0;
    }

    /** The level reached with {@code sold} base value sold in a category. */
    public int level(long sold) {
        if (!this.enabled) {
            return 0;
        }
        int level = 0;
        for (long threshold : this.levels) {
            if (sold >= threshold) {
                level++;
            } else {
                break;
            }
        }
        return level;
    }

    /** The bonus a level adds to the multiplier (0 when mastery is off). */
    public BigDecimal bonus(int level) {
        if (!this.enabled || level <= 0) {
            return BigDecimal.ZERO;
        }
        return this.step.multiply(BigDecimal.valueOf(Math.min(level, this.levels.size())));
    }

    /** The bonus at the highest level. */
    public BigDecimal maxBonus() {
        return bonus(maxLevel());
    }

    /** Base value sold needed to reach {@code level} (1 to max), or -1 past the last level. */
    public long threshold(int level) {
        if (level < 1 || level > maxLevel()) {
            return -1;
        }
        return this.levels.get(level - 1);
    }

    /** A line's multiplier: the rank multiplier plus the category bonus, exactly. */
    public static BigDecimal multiplier(BigDecimal rank, BigDecimal bonus) {
        return rank.add(bonus);
    }

    /**
     * What a sale adds to a category's mastery: the base worth of the units the server bought, plus for units sent
     * to buy orders the smaller of their base worth and what the orders paid after tax (so an overpriced order
     * between alts can't pump mastery). Units the server doesn't buy ({@code worth} 0) add nothing.
     *
     * @throws ArithmeticException on overflow
     */
    public static long credit(long serverUnits, long worth, long routedUnits, long routedNet) {
        if (serverUnits < 0 || worth < 0 || routedUnits < 0 || routedNet < 0) {
            throw new IllegalArgumentException("Credits are never negative");
        }
        if (worth == 0) {
            return 0;
        }
        long server = Math.multiplyExact(serverUnits, worth);
        long routed = Math.min(Math.multiplyExact(routedUnits, worth), routedNet);
        return Math.addExact(server, routed);
    }

    /** Reads the {@code mastery} section; a missing section means the defaults. */
    public static Mastery parse(ConfigReader parent, MoneyFormat money) {
        if (!parent.has("mastery")) {
            return DEFAULT;
        }
        ConfigReader r = parent.section("mastery");
        boolean enabled = r.has("enabled") ? r.bool("enabled", true) : true;
        List<Long> levels = new ArrayList<>();
        if (r.has("levels")) {
            List<String> raw = r.stringList("levels", List.of());
            long previous = 0;
            for (String text : raw) {
                MoneyFormat.ParseResult parsed = money.parse(text, false);
                if (!parsed.ok()) {
                    r.problem("levels", "contains '" + text + "'; use amounts like 50k or 1m");
                    return DEFAULT;
                }
                if (parsed.amount() <= previous) {
                    r.problem("levels", "must go up from level to level ('" + text + "' is not more than the level before)");
                    return DEFAULT;
                }
                previous = parsed.amount();
                levels.add(parsed.amount());
            }
            if (levels.size() > MAX_LEVELS) {
                r.problem("levels", "has " + levels.size() + " levels; at most " + MAX_LEVELS);
                return DEFAULT;
            }
            if (levels.isEmpty() && enabled) {
                r.problem("levels", "is empty; list at least one level or set enabled: false");
                return DEFAULT;
            }
        } else {
            levels.addAll(DEFAULT.levels());
        }
        BigDecimal step = r.has("step") ? BigDecimal.valueOf(r.decimal("step", 0.0, 1.0, 0.05)) : DEFAULT.step();
        return new Mastery(enabled, levels, step.stripTrailingZeros());
    }
}
