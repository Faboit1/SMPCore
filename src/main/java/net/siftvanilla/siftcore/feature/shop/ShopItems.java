package net.siftvanilla.siftcore.feature.shop;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.sell.ItemKeys;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

/**
 * What shop entries turn into: a plain stack of an item, or a spawner item made by the spawners feature
 * ({@link SpawnerItems}). Spawner entries are only offered while that feature knows the mob, so the shop never
 * shows something it can't hand out.
 */
final class ShopItems {

    private final SpawnerItems spawners;
    private final Lang lang;

    ShopItems(SpawnerItems spawners, Lang lang) {
        this.spawners = spawners;
        this.lang = lang;
    }

    /** The item and mob keys of this Minecraft version, for validating {@code features/shop.yml}. */
    static ShopSettings.Catalog catalog() {
        Set<String> items = new HashSet<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && !material.isAir() && material.isItem()) {
                items.add(material.getKey().asString());
            }
        }
        Set<String> mobs = new HashSet<>();
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.UNKNOWN && type.isAlive() && type.isSpawnable()) {
                mobs.add(type.getKey().asString());
            }
        }
        return new ShopSettings.Catalog(items, mobs);
    }

    /** The mob id the spawners feature uses ({@code zombie}, not {@code minecraft:zombie}). */
    static String mobId(ShopSettings.Entry entry) {
        return ItemKeys.shortKey(entry.mob());
    }

    /** Whether the entry can be bought right now. */
    boolean available(ShopSettings.Entry entry) {
        return !entry.spawner() || this.spawners.mobs().contains(mobId(entry));
    }

    /** One unit of what the entry sells, or empty when it can't be made right now. */
    Optional<ItemStack> unit(ShopSettings.Entry entry) {
        if (entry.spawner()) {
            if (!available(entry)) {
                return Optional.empty();
            }
            return this.spawners.create(mobId(entry), 1).filter(stack -> !stack.isEmpty()).map(stack -> stack.asQuantity(1));
        }
        Material material = Material.matchMaterial(entry.item());
        if (material == null || !material.isItem() || material.isAir()) {
            return Optional.empty();
        }
        return Optional.of(ItemStack.of(material));
    }

    /** The plain name used in text: {@code stone}, {@code zombie spawner}. */
    String name(ShopSettings.Entry entry) {
        if (entry.spawner()) {
            return this.lang.plain(ShopMessages.SPAWNER_NAME, Arg.text("mob", ItemKeys.name(entry.mob())));
        }
        return ItemKeys.name(entry.item());
    }

    /** What the purchase is called in the ledger note. */
    static String ledgerName(ShopSettings.Entry entry) {
        return entry.spawner() ? "spawner " + entry.mob() : entry.item();
    }

    boolean spawnerProvider() {
        return !this.spawners.mobs().isEmpty();
    }

    SpawnerItems spawners() {
        return this.spawners;
    }
}
