package net.siftvanilla.siftcore.feature.spawners;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Makes and recognises SiftCore spawner items: a vanilla spawner item whose mob id is kept in its persistent data
 * (never in block entity data, which survival players can't place), with a plain name such as "Skeleton spawner"
 * and short gray lore. Items of the same mob stack with each other. Also the {@link SpawnerItems} the shop and
 * crates use; they only get items for enabled mobs.
 */
final class SpawnerItemFactory implements SpawnerItems {

    private final NamespacedKey key;
    private final Supplier<SpawnersSettings> settings;
    private final Lang lang;

    SpawnerItemFactory(NamespacedKey key, Supplier<SpawnersSettings> settings, Lang lang) {
        this.key = key;
        this.settings = settings;
        this.lang = lang;
    }

    @Override
    public Set<String> mobs() {
        return this.settings.get().enabledMobs();
    }

    /**
     * Spawner items of an enabled mob. The amount may exceed the item's max stack size (64): split it before putting
     * it into an inventory slot or a claim box (both {@code Inventory#addItem} and the claim box split by themselves).
     */
    @Override
    public Optional<ItemStack> create(String mob, int amount) {
        MobDef def = this.settings.get().mob(mob);
        if (def == null || !def.enabled() || amount <= 0) {
            return Optional.empty();
        }
        return Optional.of(item(mob, amount));
    }

    /** Spawner items of any mob id, configured or not (giving back spawners of a mob that was removed from config). */
    ItemStack item(String mob, int amount) {
        MobDef def = this.settings.get().mob(mob);
        String name = def == null ? MobDef.fallbackName(mob) : def.name();
        ItemStack item = ItemStack.of(Material.SPAWNER, Math.max(1, amount));
        item.setData(DataComponentTypes.CUSTOM_NAME, plain(this.lang.get(SpawnersMessages.ITEM_NAME, Arg.text("name", name))));
        List<Component> lore = new ArrayList<>();
        for (Component line : this.lang.lines(SpawnersMessages.ITEM_LORE)) {
            lore.add(plain(line));
        }
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        }
        item.editPersistentDataContainer(pdc -> pdc.set(this.key, PersistentDataType.STRING, mob));
        return item;
    }

    /** The mob id of a SiftCore spawner item, or null for anything else (including vanilla spawner items). */
    String mobOf(ItemStack item) {
        if (item == null || item.isEmpty() || item.getType() != Material.SPAWNER) {
            return null;
        }
        return item.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
    }

    private static Component plain(Component line) {
        return line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
