package net.siftvanilla.siftcore.feature.sell;

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

    private final Setting<SellSettings> settings;
    private final AtomicReference<SellSettings> latest;
    private final Predicate<Player> onOwnThread;
    private final Map<Material, ItemStack> prototypes = new ConcurrentHashMap<>();
    private final Map<UUID, Double> multipliers = new ConcurrentHashMap<>();

    /**
     * @param settings    the applied sell settings
     * @param latest      the most recently parsed settings (written by the config parser)
     * @param onOwnThread true when the current thread owns the player (permission checks are only made there)
     */
    public WorthService(Setting<SellSettings> settings, AtomicReference<SellSettings> latest, Predicate<Player> onOwnThread) {
        this.settings = settings;
        this.latest = latest;
        this.onOwnThread = onOwnThread;
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
        if (!type.isItem()) {
            return false;
        }
        return this.prototypes.computeIfAbsent(type, ItemStack::of).isSimilar(item);
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
