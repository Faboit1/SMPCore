package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StackFitTest {

    /** A stack for tests: an item kind, an amount and the kind's stack size. */
    record Stack(String kind, int amount, int max) {
    }

    private static final StackFit.Stacks<Stack> STACKS = new StackFit.Stacks<>() {
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
    void everythingFitsIntoAnEmptyInventory() {
        List<Stack> incoming = List.of(new Stack("sword", 1, 1), new Stack("bread", 16, 64), new Stack("beef", 64, 64));
        assertEquals(List.of(0, 1, 2), StackFit.plan(slots(36), incoming, STACKS));
    }

    @Test
    void stacksTopUpMatchingStacksBeforeTakingFreeSlots() {
        List<Stack> slots = slots(2, new Stack("bread", 60, 64), new Stack("dirt", 64, 64));
        assertEquals(List.of(0), StackFit.plan(slots, List.of(new Stack("bread", 4, 64)), STACKS));
        assertEquals(List.of(), StackFit.plan(slots, List.of(new Stack("bread", 5, 64)), STACKS));
    }

    @Test
    void aStackThatDoesNotFitIsSkippedAndLaterOnesMayStillFit() {
        List<Stack> slots = slots(3, new Stack("dirt", 64, 64), new Stack("bread", 60, 64));
        List<Stack> incoming = List.of(new Stack("sword", 1, 1), new Stack("helmet", 1, 1), new Stack("bread", 4, 64));
        assertEquals(List.of(0, 2), StackFit.plan(slots, incoming, STACKS));
    }

    @Test
    void earlierStacksTakeTheRoomOfLaterOnes() {
        List<Stack> slots = slots(2);
        List<Stack> incoming = List.of(new Stack("a", 1, 1), new Stack("b", 1, 1), new Stack("c", 1, 1));
        assertEquals(List.of(0, 1), StackFit.plan(slots, incoming, STACKS));
    }

    @Test
    void stacksOfTheSameKindShareTheirRoom() {
        List<Stack> slots = slots(1);
        List<Stack> incoming = List.of(new Stack("bread", 40, 64), new Stack("bread", 24, 64), new Stack("bread", 1, 64));
        assertEquals(List.of(0, 1), StackFit.plan(slots, incoming, STACKS));
    }

    @Test
    void emptyIncomingStacksAlwaysFit() {
        List<Stack> incoming = Arrays.asList(null, new Stack("air", 0, 64));
        assertEquals(List.of(0, 1), StackFit.plan(slots(0), incoming, STACKS));
    }

    @Test
    void fitsAnswersForOneStack() {
        assertTrue(StackFit.fits(slots(1), new Stack("helmet", 1, 1), STACKS));
        assertFalse(StackFit.fits(slots(1, new Stack("dirt", 1, 64)), new Stack("helmet", 1, 1), STACKS));
        assertTrue(StackFit.fits(slots(1, new Stack("dirt", 1, 64)), new Stack("dirt", 63, 64), STACKS));
    }

    @Test
    void theInventoryItselfIsNeverChanged() {
        List<Stack> slots = slots(2, new Stack("bread", 10, 64));
        List<Stack> before = new ArrayList<>(slots);
        StackFit.plan(slots, List.of(new Stack("bread", 50, 64), new Stack("sword", 1, 1)), STACKS);
        assertEquals(before, slots);
    }
}
