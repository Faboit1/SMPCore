package net.siftvanilla.siftcore.feature.afk;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * The AFK zone as other screens show it (the shards page of the main menu): whether it is open, what it pays this
 * player, today's progress, and a way to go there. Implemented by the AFK feature ({@link AfkFeature#zone()}).
 */
public interface AfkZoneInfo {

    AfkZoneInfo NONE = new AfkZoneInfo() {
        @Override
        public boolean open() {
            return false;
        }

        @Override
        public boolean inside(UUID player) {
            return false;
        }

        @Override
        public long shardsPerInterval(Player player) {
            return 0;
        }

        @Override
        public Duration interval() {
            return Duration.ZERO;
        }

        @Override
        public long earnedToday(UUID player) {
            return 0;
        }

        @Override
        public long dailyCap() {
            return 0;
        }

        @Override
        public boolean mayTeleport(Player player) {
            return false;
        }

        @Override
        public void teleport(Player player) {
        }
    };

    /** True when the zone is turned on and its world is loaded. Safe from any thread. */
    boolean open();

    /** Whether the player is in the zone right now. Safe from any thread. */
    boolean inside(UUID player);

    /** Shards the player earns per interval (their best rank tier). Call on the player's thread. */
    long shardsPerInterval(Player player);

    /** How long a player has to stay for one reward. */
    Duration interval();

    /** Shards the player earned in the zone today. Safe from any thread. */
    long earnedToday(UUID player);

    /** The daily limit per account, 0 when there is none. */
    long dailyCap();

    /** Whether the player may use /afkzone. */
    boolean mayTeleport(Player player);

    /** Sends the player to the zone (warmup, cooldown and combat rules apply). Call on the player's thread. */
    void teleport(Player player);
}
