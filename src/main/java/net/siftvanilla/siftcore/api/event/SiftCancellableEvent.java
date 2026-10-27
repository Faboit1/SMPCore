package net.siftvanilla.siftcore.api.event;

import org.bukkit.event.Cancellable;

/** A SiftCore event that listeners may cancel; cancelling stops the action before anything changes. */
public abstract class SiftCancellableEvent extends SiftEvent implements Cancellable {

    private boolean cancelled;

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
