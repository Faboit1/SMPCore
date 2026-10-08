package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalePlanTest {

    private static SalePlan.Line line(String item, long amount, long price) {
        return new SalePlan.Line("minecraft:" + item, amount, price);
    }

    @Test
    void stacksOfOneItemMergeInTheOrderTheyWereFound() {
        SalePlan plan = SalePlan.of(List.of(line("diamond", 64, 400), line("iron_ingot", 32, 25), line("diamond", 10, 400)));
        assertEquals(List.of(line("diamond", 74, 400), line("iron_ingot", 32, 25)), plan.lines());
        assertEquals(74 * 400 + 32 * 25, plan.baseTotal());
        assertEquals(106, plan.itemCount());
        assertFalse(plan.singleKind());
        assertFalse(plan.isEmpty());
        assertEquals("74 minecraft:diamond, 32 minecraft:iron_ingot", plan.note());
    }

    @Test
    void anEmptySaleIsEmpty() {
        SalePlan plan = SalePlan.of(List.of());
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.baseTotal());
        assertEquals(0, plan.total(1.5));
        assertEquals("", plan.note());
    }

    @Test
    void theMultiplierAppliesToTheWholeSaleRoundedDown() {
        // $10 at 1.35 is exactly $13.50, paid as $13
        SalePlan plan = SalePlan.of(List.of(line("dirt", 7, 1), line("cobblestone", 3, 1)));
        assertEquals(10, plan.baseTotal());
        assertEquals(13, plan.total(1.35));
        assertEquals(15, plan.total(1.5));
        assertEquals(10, plan.total(1.0));
    }

    @Test
    void oneItemWithTwoPricesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SalePlan.of(List.of(line("diamond", 1, 400), line("diamond", 1, 401))));
    }

    @Test
    void linesNeedAnAmountAndAPrice() {
        assertThrows(IllegalArgumentException.class, () -> line("diamond", 0, 400));
        assertThrows(IllegalArgumentException.class, () -> line("diamond", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> line("diamond", -1, 400));
    }

    @Test
    void totalsThatDoNotFitAreReportedNotWrapped() {
        assertThrows(ArithmeticException.class, () -> SalePlan.of(List.of(line("nether_star", Long.MAX_VALUE / 2, 3))));
        assertThrows(ArithmeticException.class,
            () -> SalePlan.of(List.of(line("diamond", Long.MAX_VALUE - 1, 1), line("diamond", 5, 1))));
        assertThrows(ArithmeticException.class,
            () -> SalePlan.of(List.of(line("diamond", Long.MAX_VALUE / 2, 1), line("emerald", Long.MAX_VALUE / 2 + 5, 1))));
    }

    @Test
    void longNotesAreCutWithACountOfWhatDidNotFit() {
        List<SalePlan.Line> stacks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            stacks.add(line("item_number_" + i, i + 1, 2));
        }
        String note = SalePlan.of(stacks).note();
        assertTrue(note.length() <= SalePlan.NOTE_LIMIT, note.length() + " characters");
        assertTrue(note.startsWith("1 minecraft:item_number_0, 2 minecraft:item_number_1"), note);
        int shown = note.split("minecraft:", -1).length - 1;
        String more = note.substring(note.lastIndexOf(", +") + 3);
        assertTrue(more.endsWith(" more"), note);
        int hidden = Integer.parseInt(more.substring(0, more.indexOf(' ')));
        assertEquals(40, shown + hidden, note);
    }

    @Test
    void aNoteThatFitsExactlyIsNotCut() {
        // each part is "1 minecraft:x" style; build lines until the plain note is just under the limit
        List<SalePlan.Line> stacks = new ArrayList<>();
        StringBuilder expected = new StringBuilder();
        int i = 0;
        while (true) {
            String part = (i == 0 ? "" : ", ") + "1 minecraft:i" + i;
            if (expected.length() + part.length() > SalePlan.NOTE_LIMIT) {
                break;
            }
            expected.append(part);
            stacks.add(line("i" + i, 1, 1));
            i++;
        }
        assertEquals(expected.toString(), SalePlan.of(stacks).note());
    }
}
