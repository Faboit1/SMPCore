package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.item.ItemCategories;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.item.ItemPatterns;
import org.junit.jupiter.api.Test;

/** Categories, blacklist patterns, sort orders and inventory planning: the pure rules behind the menus. */
class ItemRulesTest {

    private static ItemCategory classify(String key, Set<String> tags, boolean block, boolean food) {
        return ItemCategories.classify(new ItemCategories.Traits(key, tags, block, food));
    }

    @Test
    void categories() {
        assertEquals(ItemCategory.SPAWNERS, classify("minecraft:spawner", Set.of(), true, false));
        assertEquals(ItemCategory.SPAWNERS, classify("minecraft:trial_spawner", Set.of(), true, false));
        assertEquals(ItemCategory.POTIONS, classify("minecraft:splash_potion", Set.of(), false, false));
        assertEquals(ItemCategory.POTIONS, classify("minecraft:ominous_bottle", Set.of(), false, false));
        assertEquals(ItemCategory.BOOKS, classify("minecraft:enchanted_book", Set.of(), false, false));
        assertEquals(ItemCategory.BOOKS, classify("minecraft:writable_book", Set.of(), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:diamond_sword", Set.of("swords"), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:iron_spear", Set.of("spears"), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:netherite_helmet", Set.of("head_armor"), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:spectral_arrow", Set.of("arrows"), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:mace", Set.of(), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:totem_of_undying", Set.of(), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:end_crystal", Set.of(), false, false));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:diamond_horse_armor", Set.of(), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:netherite_axe", Set.of("axes"), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:diamond_pickaxe", Set.of("pickaxes"), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:water_bucket", Set.of(), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:axolotl_bucket", Set.of(), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:hopper_minecart", Set.of(), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:oak_boat", Set.of("boats"), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:elytra", Set.of(), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:shears", Set.of(), false, false));
        assertEquals(ItemCategory.FOOD, classify("minecraft:golden_apple", Set.of(), false, true));
        assertEquals(ItemCategory.FOOD, classify("minecraft:bread", Set.of(), false, true));
        assertEquals(ItemCategory.BLOCKS, classify("minecraft:stone", Set.of(), true, false));
        assertEquals(ItemCategory.BLOCKS, classify("minecraft:shulker_box", Set.of(), true, false));
        assertEquals(ItemCategory.BLOCKS, classify("minecraft:cake", Set.of(), true, false));
        assertEquals(ItemCategory.MISC, classify("minecraft:stick", Set.of(), false, false));
        assertEquals(ItemCategory.MISC, classify("minecraft:diamond", Set.of(), false, false));
        assertEquals(ItemCategory.MISC, classify("", Set.of(), false, false));
    }

    @Test
    void categoryRulesAreOrdered() {
        // A spawner is a block, a potion is drinkable: the specific rule wins over the general one.
        assertEquals(ItemCategory.SPAWNERS, classify("minecraft:spawner", Set.of(), true, true));
        assertEquals(ItemCategory.COMBAT, classify("minecraft:turtle_helmet", Set.of("head_armor"), false, false));
        assertEquals(ItemCategory.TOOLS, classify("minecraft:milk_bucket", Set.of(), false, false));
    }

    @Test
    void blacklistPatterns() {
        ItemPatterns patterns = ItemPatterns.compile(List.of("minecraft:barrier", "command_block", "minecraft:*_spawn_egg", "Minecraft:Bedrock"));
        assertTrue(patterns.matches("minecraft:barrier"));
        assertTrue(patterns.matches("barrier"));
        assertTrue(patterns.matches("minecraft:command_block"));
        assertFalse(patterns.matches("minecraft:chain_command_block"));
        assertTrue(patterns.matches("minecraft:zombie_spawn_egg"));
        assertTrue(patterns.matches("MINECRAFT:ZOMBIE_SPAWN_EGG"));
        assertFalse(patterns.matches("minecraft:egg"));
        assertTrue(patterns.matches("minecraft:bedrock"));
        assertFalse(patterns.matches("minecraft:stone"));
        assertFalse(patterns.matches(null));
        assertEquals(4, patterns.size());
        assertEquals("minecraft:command_block", patterns.entries().get(1));
    }

    @Test
    void blacklistPatternsAreLiteralApartFromStars() {
        ItemPatterns patterns = ItemPatterns.compile(List.of("minecraft:a.b"));
        assertTrue(patterns.matches("minecraft:a.b"));
        assertFalse(patterns.matches("minecraft:axb"));
        assertTrue(ItemPatterns.compile(List.of("*:*")).matches("other:thing"));
        assertFalse(ItemPatterns.compile(List.of("*")).matches("other:thing"), "a bare pattern is in the minecraft namespace");
        assertTrue(ItemPatterns.compile(List.of("*")).matches("minecraft:stone"));
        assertFalse(ItemPatterns.none().matches("minecraft:barrier"));
    }

