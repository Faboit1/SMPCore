package net.siftvanilla.siftcore.feature.kits;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A kit from {@code features/kits.yml}. Pure data.
 *
 * @param id          stable id: the {@code /kit <id>} argument, the {@code kit_claims} key and the permission suffix
 * @param name        display name in plain text
 * @param description one plain line saying what it is for, or null
 * @param icon        the item id shown for it
 * @param everyone    whether every player has its permission by default
 * @param cooldown    how often it can be claimed
 * @param items       what it gives, in file order
 * @param keys        crate keys it also gives: crate id to amount, in file order
 */
public record Kit(String id, String name, String description, String icon, boolean everyone, Cooldown cooldown,
           List<KitItem> items, Map<String, Integer> keys) {

    /** Permission nodes of kits start with this; the kit id follows. */
    static final String NODE_PREFIX = "siftcore.kit.";

    public Kit {
        items = List.copyOf(items);
        keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    /** The node a player needs to see and claim the kit. */
    public String permission() {
        return NODE_PREFIX + this.id;
    }
}
