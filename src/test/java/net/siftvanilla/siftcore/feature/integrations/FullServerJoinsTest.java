package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Joining a full server: read from LuckPerms at login, remembered for a minute, never without LuckPerms. */
class FullServerJoinsTest {

    private final UUID baron = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean luckPerms = new AtomicBoolean(true);

    private final RankAccess ranks = new RankAccess() {
        private final Map<UUID, Set<String>> nodes = Map.of(
            FullServerJoinsTest.this.baron, Set.of(FullServerJoins.NODE),
            FullServerJoinsTest.this.staff, Set.of(FullServerJoins.STAFF));

        @Override
        public boolean available() {
            return FullServerJoinsTest.this.luckPerms.get();
        }

        @Override
        public boolean groupExists(String group) {
            return false;
        }

        @Override
        public CompletableFuture<Held> held(UUID player, String group) {
            return CompletableFuture.completedFuture(new Held(false, null));
        }

        @Override
        public CompletableFuture<Boolean> ensure(UUID player, String group, Instant until) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> permission(UUID player, String node) {
            return CompletableFuture.completedFuture(this.nodes.getOrDefault(player, Set.of()).contains(node));
        }
    };

    private FullServerJoins joins() {
        return new FullServerJoins(() -> this.ranks, this.enabled::get, Logger.getLogger("full-test"), this.clock::get);
    }

    @Test
    void theRankAndStaffNodesLetPlayersIn() {
        FullServerJoins joins = joins();
        joins.remember(this.baron, "Baron");
        joins.remember(this.staff, "Staff");
        joins.remember(this.member, "Member");
        assertTrue(joins.letIn(this.baron));
        assertTrue(joins.letIn(this.staff));
        assertFalse(joins.letIn(this.member), "a player without the node waits like everyone else");
        assertFalse(joins.letIn(UUID.randomUUID()), "a login that was never read is not let in");
    }

    @Test
    void theAnswerOnlyLastsForTheLogin() {
        FullServerJoins joins = joins();
        joins.remember(this.baron, "Baron");
        this.clock.addAndGet(59_000);
        assertTrue(joins.letIn(this.baron));
        this.clock.addAndGet(2_000);
        assertFalse(joins.letIn(this.baron), "a minute later the answer is gone");
    }

    @Test
    void withoutLuckPermsOrTurnedOffNobodyGetsIn() {
        this.luckPerms.set(false);
        FullServerJoins joins = joins();
        joins.remember(this.baron, "Baron");
        assertFalse(joins.letIn(this.baron), "without LuckPerms the node can't be read, so it is not allowed");
        this.luckPerms.set(true);
        joins.remember(this.baron, "Baron");
        assertTrue(joins.letIn(this.baron));
        this.enabled.set(false);
        assertFalse(joins.letIn(this.baron), "join-full.enabled: false lets nobody past");
    }
}
