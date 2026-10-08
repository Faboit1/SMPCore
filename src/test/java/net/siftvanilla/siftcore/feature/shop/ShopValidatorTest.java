package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.feature.sell.Pricing;
import net.siftvanilla.siftcore.feature.sell.RecipeDef;
import net.siftvanilla.siftcore.feature.sell.WorthCalculator;
import net.siftvanilla.siftcore.feature.sell.WorthTable;
import org.junit.jupiter.api.Test;

class ShopValidatorTest {

    private static RecipeDef recipe(String id, String output, int count, List<List<String>> slots) {
        return new RecipeDef("test:" + id, RecipeDef.Kind.CRAFTING, output, count, RecipeDef.slots(slots));
    }

    private static List<List<String>> times(int n, String option) {
        List<List<String>> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            slots.add(List.of(option));
        }
        return slots;
    }

    private static Pricing pricing(Map<String, Long> base, List<RecipeDef> recipes, double multiplier) {
        WorthTable table = WorthCalculator.calculate(base, Map.of(), recipes, 0.9, Long.MAX_VALUE).table();
        return new Pricing(table, multiplier, recipes);
    }

    private static String check(String item, long price, Pricing pricing) {
        return ShopValidator.check(item, price, pricing, ShopValidator.analyze(pricing));
    }

    @Test
    void minimumPriceIsStrictlyAboveWorthTimesMultiplierTimesMargin() {
        // 20 * 1.5 * 1.1 = 33 exactly, so 33 itself is not enough
        assertEquals(34, ShopValidator.minimumPrice(20, 1.5));
        assertEquals(441, ShopValidator.minimumPrice(400, 1.0));
        assertEquals(1, ShopValidator.minimumPrice(0, 1.5));
        assertThrows(ArithmeticException.class, () -> ShopValidator.minimumPrice(1e19, 1.5));
    }

    @Test
    void buyingAndSellingBackMustLoseMoney() {
        Pricing pricing = pricing(Map.of("diamond", 400L), List.of(), 1.5);
        String problem = check("diamond", 660, pricing);
        assertNotNull(problem);
        assertTrue(problem.contains("at least 661"), problem);
        assertNull(check("diamond", 661, pricing));
    }

    @Test
    void itemsThatCantBeSoldMayCostAnything() {
        Pricing pricing = pricing(Map.of("diamond", 400L), List.of(), 1.5);
        assertNull(check("name_tag", 1, pricing));
    }

    @Test
    void craftingIntoSomethingWorthMoreIsCaught() {
        // Raw iron sells for 20 but smelts into an ingot that sells for 25.
        Pricing pricing = pricing(Map.of("raw_iron", 20L, "iron_ingot", 25L),
            List.of(new RecipeDef("test:smelt", RecipeDef.Kind.SMELTING, "iron_ingot", 1, RecipeDef.slots(times(1, "raw_iron")))),
            1.5);
        // 34 beats the raw iron's own sell price (33) but not the ingot's: 25 * 1.65 = 41.25
        String problem = check("raw_iron", 34, pricing);
        assertNotNull(problem);
        assertTrue(problem.contains("iron_ingot") && problem.contains("at least 42"), problem);
        assertNull(check("raw_iron", 42, pricing));
    }

    @Test
    void recipeChainsAreFollowed() {
        // planks -> 4 sticks; flint + stick + feather -> 4 arrows worth 2 each (a base price above its inputs).
        List<RecipeDef> recipes = List.of(
            recipe("stick", "stick", 4, times(2, "planks")),
            recipe("arrow", "arrow", 4, List.of(List.of("flint"), List.of("stick"), List.of("feather"))));
        Pricing pricing = pricing(Map.of("planks", 1L, "arrow", 2L, "flint", 3L, "feather", 4L), recipes, 1.5);
        ShopValidator.Analysis analysis = ShopValidator.analyze(pricing);
        // one stick turns 7 dollars of flint and feather into 8 dollars of arrows: worth 1; two planks make four
        assertEquals(1.0, analysis.of("stick").value(), 1e-9);
        assertEquals(2.0, analysis.of("planks").value(), 1e-9);
        assertEquals("arrow", analysis.of("planks").via());
        assertEquals("test:stick", analysis.of("planks").recipe());
        assertNotNull(check("planks", 3, pricing));
        assertNull(check("planks", 4, pricing));
    }

    @Test
    void derivedItemsNeverRaiseTheBar() {
        // Iron blocks are derived from ingots, so they are always worth less than the ingots that make them.
        List<RecipeDef> recipes = List.of(recipe("block", "iron_block", 1, times(9, "iron_ingot")),
            recipe("door", "iron_door", 3, times(6, "iron_ingot")));
        Pricing pricing = pricing(Map.of("iron_ingot", 25L), recipes, 1.5);
        assertEquals(25.0, ShopValidator.analyze(pricing).of("iron_ingot").value(), 1e-9);
        assertNull(check("iron_ingot", ShopValidator.minimumPrice(25, 1.5), pricing));
    }

    @Test
    void otherIngredientsAreCountedAtTheirCheapestWorth() {
        // Gold apple = 8 gold + apple, worth 300. Buying apples only "earns" what the gold doesn't cover.
        List<RecipeDef> recipes = List.of(new RecipeDef("test:golden_apple", RecipeDef.Kind.CRAFTING, "golden_apple", 1,
            List.of(new RecipeDef.Ingredient(List.of("gold_ingot"), 8), new RecipeDef.Ingredient(List.of("apple"), 1))));
        Pricing pricing = pricing(Map.of("gold_ingot", 35L, "apple", 4L, "golden_apple", 300L), recipes, 1.0);
        // 300 - 8 * 35 = 20 per apple
        assertEquals(20.0, ShopValidator.analyze(pricing).of("apple").value(), 1e-9);
        assertNotNull(check("apple", 22, pricing));
        assertNull(check("apple", 23, pricing));
    }

    @Test
    void ingredientsThatCantBeSoldStillCostWhatMakingThemCosts() {
        // A yellow box can't be sold (its dye has no price), but making one takes a box worth 100. Re-dyeing it white
        // must not make white dye look like it is worth a whole box.
        List<String> boxes = List.of("box", "white_box", "yellow_box");
        List<RecipeDef> recipes = List.of(
            recipe("white_dye", "white_dye", 1, times(1, "bone_meal")),
            recipe("yellow_dye", "yellow_dye", 1, times(1, "dandelion")),
            recipe("white_box", "white_box", 1, List.of(boxes, List.of("white_dye"))),
            recipe("yellow_box", "yellow_box", 1, List.of(boxes, List.of("yellow_dye"))));
        Pricing pricing = pricing(Map.of("box", 100L, "bone_meal", 1L), recipes, 1.5);
        assertEquals(90, pricing.table().price("white_box"));
        assertFalse(pricing.table().sellable("yellow_box"));
        ShopValidator.Analysis analysis = ShopValidator.analyze(pricing);
        assertNull(analysis.of("white_dye"));
        assertEquals(1.0, analysis.of("bone_meal").value(), 1e-9);
        assertNull(check("bone_meal", 2, pricing));
        // what the yellow box costs: the cheapest box plus a free dye
        assertEquals(90.0, ShopValidator.costs(pricing.table(), recipes).get("yellow_box"), 1e-9);
    }

    @Test
    void itemsThatSellCostTheirPriceEvenWhenAFreeItemSmeltsIntoThem() {
        // Ore can't be sold and smelts into a gem worth 400. Using a gem still costs 400 (it could be sold), so a
        // sword of two gems and a handle doesn't make handles worth the whole sword.
        List<RecipeDef> recipes = List.of(
            new RecipeDef("test:gem_from_ore", RecipeDef.Kind.SMELTING, "gem", 1, RecipeDef.slots(times(1, "ore"))),
            recipe("sword", "sword", 1, List.of(List.of("gem"), List.of("gem"), List.of("handle"))));
        Pricing pricing = pricing(Map.of("gem", 400L, "handle", 1L), recipes, 1.5);
        assertEquals(720, pricing.table().price("sword"));
        assertEquals(400.0, ShopValidator.costs(pricing.table(), recipes).get("gem"), 1e-9);
        assertEquals(1.0, ShopValidator.analyze(pricing).of("handle").value(), 1e-9);
        assertNull(check("handle", 2, pricing));
        // but ore is worth a whole gem to whoever buys it
        assertNotNull(check("ore", 600, pricing));
        assertNull(check("ore", 661, pricing));
    }

    @Test
    void valueLoopsAreReported() {
        // One A crafts into two B and one B crafts back into one A: value doubles forever.
        List<RecipeDef> recipes = List.of(recipe("split", "b", 2, times(1, "a")), recipe("join", "a", 1, times(1, "b")));
        Pricing pricing = new Pricing(new WorthTable(Map.of("a", new WorthTable.Entry(1, WorthTable.Origin.BASE, null),
            "b", new WorthTable.Entry(1, WorthTable.Origin.BASE, null)), java.util.Set.of(), Map.of(), Map.of()), 1.0, recipes);
        ShopValidator.Analysis analysis = ShopValidator.analyze(pricing);
        assertFalse(analysis.settled());
        assertFalse(analysis.loopItems().isEmpty());
        assertNotNull(ShopValidator.check("diamond", 1_000_000, pricing, analysis));
    }
}
