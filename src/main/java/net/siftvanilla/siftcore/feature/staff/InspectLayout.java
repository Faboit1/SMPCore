package net.siftvanilla.siftcore.feature.staff;

/**
 * Where a target's items appear in the read-only inspection GUI, and which GUI slots are buttons. Pure logic.
 * <pre>
 * Inventory (6 rows)                     Ender chest (4 rows)
 *  slots  0-26 : storage (index 9-35)     slots  0-26 : ender chest (index 0-26)
 *  slots 27-35 : hotbar (index 0-8)       slot  27    : player info
 *  slots 36-39 : helmet, chestplate,      slot  30    : switch to the inventory
 *                leggings, boots          slot  31    : refresh
 *                (index 39, 38, 37, 36)   slot  32    : clear
 *  slot  40    : off hand (index 40)
 *  slot  45    : player info
 *  slot  48    : switch to the ender chest
 *  slot  49    : refresh
 *  slot  50    : clear
 * </pre>
 * Source indexes are the ones {@code PlayerInventory#getItem(int)} and {@code Inventory#getItem(int)} use.
 */
final class InspectLayout {

    /** What part of the player is shown. */
    enum Kind {
        INVENTORY(6, 41, 45, 48, 49, 50),
        ENDER_CHEST(4, 27, 27, 30, 31, 32);

        private final int rows;
        private final int sourceSize;
        private final int infoSlot;
        private final int switchSlot;
        private final int refreshSlot;
        private final int clearSlot;

        Kind(int rows, int sourceSize, int infoSlot, int switchSlot, int refreshSlot, int clearSlot) {
            this.rows = rows;
            this.sourceSize = sourceSize;
            this.infoSlot = infoSlot;
            this.switchSlot = switchSlot;
            this.refreshSlot = refreshSlot;
            this.clearSlot = clearSlot;
        }

        int rows() {
            return this.rows;
        }

        /** Number of source indexes shown (41 for an inventory, 27 for an ender chest). */
        int sourceSize() {
            return this.sourceSize;
        }

        int infoSlot() {
            return this.infoSlot;
        }

        int switchSlot() {
            return this.switchSlot;
        }

        int refreshSlot() {
            return this.refreshSlot;
        }

        int clearSlot() {
            return this.clearSlot;
        }

        Kind other() {
            return this == INVENTORY ? ENDER_CHEST : INVENTORY;
        }
    }

    /** What a source index is, for the lore of shown items. */
    enum SlotKind {
        HOTBAR,
        STORAGE,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        OFF_HAND,
        ENDER_CHEST
    }

    static final int HELMET = 39;
    static final int CHESTPLATE = 38;
    static final int LEGGINGS = 37;
    static final int BOOTS = 36;
    static final int OFF_HAND = 40;

    private InspectLayout() {
    }

    /** The source index shown in {@code guiSlot}, or -1 when that slot shows no item of the target. */
    static int sourceIndex(Kind kind, int guiSlot) {
        if (kind == Kind.ENDER_CHEST) {
            return guiSlot >= 0 && guiSlot < 27 ? guiSlot : -1;
        }
        if (guiSlot >= 0 && guiSlot < 27) {
            return guiSlot + 9;
        }
        if (guiSlot >= 27 && guiSlot < 36) {
            return guiSlot - 27;
        }
        return switch (guiSlot) {
            case 36 -> HELMET;
            case 37 -> CHESTPLATE;
            case 38 -> LEGGINGS;
            case 39 -> BOOTS;
            case 40 -> OFF_HAND;
            default -> -1;
        };
    }

    /** The GUI slot showing {@code sourceIndex}, or -1 when it is not shown. */
    static int guiSlot(Kind kind, int sourceIndex) {
        if (kind == Kind.ENDER_CHEST) {
            return sourceIndex >= 0 && sourceIndex < 27 ? sourceIndex : -1;
        }
        if (sourceIndex >= 9 && sourceIndex < 36) {
            return sourceIndex - 9;
        }
        if (sourceIndex >= 0 && sourceIndex < 9) {
            return sourceIndex + 27;
        }
        return switch (sourceIndex) {
            case HELMET -> 36;
            case CHESTPLATE -> 37;
            case LEGGINGS -> 38;
            case BOOTS -> 39;
            case OFF_HAND -> 40;
            default -> -1;
        };
    }

    static SlotKind slotKind(Kind kind, int sourceIndex) {
        if (kind == Kind.ENDER_CHEST) {
            return SlotKind.ENDER_CHEST;
        }
        if (sourceIndex >= 0 && sourceIndex < 9) {
            return SlotKind.HOTBAR;
        }
        return switch (sourceIndex) {
            case HELMET -> SlotKind.HELMET;
            case CHESTPLATE -> SlotKind.CHESTPLATE;
            case LEGGINGS -> SlotKind.LEGGINGS;
            case BOOTS -> SlotKind.BOOTS;
            case OFF_HAND -> SlotKind.OFF_HAND;
            default -> SlotKind.STORAGE;
        };
    }
}
