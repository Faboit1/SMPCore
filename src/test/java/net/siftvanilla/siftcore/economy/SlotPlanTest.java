package net.siftvanilla.siftcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which claimed stacks fit an inventory, planned the way the game adds items (on a plain stack type). */
class SlotPlanTest {

    /** A test stack: item type, amount, max stack size. */
    private record S(String type, int amount, int max) {
    }

    private static final SlotPlan.Stacks<S> STACKS = new SlotPlan.Stacks<>() {
        @Override
        public boolean empty(S stack) {
            return stack == null || stack.amount() <= 0;
        }

        @Override
        public boolean similar(S a, S b) {
            return a.type().equals(b.type());
        }

        @Override
        public int amount(S stack) {
            return stack.amount();
        }

        @Override
        public int maxStack(S stack) {
            return stack.max();
        }
    };

    private static S stone(int amount) {
        return new S("stone", amount, 64);
    }

    private static List<S> slots(int size, S... filled) {
        List<S> slots = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < filled.length; i++) {
            slots.set(i, filled[i]);
        }
        return slots;
    }

    @Test
    void everythingFitsAnEmptyInventory() {
        assertEquals(List.of(0, 1, 2), SlotPlan.fitting(slots(3), List.of(stone(64), stone(64), stone(64)), STACKS));
    }

    @Test
    void partialStacksTakeTheirShareFirst() {
        // A purchase split to what fits right now (34 onto the stack of 30, 64 into the empty slot) is claimed whole.
        List<S> inventory = slots(2, stone(30));
        assertEquals(List.of(0, 1), SlotPlan.fitting(inventory, List.of(stone(64), stone(34)), STACKS));
        assertEquals(List.of(0), SlotPlan.fitting(inventory, List.of(stone(64), stone(35)), STACKS));
    }

    @Test
    void aStackThatDoesNotFitIsSkippedNotSplit() {
        List<S> inventory = slots(2, new S("dirt", 64, 64));
        List<S> incoming = List.of(stone(64), stone(10), new S("dirt", 5, 64));
        assertEquals(List.of(0), SlotPlan.fitting(inventory, incoming, STACKS), "the second stone stack and the dirt find no room");
    }

    @Test
    void laterSmallerStacksStillFitAfterASkippedOne() {
        List<S> inventory = slots(2, stone(60), new S("sword", 1, 1));
        assertEquals(List.of(1), SlotPlan.fitting(inventory, List.of(stone(10), stone(4)), STACKS));
    }

    @Test
    void unstackableItemsNeedOneSlotEach() {
        S sword = new S("sword", 1, 1);
        assertEquals(List.of(0, 1), SlotPlan.fitting(slots(2), List.of(sword, sword, sword), STACKS));
    }

    @Test
    void stacksTakeSpaceFromTheOnesAfterThem() {
        List<S> inventory = slots(3, stone(64), stone(64));
        assertEquals(List.of(0), SlotPlan.fitting(inventory, List.of(new S("dirt", 64, 64), new S("sand", 1, 64)), STACKS));
    }

    @Test
    void emptyIncomingCountsAsFitting() {
        assertEquals(List.of(0, 1), SlotPlan.fitting(slots(0), Arrays.asList(null, stone(0)), STACKS));
    }
}
