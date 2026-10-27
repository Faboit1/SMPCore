package net.siftvanilla.siftcore.feature.spawners;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;

/**
 * The Full storage alert ({@code spawner-full-alert}). A spawner owes its owner an alert from the cycle its storage
 * fills until an alert naming it goes out or the storage stops being full ({@link ManagedSpawner#active}). Every
 * cycle that finds an owed storage still full asks for a delivery; one goes out when the owner is online, wants the
 * alert and has not had one in the last {@link #GAP}, and it covers every storage of theirs that is owed one (one line,
 * however many chunks they are in). So an alert the throttle or an offline owner held back is sent later instead of
 * being lost. Bukkit-free; safe from any thread.
 */
final class FullAlerts {

    /** The shortest time between two full storage alerts to one owner. */
    static final Duration GAP = Duration.ofMinutes(5);
    static final String THROTTLE = "spawners:full";

    /** The owners' side (Bukkit). */
    interface Owners {

        /** The alert style the owner chose, or null when they are offline (nothing can be delivered). */
        AlertStyle style(UUID owner);

        /** Sends the alert naming these full storages; false when it could not be delivered (they left). */
        boolean send(UUID owner, AlertStyle style, List<ManagedSpawner> full);
    }

    private final Cooldowns throttle;
    private final Function<UUID, List<ManagedSpawner>> owned;
    private final Owners owners;

    FullAlerts(Cooldowns throttle, Function<UUID, List<ManagedSpawner>> owned, Owners owners) {
        this.throttle = throttle;
        this.owned = owned;
        this.owners = owners;
    }

    /** Delivers the alerts these owners are owed, where they can be delivered now. */
    void deliver(Collection<UUID> owed) {
        for (UUID owner : owed) {
            deliver(owner);
        }
    }

    /** Delivers the alert this owner is owed if it can go out now; returns the storages it named (empty if none). */
    List<ManagedSpawner> deliver(UUID owner) {
        AlertStyle style = this.owners.style(owner);
        if (style == null || style == AlertStyle.OFF) {
            return List.of();
        }
        List<ManagedSpawner> full = new ArrayList<>();
        synchronized (this) {
            if (!this.throttle.remaining(owner, THROTTLE).isZero()) {
                return List.of();
            }
            for (ManagedSpawner spawner : this.owned.apply(owner)) {
                if (spawner.takeAlert()) {
                    full.add(spawner);
                }
            }
            if (full.isEmpty()) {
                return List.of();
            }
            this.throttle.start(owner, THROTTLE, GAP);
        }
        if (!this.owners.send(owner, style, full)) {
            for (ManagedSpawner spawner : full) {
                spawner.oweAlert();
            }
            this.throttle.clear(owner, THROTTLE);
            return List.of();
        }
        return full;
    }
}
