package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import org.junit.jupiter.api.Test;

class SpawnerRegistryTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static ManagedSpawner spawner(long id, int x, int z, UUID owner, String mob) {
        return new ManagedSpawner(id, new SpawnerPos("world", x, 64, z), owner, mob, 1, 0, 0);
    }

    @Test
    void indexesFollowAddsAndRemoves() {
        SpawnerRegistry registry = new SpawnerRegistry();
        ManagedSpawner a = spawner(1, 0, 0, ALICE, "zombie");
        ManagedSpawner b = spawner(2, 5, 5, ALICE, "blaze");
        ManagedSpawner c = spawner(3, 40, 0, BOB, "zombie");
        registry.add(a);
        registry.add(b);
        registry.add(c);
        assertNull(registry.verify());
        assertSame(a, registry.at(new SpawnerPos("world", 0, 64, 0)));
        assertSame(c, registry.byId(3));
        assertEquals(2, registry.countInChunk(new SpawnerPos.ChunkKey("world", 0, 0)));
        assertEquals(List.of(b, a), registry.ownedBy(ALICE), "owned spawners sort by mob, then id");
        assertEquals(2, registry.countBy(ALICE));
        assertEquals(2, registry.stackedBy(ALICE));
        assertEquals(2, registry.owners());
        assertEquals(Set.of(new SpawnerPos.ChunkKey("world", 0, 0), new SpawnerPos.ChunkKey("world", 2, 0)), registry.chunks());

        registry.remove(a);
        registry.remove(b);
        assertNull(registry.verify());
        assertNull(registry.at(new SpawnerPos("world", 0, 64, 0)));
        assertFalse(registry.hasChunk(new SpawnerPos.ChunkKey("world", 0, 0)));
        assertEquals(List.of(), registry.ownedBy(ALICE));
        assertEquals(1, registry.owners());
        assertEquals(1, registry.size());
    }

    @Test
    void negativeCoordinatesMapToTheRightChunk() {
        assertEquals(new SpawnerPos.ChunkKey("world", -1, -2), new SpawnerPos("world", -1, 10, -17).chunk());
        assertEquals(new SpawnerPos.ChunkKey("world", 0, 1), new SpawnerPos("world", 15, 10, 16).chunk());
        assertEquals(1.0, new SpawnerPos("world", 0, 0, 0).distanceSquared(0.5, 0.5, 1.5), 1e-9);
        assertEquals("3, -4, 5", new SpawnerPos("world", 3, -4, 5).coordinates());
    }

    @Test
    void loadedChunksAndDueCycles() {
        SpawnerRegistry registry = new SpawnerRegistry();
        SpawnerPos.ChunkKey first = new SpawnerPos.ChunkKey("world", 0, 0);
        SpawnerPos.ChunkKey second = new SpawnerPos.ChunkKey("world", 2, 0);
        registry.loaded(first, 1_000);
        assertFalse(registry.isLoaded(first), "a chunk without spawners is not tracked");
        registry.add(spawner(1, 0, 0, ALICE, "zombie"));
        registry.add(spawner(2, 40, 0, ALICE, "zombie"));
        registry.loaded(first, 1_000);
        registry.loaded(second, 5_000);
        assertEquals(2, registry.loadedCount());
        assertEquals(List.of(first), registry.due(2_000, 30_000, false));
        assertEquals(List.of(), registry.due(2_000, 30_000, false), "the next cycle moved 30s ahead");
        assertEquals(2, registry.due(2_000, 30_000, true).size());
        registry.unloaded(first);
        assertFalse(registry.isLoaded(first));
        ManagedSpawner only = registry.byId(2);
        registry.remove(only);
        assertFalse(registry.isLoaded(second), "removing the last spawner forgets the chunk");
        assertNull(registry.verify());
    }

    @Test
    void verifyFindsRemovedSpawners() {
        SpawnerRegistry registry = new SpawnerRegistry();
        ManagedSpawner a = spawner(1, 0, 0, ALICE, "zombie");
        registry.add(a);
        a.removed(true);
        assertTrue(registry.verify().contains("removed"));
    }

    @Test
    void accessRules() {
        TeamLookup teams = new TeamLookup() {
            @Override
            public Optional<Long> team(UUID player) {
                return player.equals(ALICE) || player.equals(BOB) ? Optional.of(7L) : Optional.empty();
            }

            @Override
            public Optional<String> teamName(UUID player) {
                return team(player).map(id -> "Seven");
            }

            @Override
            public boolean friendlyFire(long team) {
                return false;
            }

            @Override
            public Set<UUID> members(long team) {
                return Set.of(ALICE, BOB);
            }
        };
        assertEquals(Access.OWNER, Access.of(ALICE, ALICE, false, teams));
        assertEquals(Access.TEAM, Access.of(ALICE, BOB, false, teams));
        assertEquals(Access.DENIED, Access.of(ALICE, CARA, false, teams));
        assertEquals(Access.BYPASS, Access.of(ALICE, CARA, true, teams));
        assertEquals(Access.DENIED, Access.of(ALICE, BOB, false, TeamLookup.NONE));
        assertTrue(Access.BYPASS.allowed());
        assertFalse(Access.DENIED.allowed());
    }

    @Test
    void stateIsAConsistentCopy() {
        ManagedSpawner spawner = spawner(9, 0, 0, ALICE, "zombie");
        spawner.storage.add("minecraft:rotten_flesh", 30);
        spawner.xp(40);
        long version = spawner.version();
        ManagedSpawner.State state = spawner.state();
        spawner.storage.add("minecraft:rotten_flesh", 5);
        spawner.touch();
        assertEquals(30, state.used());
        assertEquals(40, state.xp());
        assertEquals(version, state.version());
        assertTrue(spawner.version() > version);
        spawner.stack(3);
        assertEquals(1, state.stack());
        assertEquals(3, spawner.stack());
    }
}
