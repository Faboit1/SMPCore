package net.siftvanilla.siftcore.feature.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The variant rule of enchanted book orders: a book matches only with exactly the one stored enchantment at the
 * ordered level, no anvil repair cost and nothing else changed. The server applies the same rule with {@code isSimilar}
 * against the canonical book; its self-test checks that both agree.
 */
class VariantMatchTest {

    private static final Variant.Enchant MENDING = new Variant.Enchant("minecraft:mending", 1);
    private static final Variant.Enchant SHARPNESS_4 = new Variant.Enchant("minecraft:sharpness", 4);

    private static Variant.BookFacts book(Map<String, Integer> stored, int repairCost, boolean otherData) {
        return new Variant.BookFacts(stored, repairCost, otherData);
    }

    @Test
    void theExactBookMatches() {
        assertTrue(MENDING.accepts(book(Map.of("minecraft:mending", 1), 0, false)));
        assertTrue(SHARPNESS_4.accepts(book(Map.of("minecraft:sharpness", 4), 0, false)));
    }

    @Test
    void anExtraEnchantmentDoesNotMatch() {
        assertFalse(MENDING.accepts(book(Map.of("minecraft:mending", 1, "minecraft:unbreaking", 3), 0, false)),
            "the extra enchantment would be lost to the buyer's order");
    }

    @Test
    void aRepairCostDoesNotMatch() {
        assertFalse(MENDING.accepts(book(Map.of("minecraft:mending", 1), 1, false)), "books combined in an anvil carry a repair cost");
        assertFalse(MENDING.accepts(book(Map.of("minecraft:mending", 1), 7, false)));
    }

    @Test
    void anotherLevelOrEnchantmentDoesNotMatch() {
        assertFalse(SHARPNESS_4.accepts(book(Map.of("minecraft:sharpness", 5), 0, false)), "a higher level is a different book");
        assertFalse(SHARPNESS_4.accepts(book(Map.of("minecraft:sharpness", 3), 0, false)));
        assertFalse(MENDING.accepts(book(Map.of("minecraft:unbreaking", 1), 0, false)));
        assertFalse(MENDING.accepts(book(Map.of(), 0, false)), "an empty book is not a Mending book");
    }

    @Test
    void anyOtherDataDoesNotMatch() {
        assertFalse(MENDING.accepts(book(Map.of("minecraft:mending", 1), 0, true)), "a renamed or tagged book is a different item");
    }

    @Test
    void idsRoundTrip() {
        assertEquals("enchant:minecraft:mending:1", MENDING.id());
        assertEquals(MENDING, Variant.parse("enchant:minecraft:mending:1"));
        assertEquals(SHARPNESS_4, Variant.parse(" ENCHANT:minecraft:sharpness:4 "));
        assertEquals(new Variant.Potion("minecraft:long_swiftness"), Variant.parse("potion:minecraft:long_swiftness"));
        assertEquals(new Variant.Spawner("skeleton"), Variant.parse("spawner:skeleton"));
        assertInstanceOf(Variant.Enchant.class, Variant.parse("enchant:custompack:soulbound:2"), "data pack enchantments parse too");
        assertEquals("minecraft:enchanted_book|enchant:minecraft:mending:1", OrderKeys.key("minecraft:enchanted_book", MENDING.id()));
        assertEquals(new OrderKeys.Parts("minecraft:enchanted_book", "enchant:minecraft:mending:1"),
            OrderKeys.parse("minecraft:enchanted_book|enchant:minecraft:mending:1"));
        assertEquals(new OrderKeys.Parts("minecraft:diamond", null), OrderKeys.parse("minecraft:diamond"));
    }

    @Test
    void brokenIdsAreRefused() {
        for (String id : new String[] {"enchant:minecraft:mending", "enchant:minecraft:mending:", "enchant::1", "enchant:mending:0",
            "enchant:minecraft:mending:256", "enchant:minecraft:mending:x", "potion:", "potion:Not A Key", "spawner:", "spawner:a b",
            "sword:minecraft:diamond", ""}) {
            assertNull(Variant.tryParse(id), () -> "'" + id + "' should be refused");
        }
        assertThrows(IllegalArgumentException.class, () -> Variant.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new Variant.Enchant("minecraft:mending", 0));
    }

    @Test
    void levelsReadLikeTheGame() {
        assertEquals("I", Variant.roman(1));
        assertEquals("IV", Variant.roman(4));
        assertEquals("X", Variant.roman(10));
        assertEquals("11", Variant.roman(11));
    }
}
