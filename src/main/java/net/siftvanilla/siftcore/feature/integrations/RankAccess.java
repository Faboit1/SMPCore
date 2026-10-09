package net.siftvanilla.siftcore.feature.integrations;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The rank grants store delivery needs (LuckPerms when installed). */
interface RankAccess {

    RankAccess NONE = new RankAccess() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean groupExists(String group) {
            return false;
        }

        @Override
        public CompletableFuture<Held> held(UUID player, String group) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not installed"));
        }

        @Override
        public CompletableFuture<Boolean> ensure(UUID player, String group, Instant until) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not installed"));
        }

        @Override
        public CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not installed"));
        }
    };

    /** How a player holds a group: permanently, until a time, or not ({@code until} null and not permanent). */
    record Held(boolean permanent, Instant until) {
    }

    boolean available();

    boolean groupExists(String group);

    CompletableFuture<Held> held(UUID player, String group);

    /** Makes the player hold the group at least until {@code until} (null: permanently); idempotent. */
    CompletableFuture<Boolean> ensure(UUID player, String group, Instant until);

    /**
     * Takes a group back; idempotent. {@code removePermanent} removes a permanent grant. With {@code cutTimed}, timed
     * grants that end after {@code cutTo} are cut to end then, or removed when {@code cutTo} is null or past.
     */
    CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo);
}
