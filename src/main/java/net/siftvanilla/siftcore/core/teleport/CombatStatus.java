package net.siftvanilla.siftcore.core.teleport;

import java.time.Duration;
import java.util.UUID;

/** Read-only view of combat tags, implemented by the combat feature and used by teleports and other features. */
public interface CombatStatus {

    CombatStatus NONE = new CombatStatus() {
        @Override
        public boolean tagged(UUID player) {
            return false;
        }

        @Override
        public Duration remaining(UUID player) {
            return Duration.ZERO;
        }
    };

    boolean tagged(UUID player);

    Duration remaining(UUID player);
}
