package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bans and mutes currently in force, one of each per player at most. Reads are lock-free and safe from any
 * thread (the async chat thread and the login thread read it on every message and login); callers that combine a
 * read with a write hold their own lock.
 */
final class PunishmentBook {

    private final Map<PunishmentType, Map<UUID, Punishment>> current = new EnumMap<>(PunishmentType.class);

    PunishmentBook() {
        for (PunishmentType type : PunishmentType.values()) {
            if (type.lasting()) {
                this.current.put(type, new ConcurrentHashMap<>());
            }
        }
    }

    private Map<UUID, Punishment> map(PunishmentType type) {
        Map<UUID, Punishment> map = this.current.get(type);
        if (map == null) {
            throw new IllegalArgumentException(type + " is not a lasting punishment");
        }
        return map;
    }

    /** Replaces everything with {@code punishments} (startup). Entries that are not active at {@code now} are skipped. */
    void load(Collection<Punishment> punishments, long now) {
        for (Map<UUID, Punishment> map : this.current.values()) {
            map.clear();
        }
        for (Punishment punishment : punishments) {
            if (punishment.type().lasting() && punishment.active(now)) {
                Punishment previous = map(punishment.type()).get(punishment.target());
                if (previous == null || previous.created() <= punishment.created()) {
                    map(punishment.type()).put(punishment.target(), punishment);
                }
            }
        }
    }

    /** The player's punishment of that type if it is in force at {@code now}. */
    Optional<Punishment> active(PunishmentType type, UUID target, long now) {
        Punishment punishment = map(type).get(target);
        return punishment != null && punishment.active(now) ? Optional.of(punishment) : Optional.empty();
    }

    /** Stores a new punishment and returns the one it replaces, if any (active or not). */
    Optional<Punishment> put(Punishment punishment) {
        return Optional.ofNullable(map(punishment.type()).put(punishment.target(), punishment));
    }

    /** Removes the player's punishment of that type and returns it, if any. */
    Optional<Punishment> remove(PunishmentType type, UUID target) {
        return Optional.ofNullable(map(type).remove(target));
    }

    /** Removes and returns every punishment that is no longer in force at {@code now}. */
    List<Punishment> expire(long now) {
        List<Punishment> ended = new ArrayList<>();
        for (Map<UUID, Punishment> map : this.current.values()) {
            for (Punishment punishment : map.values()) {
                if (!punishment.active(now) && map.remove(punishment.target(), punishment)) {
                    ended.add(punishment);
                }
            }
        }
        return ended;
    }

    /** Number of punishments of that type in force at {@code now}. */
    int count(PunishmentType type, long now) {
        int count = 0;
        for (Punishment punishment : map(type).values()) {
            if (punishment.active(now)) {
                count++;
            }
        }
        return count;
    }
}
