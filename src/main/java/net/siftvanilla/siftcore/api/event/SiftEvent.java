package net.siftvanilla.siftcore.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * Base of SiftCore's events. An event is synchronous when fired from a world thread (the main thread on Paper, a
 * region thread on Folia) and asynchronous otherwise, so listeners must not assume either; use
 * {@link #isAsynchronous()} and the entity/region schedulers before touching world state.
 */
public abstract class SiftEvent extends Event {

    protected SiftEvent() {
        super(!Bukkit.isPrimaryThread());
    }
}
