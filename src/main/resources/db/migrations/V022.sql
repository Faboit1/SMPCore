-- orders: exact variants (enchanted books, potions, spawners)
-- NULL means plain items of item_type; otherwise the exact kind, e.g. enchant:minecraft:mending:1.
ALTER TABLE orders ADD COLUMN variant VARCHAR(160);
