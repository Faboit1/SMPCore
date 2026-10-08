package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WorthCalculatorTest {

    private static final long MAX = 1_000_000_000_000_000L;

    private static RecipeDef recipe(String id, String output, int count, List<List<String>> slots) {
        return new RecipeDef("test:" + id, RecipeDef.Kind.CRAFTING, output, count, RecipeDef.slots(slots));
    }

    private static List<List<String>> times(int n, String... options) {
        List<List<String>> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            slots.add(List.of(options));
        }
        return slots;
    }

    private static WorthCalculator.Result calc(Map<String, Long> base, List<RecipeDef> recipes, double loss) {
        return WorthCalculator.calculate(base, Map.of(), recipes, loss, MAX);
    }

    @Test
    void basePricesPassThroughUnchanged() {
        var result = calc(Map.of("diamond", 400L, "dirt", 1L), List.of(), 0.9);
        assertEquals(400, result.table().price("diamond"));
        assertEquals(1, result.table().price("dirt"));
        assertEquals(WorthTable.Origin.BASE, result.table().entry("diamond").origin());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void derivedPriceIsIngredientsOverOutputTimesLoss() {
        // 9 iron ingots (25) -> 1 block: 225 * 0.9 = 202.5 -> 202
        var result = calc(Map.of("iron_ingot", 25L), List.of(recipe("iron_block", "iron_block", 1, times(9, "iron_ingot"))), 0.9);
        assertEquals(202, result.table().price("iron_block"));
        assertEquals(WorthTable.Origin.DERIVED, result.table().entry("iron_block").origin());
        assertEquals("test:iron_block", result.table().entry("iron_block").recipe());
    }

    @Test
    void outputCountDividesAndLossAppliesPerStep() {
        // log 6 -> 4 planks: 6 * 0.9 / 4 = 1.35 -> 1; 2 planks make 4 sticks: 2 * 0.9 / 4 = 0.45, below $1
        var result = calc(Map.of("log", 6L), List.of(
            recipe("planks", "planks", 4, times(1, "log")),
            recipe("stick", "stick", 4, times(2, "planks")),
            recipe("crafting_table", "crafting_table", 1, times(4, "planks"))), 0.9);
        assertEquals(1, result.table().price("planks"));
        assertEquals(0, result.table().price("stick"));
        assertEquals(0.45, result.table().belowOne().get("stick"), 1e-9);
        // ingredients count at their whole-dollar price: 4 * 1 * 0.9 = 3.6 -> 3, never more than the 4 planks sell for
        assertEquals(3, result.table().price("crafting_table"));
    }

    @Test
    void lossOfOneKeepsFullValue() {
        var result = calc(Map.of("wheat", 6L), List.of(recipe("hay", "hay", 1, times(9, "wheat"))), 1.0);
        assertEquals(54, result.table().price("hay"));
    }

    @Test
    void cheapestOptionAndCheapestRecipeWin() {
        var result = calc(Map.of("oak_log", 6L, "birch_log", 4L, "bamboo", 2L), List.of(
            recipe("stick_from_bamboo", "stick", 1, times(2, "bamboo")),
            recipe("table", "table", 1, times(4, "oak_log", "birch_log"))), 0.9);
        // 4 * min(6, 4) * 0.9 = 14.4
        assertEquals(14, result.table().price("table"));
        // the only stick recipe here is bamboo: 2 * 2 * 0.9 = 3.6
        assertEquals(3, result.table().price("stick"));
    }

    @Test
    void cheapestRouteWinsWhereverItSortsAndEverythingBuiltOnItFollows() {
        // The expensive stick recipe sorts first; the cheap one needs planks, which need logs.
        var result = calc(Map.of("log", 6L, "bamboo", 20L, "flint", 3L, "feather", 4L), List.of(
            recipe("a_stick_from_bamboo", "stick", 1, times(2, "bamboo")),
            recipe("b_torch", "torch", 4, List.of(List.of("stick"), List.of("flint"))),
            recipe("c_planks", "planks", 4, times(1, "log")),
            recipe("d_stick", "stick", 4, times(2, "planks"))), 0.9);
        assertEquals(0.45, result.table().belowOne().get("stick"), 1e-9);
        // torch would be 8 with the bamboo stick (36); with the plank stick (worth 0) it is 3 * 0.9 / 4 = 0.675
        assertFalse(result.table().sellable("torch"));
        assertEquals(1, result.passes());
    }

    @Test
    void cyclesDoNotShrinkPrices() {
        // raw -> ingot by smelting; ingot <-> 9 nuggets; ingot <-> block. None of the loops may lower the ingot.
        var result = calc(Map.of("raw_iron", 20L), List.of(
            new RecipeDef("test:smelt", RecipeDef.Kind.SMELTING, "ingot", 1, RecipeDef.slots(times(1, "raw_iron"))),
            recipe("nuggets", "nugget", 9, times(1, "ingot")),
            recipe("ingot_from_nuggets", "ingot", 1, times(9, "nugget")),
            recipe("block", "block", 1, times(9, "ingot")),
            recipe("ingot_from_block", "ingot", 9, times(1, "block"))), 0.9);
        assertEquals(18, result.table().price("ingot"));
        assertEquals(1, result.table().price("nugget"));
        assertEquals(145, result.table().price("block"));
        assertEquals("test:smelt", result.table().entry("ingot").recipe());
    }

    @Test
    void dyeLoopsBetweenColoursDoNotDragTheSourceDown() {
        // white wool from 4 string; every colour can be re-dyed from any other wool.
        List<String> wools = List.of("white_wool", "red_wool", "blue_wool");
        List<RecipeDef> recipes = new ArrayList<>();
        recipes.add(recipe("white_wool_from_string", "white_wool", 1, times(4, "string")));
        for (String colour : wools) {
            List<String> others = wools.stream().filter(w -> !w.equals(colour)).toList();
            String dye = colour.replace("_wool", "_dye");
            recipes.add(recipe("dye_" + colour, colour, 1, List.of(others, List.of(dye))));
        }
        var result = calc(Map.of("string", 6L, "white_dye", 1L, "red_dye", 1L, "blue_dye", 1L), recipes, 0.9);
        assertEquals(21, result.table().price("white_wool"));
        assertEquals("test:white_wool_from_string", result.table().entry("white_wool").recipe());
        // Both colours are one dye away from white: 0.9 * (21 + 1) = 19.8 -> 19. Neither is priced from the other
        // (that would chain the loss around the loop), and neither re-prices white.
        assertEquals(19, result.table().price("blue_wool"));
        assertEquals(19, result.table().price("red_wool"));
        assertEquals(2, result.passes());
    }

    @Test
    void loopsArePricedFromTheirNearestPricedMemberSoRecolouringCantGainValue() {
        // A plain box from two shells and a chest; any box re-dyes into any colour (a loop of six boxes).
        List<String> colours = List.of("white", "orange", "magenta", "light_blue", "yellow");
        List<String> boxes = new ArrayList<>();
        boxes.add("shulker_box");
        for (String colour : colours) {
            boxes.add(colour + "_shulker_box");
        }
        List<RecipeDef> recipes = new ArrayList<>();
        recipes.add(new RecipeDef("test:shulker_box", RecipeDef.Kind.CRAFTING, "shulker_box", 1,
            List.of(new RecipeDef.Ingredient(List.of("shulker_shell"), 2), new RecipeDef.Ingredient(List.of("chest"), 1))));
        Map<String, Long> base = new java.util.HashMap<>(Map.of("shulker_shell", 600L, "chest", 7L));
        long dyePrice = 0;
        for (String colour : colours) {
            base.put(colour + "_dye", ++dyePrice);
            String box = colour + "_shulker_box";
            recipes.add(new RecipeDef("test:" + box, RecipeDef.Kind.CRAFTING, box, 1,
                RecipeDef.slots(List.of(boxes, List.of(colour + "_dye")))));
        }
        WorthTable table = calc(base, recipes, 0.9).table();
        assertEquals(1086, table.price("shulker_box"));
        for (String colour : colours) {
            String box = colour + "_shulker_box";
            long dye = table.price(colour + "_dye");
            // one dye away from the plain box, never further down a chain of re-dyes
            assertEquals((long) Math.floor((1086 + dye) * 0.9), table.price(box), box);
            assertEquals("test:" + box, table.entry(box).recipe());
        }
        // Re-dyeing any box into another colour is worth at most a dollar of rounding.
        for (String from : boxes) {
            for (String colour : colours) {
                String to = colour + "_shulker_box";
                if (!to.equals(from)) {
                    assertTrue(table.price(to) <= table.price(from) + table.price(colour + "_dye") + 1, from + " -> " + to);
                }
            }
        }
    }

    @Test
    void loopsWithABasePricedMemberArePricedFromIt() {
        // ingot <-> nugget, ingot <-> block, ingot -> sword -> (smelted) nugget: one loop anchored by the ingot.
        var result = calc(Map.of("ingot", 25L, "stick", 1L), List.of(
            recipe("nugget", "nugget", 9, times(1, "ingot")),
            recipe("ingot_from_nuggets", "ingot", 1, times(9, "nugget")),
            recipe("block", "block", 1, times(9, "ingot")),
            recipe("ingot_from_block", "ingot", 9, times(1, "block")),
            recipe("sword", "sword", 1, List.of(List.of("ingot"), List.of("ingot"), List.of("stick"))),
            new RecipeDef("test:nugget_from_sword", RecipeDef.Kind.SMELTING, "nugget", 1, RecipeDef.slots(times(1, "sword")))), 0.9);
        assertEquals(25, result.table().price("ingot"));
        assertEquals(2, result.table().price("nugget"));
        assertEquals("test:nugget", result.table().entry("nugget").recipe());
        assertEquals(202, result.table().price("block"));
        assertEquals(45, result.table().price("sword"));
    }

    @Test
    void aRecipeThatCopiesItsOwnIngredientNeedsAnotherSource() {
        RecipeDef copy = recipe("template_copy", "template", 2, List.of(List.of("template"), List.of("diamond"), List.of("diamond")));
        assertFalse(calc(Map.of("diamond", 400L), List.of(copy), 0.9).table().sellable("template"));
        // With a base price the copy recipe is simply not needed.
        assertEquals(5000, calc(Map.of("diamond", 400L, "template", 5000L), List.of(copy), 0.9).table().price("template"));
    }

    @Test
    void componentsGroupLoopsAndNumberTheirProductsFirst() {
        // 0 -> 1 -> 2 -> 0 is a loop, 2 -> 3, and 4 stands alone.
        int[][] edges = {{1}, {2}, {0, 3}, {}, {}};
        int[] component = WorthCalculator.components(5, edges);
        assertEquals(component[0], component[1]);
        assertEquals(component[1], component[2]);
        assertTrue(component[3] != component[0]);
        assertTrue(component[4] != component[0] && component[4] != component[3]);
        // what the loop makes completes before the loop
        assertTrue(component[3] < component[0]);
    }

    @Test
    void basePricesAreNeverReplacedByCheaperRecipes() {
        var result = calc(Map.of("raw_iron", 20L, "iron_ingot", 25L), List.of(
            new RecipeDef("test:smelt", RecipeDef.Kind.SMELTING, "iron_ingot", 1, RecipeDef.slots(times(1, "raw_iron")))), 0.9);
        assertEquals(25, result.table().price("iron_ingot"));
        assertEquals(WorthTable.Origin.BASE, result.table().entry("iron_ingot").origin());
    }

    @Test
    void missingIngredientMakesARecipeUnusable() {
        var result = calc(Map.of("sugar", 2L), List.of(recipe("cake", "cake", 1, List.of(List.of("milk_bucket"), List.of("sugar")))), 0.9);
        assertFalse(result.table().sellable("cake"));
        assertNull(result.table().belowOne().get("cake"));
    }

    @Test
    void emptyOptionListMakesARecipeUnusable() {
        var result = calc(Map.of("sugar", 2L), List.of(recipe("weird", "weird", 1, List.of(List.of(), List.of("sugar")))), 0.9);
        assertFalse(result.table().sellable("weird"));
    }

    @Test
    void overridesApplyLastAndDoNotMoveOtherPrices() {
        var result = WorthCalculator.calculate(Map.of("iron_ingot", 25L),
            Map.of("iron_block", 0L, "iron_ingot", 30L, "nether_star", 150_000L),
            List.of(recipe("iron_block", "iron_block", 1, times(9, "iron_ingot")),
                recipe("iron_door", "iron_door", 3, times(6, "iron_ingot"))), 0.9, MAX);
        WorthTable table = result.table();
        assertFalse(table.sellable("iron_block"));
        assertTrue(table.unsellable().contains("iron_block"));
        assertEquals(30, table.price("iron_ingot"));
        assertEquals(WorthTable.Origin.OVERRIDE, table.entry("iron_ingot").origin());
        // derived from the base price (25), not from the override: 150 * 0.9 / 3 = 45
        assertEquals(45, table.price("iron_door"));
        assertEquals(150_000, table.price("nether_star"));
    }

    @Test
    void derivedPricesAboveTheMoneyLimitAreReported() {
        var result = WorthCalculator.calculate(Map.of("star", 900L), Map.of(),
            List.of(recipe("block", "block", 1, times(9, "star"))), 1.0, 1000);
        assertFalse(result.table().sellable("block"));
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().getFirst().contains("block"));
    }

    @Test
    void hugeValuesDoNotOverflow() {
        var result = WorthCalculator.calculate(Map.of("star", 900_000_000_000_000L), Map.of(),
            List.of(recipe("block", "block", 1, times(9, "star"))), 1.0, Long.MAX_VALUE);
        assertEquals(8_100_000_000_000_000L, result.table().price("block"));
        for (WorthTable.Entry entry : result.table().entries().values()) {
            assertTrue(entry.price() >= 1);
        }
    }

    @Test
    void mathIsExactDecimalNotFloatingPoint() {
        // In doubles 9 * 10 * 0.9 is 81.00000000000001 and 3 * 0.7 * 10 is 20.999999999999996.
        var result = calc(Map.of("ingot", 10L), List.of(recipe("block", "block", 1, times(9, "ingot"))), 0.9);
        assertEquals(81, result.table().price("block"));
        var sevenTenths = calc(Map.of("ingot", 10L), List.of(recipe("block", "block", 1, times(3, "ingot"))), 0.7);
        assertEquals(21, sevenTenths.table().price("block"));
    }

    @Test
    void resultDoesNotDependOnRecipeOrder() {
        List<RecipeDef> recipes = new ArrayList<>(List.of(
            recipe("planks", "planks", 4, times(1, "log")),
            recipe("stick", "stick", 4, times(2, "planks")),
            recipe("stick_bamboo", "stick", 1, times(2, "bamboo")),
            recipe("ladder", "ladder", 3, times(7, "stick")),
            recipe("chest", "chest", 1, times(8, "planks")),
            recipe("hopper", "hopper", 1, List.of(List.of("chest"), List.of("iron"), List.of("iron"), List.of("iron"),
                List.of("iron"), List.of("iron"))),
            recipe("nugget", "nugget", 9, times(1, "iron")),
            recipe("iron", "iron", 1, times(9, "nugget"))));
        Map<String, Long> base = Map.of("log", 6L, "bamboo", 2L, "iron", 25L);
        WorthTable expected = calc(base, recipes, 0.9).table();
        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(recipes, random);
            assertEquals(expected.entries(), calc(base, recipes, 0.9).table().entries());
        }
    }

    @Test
    void craftingADerivedItemNeverPaysMoreThanItsIngredients() {
        List<RecipeDef> recipes = List.of(
            recipe("planks", "planks", 4, times(1, "log")),
            recipe("chest", "chest", 1, times(8, "planks")),
            recipe("barrel", "barrel", 1, List.of(List.of("planks"), List.of("planks"), List.of("planks"), List.of("planks"),
                List.of("planks"), List.of("planks"), List.of("slab"), List.of("slab"))),
            recipe("slab", "slab", 6, times(3, "planks")));
        var table = calc(Map.of("log", 6L), recipes, 0.9).table();
        for (RecipeDef recipe : recipes) {
            if (!table.sellable(recipe.output())) {
                continue;
            }
            // What the ingredients sell for (items under a dollar sell for nothing) is never less than the result.
            long inputs = 0;
            for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                long cheapest = Long.MAX_VALUE;
                for (String option : ingredient.options()) {
                    cheapest = Math.min(cheapest, table.price(option));
                }
                inputs += cheapest * ingredient.count();
            }
            assertTrue(table.price(recipe.output()) * recipe.outputCount() <= inputs, recipe.id());
        }
    }

    @Test
    void invalidLossIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> calc(Map.of(), List.of(), 1.2));
        assertThrows(IllegalArgumentException.class, () -> calc(Map.of(), List.of(), 0));
    }
}
