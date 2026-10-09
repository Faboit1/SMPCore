package net.siftvanilla.siftcore.core.teleport;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Messages of the shared teleport warmup. */
public final class TeleportMessages {

    public static final MessageKey WARMUP = MessageKey.status("teleport.warmup", "time");
    public static final MessageKey CANCELLED_MOVE = MessageKey.error("teleport.cancelled-move");
    public static final MessageKey CANCELLED_DAMAGE = MessageKey.error("teleport.cancelled-damage");
    public static final MessageKey CANCELLED_REPLACED = MessageKey.info("teleport.cancelled-replaced");
    public static final MessageKey IN_COMBAT = MessageKey.error("teleport.in-combat", "time");
    public static final MessageKey FROZEN = MessageKey.error("teleport.frozen");
    public static final MessageKey DONE = MessageKey.success("teleport.done");
    public static final MessageKey FAILED = MessageKey.error("teleport.failed");

    private TeleportMessages() {
    }
}
