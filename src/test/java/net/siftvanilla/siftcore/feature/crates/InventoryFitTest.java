package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryFitTest {

    private record Stack(String kind, int amount, int max) {
    }

    private static final InventoryFit.Stacks<Stack> STACKS = new InventoryFit.Stacks<>() {
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
    };

    private static List<Stack> slots(int size, Stack... filled) {
        List<Stack> slots = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < filled.length; i++) {
            slots.set(i, filled[i]);
        }
        return slots;
    }

    @Test
    void emptySlotsTakeStacks() {
        assertTrue(InventoryFit.fitsAll(slots(2), List.of(new Stack("diamond", 64, 64), new Stack("dirt", 10, 64)), STACKS));
        assertFalse(InventoryFit.fitsAll(slots(1), List.of(new Stack("diamond", 64, 64), new Stack("dirt", 10, 64)), STACKS));
    }

    @Test
    void matchingStacksWithRoomAreFilledFirst() {
        List<Stack> full = slots(2, new Stack("diamond", 60, 64), new Stack("stone", 64, 64));
        assertTrue(InventoryFit.fitsAll(full, List.of(new Stack("diamond", 4, 64)), STACKS));
        assertFalse(InventoryFit.fitsAll(full, List.of(new Stack("diamond", 5, 64)), STACKS));
    }

    @Test
    void stacksPlannedEarlierTakeTheirSpace() {
        List<Stack> one = slots(1);
        assertFalse(InventoryFit.fitsAll(one, List.of(new Stack("sword", 1, 1), new Stack("sword", 1, 1)), STACKS));
        assertTrue(InventoryFit.fitsAll(one, List.of(new Stack("diamond", 30, 64), new Stack("diamond", 30, 64)), STACKS));
        assertFalse(InventoryFit.fitsAll(one, List.of(new Stack("diamond", 40, 64), new Stack("diamond", 30, 64)), STACKS));
    }

    @Test
    void largeAmountsSpreadOverSeveralSlots() {
        assertTrue(InventoryFit.fitsAll(slots(3), List.of(new Stack("arrow", 192, 64)), STACKS));
        assertFalse(InventoryFit.fitsAll(slots(2), List.of(new Stack("arrow", 192, 64)), STACKS));
    }

    @Test
    void theInputIsNotChanged() {
        Stack[] content = {new Stack("diamond", 10, 64), null};
        List<Stack> slots = Arrays.asList(content.clone());
        InventoryFit.fitsAll(slots, List.of(new Stack("diamond", 50, 64)), STACKS);
        assertTrue(slots.get(1) == null && slots.get(0).amount() == 10);
    }
}
