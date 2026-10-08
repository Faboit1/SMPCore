package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The live worth lookup other features use ({@link WorthLookup}): the generated table, the plain-item rule and rank
 * multipliers. Thread-safe: the table is an immutable snapshot swapped on reload, the prototype stacks it compares
 * against are only ever read, and permission checks only run on the player's own thread (other threads get the
 * multiplier last computed there).
 * <p>
 * Only plain items sell: a stack must look exactly like a fresh stack of its type. Renamed, enchanted (including
 * enchanted books), damaged, dyed, filled (shulker boxes, bundles) and custom-data items (SiftCore spawners) are
 * all refused, so nothing whose value depends on its data can be sold at a flat price.
 */
public final class WorthService implements WorthLookup, Pricing.Source {

    /** What selling one stack would do. */
    public enum Verdict {
        SELLABLE,
        /** Nothing there. */
        EMPTY,
        /** The item type has no price (or is worth less than a dollar). */
        NO_PRICE,
        /** The type has a price but this stack was renamed, enchanted, damaged or otherwise changed. */
        MODIFIED
    }

    /**
     * What one player gets per sell category right now: the rank multiplier plus each category's mastery bonus.
     *
     * @param rank    the rank multiplier
     * @param sold    base value sold per category (the mastery totals)
     * @param mastery the mastery rules
     */
    public record Rates(BigDecimal rank, Map<String, Long> sold, Mastery mastery) {

        public Rates {
            sold = Map.copyOf(sold);
        }

        public int level(String category) {
            return this.mastery.level(this.sold.getOrDefault(category, 0L));
        }

        public BigDecimal bonus(String category) {
            return this.mastery.bonus(level(category));
        }

        /** Rank plus the category's bonus, exactly. */
        public BigDecimal multiplier(String category) {
            return Mastery.multiplier(this.rank, bonus(category));
        }

        public long sold(String category) {
            return this.sold.getOrDefault(category, 0L);
        }
    }

    private final Setting<SellSettings> settings;
    private final AtomicReference<SellSettings> latest;
    private final Predicate<Player> onOwnThread;
    private final MasteryBook mastery;
    private final Map<Material, ItemStack> prototypes = new ConcurrentHashMap<>();
    private final Map<UUID, Double> multipliers = new ConcurrentHashMap<>();

    /**
     * @param settings    the applied sell settings
     * @param latest      the most recently parsed settings (written by the config parser)
     * @param onOwnThread true when the current thread owns the player (permission checks are only made there)
     * @param mastery     what online players sold per category
     */
    WorthService(Setting<SellSettings> settings, AtomicReference<SellSettings> latest, Predicate<Player> onOwnThread,
                 MasteryBook mastery) {
        this.settings = settings;
        this.latest = latest;
        this.onOwnThread = onOwnThread;
        this.mastery = mastery;
    }

    public static String key(Material material) {
        return material.getKey().asString();
    }

    public WorthTable table() {
        return this.settings.get().table();
    }

    /** What one item of this type sells for when plain, 0 when the type has no price. */
    public long price(Material material) {
        return table().price(key(material));
    }

    public Verdict verdict(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Verdict.EMPTY;
        }
        if (price(item.getType()) <= 0) {
            return Verdict.NO_PRICE;
        }
        return pristine(item) ? Verdict.SELLABLE : Verdict.MODIFIED;
    }

    /** True when the stack looks exactly like a fresh stack of its type (amount aside). */
    public boolean pristine(ItemStack item) {
        Material type = item.getType();
        if (!type.isItem() || type.isAir()) {
            return false;
        }
        return this.prototypes.computeIfAbsent(type, ItemStack::of).isSimilar(item);
    }

    /** The sell category of an item key, or null when it can't be sold. */
    public String category(String key) {
        return table().category(key);
    }

    /** The sell categories in effect. */
    public SellCategories categories() {
        return this.settings.get().categories();
    }

    /** A player's rates for every category. On the player's thread it reads their rank fresh. */
    public Rates rates(Player player) {
        return new Rates(BigDecimal.valueOf(multiplier(player)), this.mastery.totals(player.getUniqueId()),
            this.settings.get().mastery());
    }

    /** A player's rates from cached values only (any thread, also for placeholders). */
    public Rates cachedRates(UUID player) {
        return new Rates(BigDecimal.valueOf(cachedMultiplier(player)), this.mastery.totals(player),
            this.settings.get().mastery());
    }

    /**
     * What a player gets for one plain item of {@code key} with their rank and mastery, rounded down; 0 when the
     * server doesn't buy it.
     */
    public long unitPriceFor(Player player, String key) {
        WorthTable.Entry entry = table().entry(key);
        if (entry == null) {
            return 0;
        }
        return SaleMath.withMultiplier(entry.price(), rates(player).multiplier(entry.category()));
    }

    @Override
    public long unitPrice(ItemStack item) {
        return verdict(item) == Verdict.SELLABLE ? price(item.getType()) : 0L;
    }

    /**
     * The player's rank multiplier. On the player's own thread it reads their permissions (and remembers the
     * result); elsewhere it returns the value last read there.
     */
    @Override
    public double multiplier(Player player) {
        if (!this.onOwnThread.test(player)) {
            Double cached = this.multipliers.get(player.getUniqueId());
            if (cached != null) {
                return cached;
            }
        }
        return refresh(player);
    }

    /** Reads the player's multiplier from their permissions and remembers it. Call on the player's thread. */
    public double refresh(Player player) {
        double value = Multipliers.select(this.settings.get().multipliers(), tier -> granted(player, Multipliers.node(tier)));
        this.multipliers.put(player.getUniqueId(), value);
        return value;
    }

    /** The multiplier last read for a player, 1.0 when none was read. Safe from any thread (placeholders). */
    public double cachedMultiplier(UUID player) {
        return this.multipliers.getOrDefault(player, 1.0);
    }

    /** Drops a player's cached multiplier (on quit). */
    public void forget(UUID player) {
        this.multipliers.remove(player);
    }

    /**
     * Only explicitly granted tiers count (a group or user permission), so operators don't silently get the best
     * rate and tiers added by a reload are never granted by default.
     */
    private static boolean granted(Player player, String node) {
        return player.isPermissionSet(node) && player.hasPermission(node);
    }

    /** Every sellable item key per category, for tooltips and filters (a fresh map). */
    public Map<String, Integer> categorySizes() {
        return new HashMap<>(table().categorySizes());
    }

    @Override
    public long priceFor(Player player, ItemStack item) {
        long base = price(item);
        return base <= 0 ? 0 : SaleMath.withMultiplier(base, multiplier(player));
    }

    @Override
    public Pricing current() {
        return this.settings.get().pricing();
    }

    @Override
    public Pricing latest() {
        SellSettings parsed = this.latest.get();
        return (parsed == null ? this.settings.get() : parsed).pricing();
    }
}
