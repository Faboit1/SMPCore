package net.siftvanilla.siftcore.core.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The taking logic of shulker boxes and bundles, on a plain stack type (the ItemStack wrappers are self-tested). */
class ContainerItemsTest {

    /** A test stack: an item type and an amount (amount 0 is an empty position). */
    private record S(String type, int amount) {
    }

    private static final S EMPTY = new S("air", 0);

    private static final ContainerItems.Stacks<S> STACKS = new ContainerItems.Stacks<>() {
        @Override
        public boolean empty(S stack) {
            return stack == null || stack.amount() == 0;
        }

        @Override
        public int amount(S stack) {
            return stack.amount();
        }

        @Override
        public S withAmount(S stack, int amount) {
            return new S(stack.type(), amount);
        }

        @Override
        public S emptyStack() {
            return EMPTY;
        }
    };

    /** A 27-position box with the given stacks at the given positions. */
    private static List<S> box(Object... positionsAndStacks) {
        List<S> contents = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            contents.add(EMPTY);
        }
        for (int i = 0; i < positionsAndStacks.length; i += 2) {
            contents.set((Integer) positionsAndStacks[i], (S) positionsAndStacks[i + 1]);
        }
        return contents;
    }

    private static ContainerItems.Extraction<S> take(List<S> contents, String type, long max) {
        return ContainerItems.extract(contents, stack -> stack.type().equals(type), max, STACKS);
    }

    @Test
    void positionsOfEverythingElseAreKept() {
        List<S> contents = box(0, new S("diamond", 10), 4, new S("stick", 5), 13, new S("diamond", 64), 26, new S("dirt", 3));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", Long.MAX_VALUE);
        assertEquals(74, taken.units());
        assertEquals(List.of(new S("diamond", 10), new S("diamond", 64)), taken.taken());
        assertEquals(27, taken.remaining().size());
        assertEquals(new S("stick", 5), taken.remaining().get(4));
        assertEquals(new S("dirt", 3), taken.remaining().get(26));
        assertEquals(EMPTY, taken.remaining().get(0));
        assertEquals(EMPTY, taken.remaining().get(13));
        assertFalse(ContainerItems.allEmpty(taken.remaining(), STACKS));
    }

    @Test
    void aPartialTakeLeavesTheRestInPlace() {
        List<S> contents = box(2, new S("diamond", 10), 9, new S("diamond", 64));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", 30);
        assertEquals(30, taken.units());
        assertEquals(List.of(new S("diamond", 10), new S("diamond", 20)), taken.taken());
        assertEquals(EMPTY, taken.remaining().get(2));
        assertEquals(new S("diamond", 44), taken.remaining().get(9));
    }

    @Test
    void theCapStopsTakingAndZeroTakesNothing() {
        List<S> contents = box(0, new S("diamond", 5), 1, new S("diamond", 5));
        assertEquals(5, take(contents, "diamond", 5).units());
        assertEquals(new S("diamond", 5), take(contents, "diamond", 5).remaining().get(1));
        ContainerItems.Extraction<S> none = take(contents, "diamond", 0);
        assertTrue(none.nothing());
        assertEquals(contents, none.remaining());
        assertThrows(IllegalArgumentException.class, () -> take(contents, "diamond", -1));
    }

    @Test
    void nothingToTakeChangesNothing() {
        List<S> contents = box(3, new S("stick", 5), 7, new S("dirt", 1));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", 100);
        assertTrue(taken.nothing());
        assertTrue(taken.taken().isEmpty());
        assertEquals(contents, taken.remaining());
    }

    @Test
    void takingEverythingLeavesAnEmptyBoxThatGoesBackToPlain() {
        List<S> contents = box(0, new S("diamond", 64), 26, new S("diamond", 1));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", Long.MAX_VALUE);
        assertEquals(65, taken.units());
        // rebuild() resets the container component when every position is empty, so the box is plain again
        assertTrue(ContainerItems.allEmpty(taken.remaining(), STACKS));
        assertTrue(ContainerItems.compact(taken.remaining(), STACKS).isEmpty());
    }

    @Test
    void nestedContainersAreJudgedAsStacksAndNeverOpened() {
        // A bundle inside a box is one stack; the predicate decides about it as a whole and its contents stay hidden.
        List<S> contents = box(0, new S("bundle", 1), 1, new S("diamond", 2));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", 10);
        assertEquals(new S("bundle", 1), taken.remaining().getFirst());
        assertEquals(2, taken.units());
    }

    @Test
    void onlySingleContainersOpen() {
        assertTrue(ContainerItems.openable(true, 1));
        assertFalse(ContainerItems.openable(true, 2), "a stacked box is never opened");
        assertFalse(ContainerItems.openable(false, 1));
    }

    @Test
    void bundlesKeepNoEmptyPositions() {
        List<S> contents = List.of(new S("diamond", 3), new S("stick", 2), new S("diamond", 1));
        ContainerItems.Extraction<S> taken = take(contents, "diamond", 10);
        assertEquals(List.of(new S("stick", 2)), ContainerItems.compact(taken.remaining(), STACKS));
    }
}
