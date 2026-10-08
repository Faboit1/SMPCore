package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Every placed spawner, in memory, indexed by id, block, chunk and owner, plus which of those chunks are loaded and
 * when each loaded chunk's next loot cycle is due. The maps are concurrent so any thread can look things up; adding
 * and removing spawners happens inside transaction applies (under the economy lock) or at startup. Bukkit-free.
 */
final class SpawnerRegistry {

    private final Map<Long, ManagedSpawner> byId = new ConcurrentHashMap<>();
    private final Map<SpawnerPos, ManagedSpawner> byPos = new ConcurrentHashMap<>();
    private final Map<SpawnerPos.ChunkKey, Set<ManagedSpawner>> byChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ManagedSpawner>> byOwner = new ConcurrentHashMap<>();
    /** Loaded chunks that have spawners, with the time (epoch millis) their next cycle is due. */
    private final Map<SpawnerPos.ChunkKey, Long> loaded = new ConcurrentHashMap<>();

    void add(ManagedSpawner spawner) {
        this.byId.put(spawner.id, spawner);
        this.byPos.put(spawner.pos, spawner);
        this.byChunk.computeIfAbsent(spawner.pos.chunk(), k -> ConcurrentHashMap.newKeySet()).add(spawner);
        this.byOwner.computeIfAbsent(spawner.owner, k -> ConcurrentHashMap.newKeySet()).add(spawner);
    }

    void remove(ManagedSpawner spawner) {
        this.byId.remove(spawner.id, spawner);
        this.byPos.remove(spawner.pos, spawner);
        SpawnerPos.ChunkKey chunk = spawner.pos.chunk();
        this.byChunk.computeIfPresent(chunk, (k, set) -> {
            set.remove(spawner);
            return set.isEmpty() ? null : set;
        });
        if (!this.byChunk.containsKey(chunk)) {
            this.loaded.remove(chunk);
        }
        this.byOwner.computeIfPresent(spawner.owner, (k, set) -> {
            set.remove(spawner);
            return set.isEmpty() ? null : set;
        });
    }

    ManagedSpawner byId(long id) {
        return this.byId.get(id);
    }

    ManagedSpawner at(SpawnerPos pos) {
        return this.byPos.get(pos);
    }

    /** The spawners of a chunk (a snapshot). */
    List<ManagedSpawner> inChunk(SpawnerPos.ChunkKey chunk) {
        Set<ManagedSpawner> set = this.byChunk.get(chunk);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    int countInChunk(SpawnerPos.ChunkKey chunk) {
        Set<ManagedSpawner> set = this.byChunk.get(chunk);
        return set == null ? 0 : set.size();
    }

    /** The spawners a player owns, sorted by mob then id (a snapshot). */
    List<ManagedSpawner> ownedBy(UUID owner) {
        Set<ManagedSpawner> set = this.byOwner.get(owner);
        if (set == null) {
            return List.of();
        }
        List<ManagedSpawner> list = new ArrayList<>(set);
        list.sort(Comparator.comparing((ManagedSpawner s) -> s.mob).thenComparingLong(s -> s.id));
        return list;
    }

    Collection<ManagedSpawner> all() {
        return this.byId.values();
    }

    int size() {
        return this.byId.size();
    }

    int owners() {
        return this.byOwner.size();
    }

    /** Every chunk that has spawners. */
    Set<SpawnerPos.ChunkKey> chunks() {
        return this.byChunk.keySet();
    }

    boolean hasChunk(SpawnerPos.ChunkKey chunk) {
        return this.byChunk.containsKey(chunk);
    }

    /** Marks a chunk with spawners as loaded; its first cycle is due at {@code firstCycle}. */
    void loaded(SpawnerPos.ChunkKey chunk, long firstCycle) {
        if (this.byChunk.containsKey(chunk)) {
            this.loaded.putIfAbsent(chunk, firstCycle);
        }
    }

    void unloaded(SpawnerPos.ChunkKey chunk) {
        this.loaded.remove(chunk);
    }

    boolean isLoaded(SpawnerPos.ChunkKey chunk) {
        return this.loaded.containsKey(chunk);
    }

    int loadedCount() {
        return this.loaded.size();
    }

    /**
     * The loaded chunks whose cycle is due at {@code now} (or every loaded chunk when {@code all}); each one's next
     * cycle is moved to {@code now + interval}.
     */
    List<SpawnerPos.ChunkKey> due(long now, long intervalMillis, boolean all) {
        List<SpawnerPos.ChunkKey> due = new ArrayList<>();
        for (Map.Entry<SpawnerPos.ChunkKey, Long> entry : this.loaded.entrySet()) {
            if (all || entry.getValue() <= now) {
                if (this.loaded.replace(entry.getKey(), entry.getValue(), now + intervalMillis)) {
                    due.add(entry.getKey());
                }
            }
        }
        return due;
    }

    /** Sum of stacks of a player's spawners. */
    long stackedBy(UUID owner) {
        return sumBy(owner, ManagedSpawner::stack);
    }

    /** Sum of a value over a player's spawners (no copy, no sorting: cheap enough for placeholders). */
    long sumBy(UUID owner, ToLongFunction<ManagedSpawner> value) {
        Set<ManagedSpawner> set = this.byOwner.get(owner);
        long total = 0;
        if (set != null) {
            for (ManagedSpawner spawner : set) {
                total += value.applyAsLong(spawner);
            }
        }
        return total;
    }

    int countBy(UUID owner) {
        Set<ManagedSpawner> set = this.byOwner.get(owner);
        return set == null ? 0 : set.size();
    }

    /** Index consistency, for the self-test: every index agrees with the id index. Returns null when consistent. */
    String verify() {
        int chunkTotal = 0;
        for (Set<ManagedSpawner> set : this.byChunk.values()) {
            chunkTotal += set.size();
        }
        int ownerTotal = 0;
        for (Set<ManagedSpawner> set : this.byOwner.values()) {
            ownerTotal += set.size();
        }
        if (this.byPos.size() != this.byId.size() || chunkTotal != this.byId.size() || ownerTotal != this.byId.size()) {
            return "index sizes differ: ids " + this.byId.size() + ", blocks " + this.byPos.size() + ", chunks " + chunkTotal
                + ", owners " + ownerTotal;
        }
        for (ManagedSpawner spawner : this.byId.values()) {
            if (this.byPos.get(spawner.pos) != spawner) {
                return "spawner " + spawner.id + " is not indexed at its block";
            }
            Set<ManagedSpawner> chunk = this.byChunk.get(spawner.pos.chunk());
            if (chunk == null || !chunk.contains(spawner)) {
                return "spawner " + spawner.id + " is not indexed in its chunk";
            }
            if (spawner.removed()) {
                return "spawner " + spawner.id + " is removed but still indexed";
            }
        }
        for (SpawnerPos.ChunkKey chunk : this.loaded.keySet()) {
            if (!this.byChunk.containsKey(chunk)) {
                return "chunk " + chunk + " is tracked as loaded but has no spawners";
            }
        }
        return null;
    }
}
