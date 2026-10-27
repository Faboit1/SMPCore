package net.siftvanilla.siftcore.core.link;

import java.util.Optional;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

/** Creates SiftCore spawner items. Implemented by the spawners feature; used by the shop and crates. */
public interface SpawnerItems {

    SpawnerItems NONE = new SpawnerItems() {
        @Override
        public Set<String> mobs() {
            return Set.of();
        }

        @Override
        public Optional<ItemStack> create(String mob, int amount) {
            return Optional.empty();
        }
    };

    /** Configured mob ids (lowercase entity type keys such as {@code skeleton}). */
    Set<String> mobs();

    /** A stack of spawner items for that mob, or empty if the mob is not configured. */
    Optional<ItemStack> create(String mob, int amount);
}