    @Test
    void invalidBlacklistEntriesAreReported() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ItemPatterns.compile(List.of("minecraft:barrier", "bad item")));
        assertTrue(error.getMessage().contains("bad item"));
        assertThrows(IllegalArgumentException.class, () -> ItemPatterns.compile(List.of("a:b:c")));
        assertThrows(IllegalArgumentException.class, () -> ItemPatterns.compile(List.of("(.*)")));
    }

    private static Listing<String> listing(long id, long price, long created, long expires) {
        return new Listing<>(id, new UUID(0, id), "x", "minecraft:stone", "stone", ItemCategory.BLOCKS, 1, price, created, expires);
    }

    @Test
    void sortOrders() {
        List<Listing<String>> listings = new ArrayList<>(List.of(
            listing(1, 300, 100, 5000), listing(2, 100, 300, 4000), listing(3, 200, 200, 3000), listing(4, 100, 400, 6000)));
        Collections.shuffle(listings, new java.util.Random(7));
        listings.sort(SortOrder.NEWEST.comparator());
        assertEquals(List.of(4L, 2L, 3L, 1L), ids(listings));
        listings.sort(SortOrder.ENDING_SOON.comparator());
        assertEquals(List.of(3L, 2L, 1L, 4L), ids(listings));
        listings.sort(SortOrder.LOWEST_PRICE.comparator());
        assertEquals(List.of(4L, 2L, 3L, 1L), ids(listings), "equal prices: newest first");
        listings.sort(SortOrder.HIGHEST_PRICE.comparator());
        assertEquals(List.of(1L, 3L, 4L, 2L), ids(listings));
    }

    @Test
    void sortOrderIds() {
        for (SortOrder order : SortOrder.values()) {
            assertEquals(order, SortOrder.byId(order.id()));
        }
        assertEquals("ending-soon", SortOrder.ENDING_SOON.id());
        assertEquals(null, SortOrder.byId("cheapest"));
    }

    private static List<Long> ids(List<Listing<String>> listings) {
        return listings.stream().map(Listing::id).toList();
    }

    /** A test stack: a kind, an amount and a max stack size. */
    private record Stack(String kind, int amount, int max) {
    }

    private static final StackPlanner<Stack> PLANNER = new StackPlanner<>(new StackPlanner.Stacks<>() {
        @Override
        public boolean empty(Stack stack) {
            return stack == null || stack.amount() <= 0;
        }

        @Override
        public boolean similar(Stack a, Stack b) {
            return a.kind().equals(b.kind());
        }

        @Override
        public int amount(Stack stack) {
            return stack.amount();
        }

        @Override
        public int maxStack(Stack stack) {
            return stack.max();
        }
    });

    @Test
    void plannerFillsMatchingStacksThenEmptySlots() {
        List<Stack> slots = new ArrayList<>(List.of(new Stack("dirt", 60, 64), new Stack("stone", 64, 64)));
        slots.add(null);
        assertEquals(List.of(0), PLANNER.fitting(slots, List.of(new Stack("dirt", 68, 64))));
        assertEquals(List.of(), PLANNER.fitting(slots, List.of(new Stack("dirt", 69, 64))));
    }

    @Test
    void plannerSkipsStacksThatDoNotFitAndKeepsGoing() {
        List<Stack> slots = new ArrayList<>();
        slots.add(null);
        slots.add(new Stack("dirt", 63, 64));
        List<Stack> incoming = List.of(new Stack("sword", 1, 1), new Stack("pearl", 16, 16), new Stack("dirt", 1, 64));
        assertEquals(List.of(0, 2), PLANNER.fitting(slots, incoming), "the sword takes the empty slot, the pearls no longer fit");
        assertFalse(PLANNER.fitsAll(slots, incoming));
    }

    @Test
    void plannerHandlesFullAndEmptyInventories() {
        List<Stack> full = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            full.add(new Stack("stone", 64, 64));
        }
        assertEquals(List.of(), PLANNER.fitting(full, List.of(new Stack("stone", 1, 64))));
        List<Stack> empty = new ArrayList<>(Collections.nCopies(36, (Stack) null));
        List<Stack> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new Stack("sword" + i, 1, 1));
        }
        assertEquals(36, PLANNER.fitting(empty, many).size());
        assertTrue(PLANNER.fitsAll(empty, many.subList(0, 36)));
    }
}
