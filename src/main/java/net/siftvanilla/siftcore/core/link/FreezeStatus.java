package net.siftvanilla.siftcore.core.link;

import java.util.UUID;

/**
 * Whether staff froze a player. Implemented by the staff feature, which also blocks a frozen player's commands,
 * movement and interactions. The shared teleports and the dialog router consult it, so a frozen player can't
 * teleport away or use menus (the pause-menu hub, dialogs from chat) to pay, trade or move. Thread-safe, lock-free and
 * cheap.
 */
public interface FreezeStatus {

    FreezeStatus NONE = player -> false;

    boolean frozen(UUID player);
}
