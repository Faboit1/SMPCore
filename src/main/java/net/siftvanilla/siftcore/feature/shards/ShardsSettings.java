package net.siftvanilla.siftcore.feature.shards;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/shards.yml}.
 *
 * @param blockInCombat combat-tagged players can't open the shop or buy (no totems or golden apples mid-fight)
 */
record ShardsSettings(long confirmAbove, boolean blockInCombat, List<ShardOffer> offers) {

    static final long MAX_PRICE = 1_000_000_000_000L;
    static final int MAX_UNITS = 64;

    ShardsSettings {
        offers = List.copyOf(offers);
    }

    /** The offer with this id, or null. */
    ShardOffer offer(String id) {
        for (ShardOffer offer : this.offers) {
            if (offer.id().equals(id)) {
                return offer;
            }
        }
        return null;
    }

    /**
     * @param itemExists tells whether an item id ({@code minecraft:stone}) names a real item
     */
    static ShardsSettings parse(ConfigReader r, Predicate<String> itemExists) {
        ConfigReader shop = r.section("shop");
        long confirmAbove = shop.longValue("confirm-above", 0, MAX_PRICE, 500);
        boolean blockInCombat = !shop.has("block-in-combat") || shop.bool("block-in-combat", true);
        List<ShardOffer> offers = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : shop.children("offers").entrySet()) {
            String id = entry.getKey();
            ConfigReader o = entry.getValue();
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                shop.problem("offers." + id, "is not a valid offer id (1 to 32 lowercase letters, digits, - or _)");
                continue;
            }
            int before = o.problems().size();
            ShardOffer.Kind kind = o.enumValue("type", ShardOffer.Kind.class, ShardOffer.Kind.ITEM);
            String target;
            int amount;
            if (kind == ShardOffer.Kind.KEY) {
                String crate = o.string("crate", "").trim();
                target = crate.toLowerCase(Locale.ROOT);
                if (!target.matches("[a-z0-9_-]{1,32}")) {
                    o.problem("crate", "must be a crate id (1 to 32 lowercase letters, digits, - or _), got '" + crate + "'");
                }
                amount = o.has("keys") ? o.integer("keys", 1, 64, 1) : 1;
            } else {
                String item = o.string("item", "").trim().toLowerCase(Locale.ROOT);
                target = item.contains(":") ? item : "minecraft:" + item;
                if (!itemExists.test(target)) {
                    o.problem("item", "is not an item: '" + item + "'");
                }
                amount = o.has("amount") ? o.integer("amount", 1, 64, 1) : 1;
            }
            String name = o.optionalString("name", "").strip();
            if (name.length() > 48) {
                o.problem("name", "is longer than 48 characters");
                name = name.substring(0, 48);
            }
            String description = o.optionalString("description", "").strip();
            if (description.length() > 120) {
                o.problem("description", "is longer than 120 characters");
                description = description.substring(0, 120);
            }
            long price = o.longValue("price", 1, MAX_PRICE, 1);
            int max = o.has("max") ? o.integer("max", 1, MAX_UNITS, 1) : 1;
            int order = o.has("order") ? o.integer("order", -1_000_000, 1_000_000, ShardOffer.DEFAULT_ORDER) : ShardOffer.DEFAULT_ORDER;
            String permission = o.optionalString("permission", "").strip();
            if (!permission.isEmpty() && !permission.matches("[a-z0-9_.-]{1,128}")) {
                o.problem("permission", "is not a permission node: '" + permission + "'");
                permission = "";
            }
            if (o.problems().size() > before) {
                // A broken offer is left out rather than sold with fallback values.
                continue;
            }
            offers.add(new ShardOffer(id, kind, name, description, target, amount, price, max, permission, order));
        }
        // Listed by order; offers with the same order keep their file order (new offers land at the end of an older file).
        offers.sort(Comparator.comparingInt(ShardOffer::order));
        return new ShardsSettings(confirmAbove, blockInCombat, offers);
    }
}
