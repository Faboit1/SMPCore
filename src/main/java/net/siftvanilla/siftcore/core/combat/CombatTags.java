package net.siftvanilla.siftcore.core.combat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;

/**
 * Who is in combat and until when. Pure state, written by the combat feature and read by everything that must
 * refuse actions in combat (teleports, menus, AFK zone). Thread-safe.
 */
public final class CombatTags implements CombatStatus {

    /** A tag: until when, and who last hit the player (for kill credit on logout). */
    public record Tag(long until, UUID lastAttacker) {
    }

    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();

    /** Tags (or refreshes) a player. Returns true if they were not tagged before. */
    public boolean tag(UUID player, UUID attacker, Duration duration) {
        long until = System.currentTimeMillis() + duration.toMillis();
        Tag previous = this.tags.put(player, new Tag(until, attacker));
        return previous == null || previous.until() <= System.currentTimeMillis();
    }

    public void untag(UUID player) {
        this.tags.remove(player);
    }

    public Tag get(UUID player) {
        Tag tag = this.tags.get(player);
        if (tag == null) {
            return null;
        }
        if (tag.until() <= System.currentTimeMillis()) {
            this.tags.remove(player, tag);
            return null;
        }
        return tag;
    }

    @Override
    public boolean tagged(UUID player) {
        return get(player) != null;
    }

    @Override
    public Duration remaining(UUID player) {
        Tag tag = get(player);
        return tag == null ? Duration.ZERO : Duration.ofMillis(Math.max(0, tag.until() - System.currentTimeMillis()));
    }

    /** Players currently tagged (for the action-bar timer). */
    public Map<UUID, Tag> snapshot() {
        return Map.copyOf(this.tags);
    }

    public int size() {
        return this.tags.size();
    }
}
