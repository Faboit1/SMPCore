package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The inspection GUI layout and the "take only what was shown" rule. */
class InspectTest {

    /** A stand-in for an item: type, data and amount. */
    private record Stack(String type, String data, int amount) {
        static final Stack EMPTY = new Stack("air", "", 0);

        boolean empty() {
            return this.amount <= 0 || this.type.equals("air");
        }
    }

    private static Stack take(Stack expected, Stack[] slots, int index) {
        return InspectSnapshot.takeIfUnchanged(expected, slots[index], () -> slots[index] = null, Objects::equals, Stack::empty);
    }

    @Test
    void unchangedSlotIsTakenAndEmptied() {
        Stack diamonds = new Stack("diamond", "", 5);
        Stack[] slots = {diamonds};
        assertEquals(diamonds, take(new Stack("diamond", "", 5), slots, 0));
        assertNull(slots[0]);
    }

    @Test
    void changedSlotIsLeftAlone() {
        Stack[] amountChanged = {new Stack("diamond", "", 4)};
        assertNull(take(new Stack("diamond", "", 5), amountChanged, 0));
        assertEquals(new Stack("diamond", "", 4), amountChanged[0]);

        Stack[] dataChanged = {new Stack("diamond_sword", "sharpness 5", 1)};
        assertNull(take(new Stack("diamond_sword", "", 1), dataChanged, 0));
        assertEquals("sharpness 5", dataChanged[0].data());

        Stack[] replaced = {new Stack("dirt", "", 5)};
        assertNull(take(new Stack("diamond", "", 5), replaced, 0));
        assertEquals("dirt", replaced[0].type());
    }

    @Test
    void emptySlotsGiveNothing() {
        Stack[] emptied = {null};
        assertNull(take(new Stack("diamond", "", 5), emptied, 0));
        Stack[] air = {Stack.EMPTY};
        assertNull(take(new Stack("diamond", "", 5), air, 0));
        Stack[] full = {new Stack("diamond", "", 5)};
        assertNull(take(null, full, 0));
        assertNull(take(Stack.EMPTY, full, 0));
        assertEquals(new Stack("diamond", "", 5), full[0]);
    }

    @Test
    void takingTwiceOnlyWorksOnce() {
        Stack[] slots = {new Stack("totem", "", 1)};
        Stack shown = new Stack("totem", "", 1);
        assertEquals(shown, take(shown, slots, 0));
        assertNull(take(shown, slots, 0), "a second click on the same stale view takes nothing");
    }

    @Test
    void snapshotHoldsOneEntryPerSourceSlot() {
        List<Stack> items = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            items.add(i % 3 == 0 ? new Stack("stone", "", i + 1) : null);
        }
        InspectSnapshot<Stack> snapshot = new InspectSnapshot<>(InspectLayout.Kind.INVENTORY, items, 5L);
        assertEquals(new Stack("stone", "", 1), snapshot.item(0));
        assertNull(snapshot.item(1));
        assertNull(snapshot.item(-1));
        assertNull(snapshot.item(41));
        assertEquals(14, snapshot.stacks(Stack::empty));
        assertEquals(5L, snapshot.takenAt());
        assertThrows(IllegalArgumentException.class, () -> new InspectSnapshot<>(InspectLayout.Kind.ENDER_CHEST, items, 0L));
    }

    @Test
    void inventoryLayoutMapsEverySlotOnce() {
        checkLayout(InspectLayout.Kind.INVENTORY);
        // Hotbar sits under the storage rows, armour is head to feet, then the off hand.
        assertEquals(27, InspectLayout.guiSlot(InspectLayout.Kind.INVENTORY, 0));
        assertEquals(0, InspectLayout.guiSlot(InspectLayout.Kind.INVENTORY, 9));
        assertEquals(InspectLayout.HELMET, InspectLayout.sourceIndex(InspectLayout.Kind.INVENTORY, 36));
        assertEquals(InspectLayout.BOOTS, InspectLayout.sourceIndex(InspectLayout.Kind.INVENTORY, 39));
        assertEquals(InspectLayout.OFF_HAND, InspectLayout.sourceIndex(InspectLayout.Kind.INVENTORY, 40));
        assertEquals(InspectLayout.SlotKind.HOTBAR, InspectLayout.slotKind(InspectLayout.Kind.INVENTORY, 4));
        assertEquals(InspectLayout.SlotKind.STORAGE, InspectLayout.slotKind(InspectLayout.Kind.INVENTORY, 20));
        assertEquals(InspectLayout.SlotKind.CHESTPLATE, InspectLayout.slotKind(InspectLayout.Kind.INVENTORY, 38));
        assertEquals(-1, InspectLayout.sourceIndex(InspectLayout.Kind.INVENTORY, 41));
    }

    @Test
    void enderChestLayoutMapsEverySlotOnce() {
        checkLayout(InspectLayout.Kind.ENDER_CHEST);
        assertEquals(InspectLayout.SlotKind.ENDER_CHEST, InspectLayout.slotKind(InspectLayout.Kind.ENDER_CHEST, 3));
        assertEquals(-1, InspectLayout.guiSlot(InspectLayout.Kind.ENDER_CHEST, 27));
    }

    private static void checkLayout(InspectLayout.Kind kind) {
        int size = kind.rows() * 9;
        Set<Integer> used = new HashSet<>();
        for (int index = 0; index < kind.sourceSize(); index++) {
            int slot = InspectLayout.guiSlot(kind, index);
            assertTrue(slot >= 0 && slot < size, "index " + index + " is shown inside the menu");
            assertTrue(used.add(slot), "slot " + slot + " shows one item only");
            assertEquals(index, InspectLayout.sourceIndex(kind, slot), "slot " + slot + " maps back to index " + index);
        }
        for (int button : new int[] {kind.infoSlot(), kind.switchSlot(), kind.refreshSlot(), kind.clearSlot()}) {
            assertTrue(button >= 0 && button < size, "button slot " + button + " is inside the menu");
            assertTrue(used.add(button), "button slot " + button + " is free");
            assertEquals(-1, InspectLayout.sourceIndex(kind, button));
        }
        assertEquals(kind, kind.other().other());
    }
}
