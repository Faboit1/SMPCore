package net.siftvanilla.siftcore.feature.spawners;

import java.util.Map;
import java.util.UUID;

/**
 * A placed SiftCore spawner: who owns it, what mob, how many are stacked, and its storage. The identity fields never
 * change. The mutable state is only changed under the economy lock (transaction applies and loot cycles hold it);
 * the scalar fields are volatile so displays and placeholders can read them without the lock. Bukkit-free.
 */
final class ManagedSpawner {

    final long id;
    final SpawnerPos pos;
    final UUID owner;
    final String mob;
    final long created;
    final Storage storage = new Storage();

    private volatile int stack;
    private volatile long xp;
    private volatile long version;
    private volatile boolean removed;
    private volatile long lastActive;
    private volatile boolean lastFull;
    /** Written under the lock: true when memory has changes the database doesn't have yet. */
    private boolean dirty;
    /** Region thread only: the activation radius the block was last set up with (-1 = not yet). */
    private int syncedRadius = -1;

    ManagedSpawner(long id, SpawnerPos pos, UUID owner, String mob, int stack, long xp, long created) {
        this.id = id;
        this.pos = pos;
        this.owner = owner;
        this.mob = mob;
        this.stack = stack;
        this.xp = xp;
        this.created = created;
    }

    int stack() {
        return this.stack;
    }

    void stack(int stack) {
        this.stack = stack;
        this.version++;
    }

    long xp() {
        return this.xp;
    }

    void xp(long xp) {
        this.xp = xp;
        this.version++;
    }

    /** Changes with every change to the stack, XP or storage (menus redraw when it moves). */
    long version() {
        return this.version;
    }

    void touch() {
        this.version++;
    }

    boolean removed() {
        return this.removed;
    }

    void removed(boolean removed) {
        this.removed = removed;
        this.version++;
    }

    boolean dirty() {
        return this.dirty;
    }

    void dirty(boolean dirty) {
        this.dirty = dirty;
    }

    long lastActive() {
        return this.lastActive;
    }

    /** Whether the last active cycle found the storage full (loot was lost). */
    boolean lastFull() {
        return this.lastFull;
    }

    void active(long now, boolean full) {
        this.lastActive = now;
        this.lastFull = full;
    }

    int syncedRadius() {
        return this.syncedRadius;
    }

    void syncedRadius(int radius) {
        this.syncedRadius = radius;
    }

    /** A consistent copy of the state; call under the economy lock. */
    State state() {
        return new State(this.stack, this.xp, this.storage.snapshot(), this.version, this.removed);
    }

    /** A copy of the mutable state taken under the lock. */
    record State(int stack, long xp, Map<String, Long> items, long version, boolean removed) {

        long used() {
            long total = 0;
            for (long amount : this.items.values()) {
                total = StorageMath.saturatingAdd(total, amount);
            }
            return total;
        }
    }
}
