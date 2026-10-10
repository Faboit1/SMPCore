package net.siftvanilla.siftcore.feature.tpa;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Pending teleport requests. A player can have requests from many players at once, at most one from each sender
 * (a new request from the same sender replaces the old one). Each request expires on its own. Bukkit-free and
 * thread-safe (all methods synchronize on this store; every operation is a few map lookups).
 */
final class TpaRequests {

    /** Who moves. */
    enum Kind {
        /** /tpa: the sender goes to the target. */
        TO_TARGET,
        /** /tpahere: the target goes to the sender. */
        TO_SENDER
    }

    /** One request; {@code id} is unique for the life of the store so an old answer can't accept a newer request. */
    record Request(long id, UUID sender, UUID target, Kind kind, long created, long expires) {

        boolean expired(long now) {
            return now >= this.expires;
        }

        /** The player who teleports. */
        UUID mover() {
            return this.kind == Kind.TO_TARGET ? this.sender : this.target;
        }

        /** The player the mover teleports to. */
        UUID destination() {
            return this.kind == Kind.TO_TARGET ? this.target : this.sender;
        }

        Duration left(long now) {
            return Duration.ofMillis(Math.max(0, this.expires - now));
        }
    }

    /** The result of adding: the new request and the one it replaced, if any. */
    record Added(Request request, Request replaced) {
    }

    private final LongSupplier clock;
    /** target -> sender -> request, in the order they arrived. */
    private final Map<UUID, Map<UUID, Request>> byTarget = new HashMap<>();
    private long nextId = 1;

    TpaRequests(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized Added add(UUID sender, UUID target, Kind kind, Duration ttl) {
        if (sender.equals(target)) {
            throw new IllegalArgumentException("A player can't send a request to themselves");
        }
        long now = this.clock.getAsLong();
        Request request = new Request(this.nextId++, sender, target, kind, now, now + ttl.toMillis());
        Map<UUID, Request> incoming = this.byTarget.computeIfAbsent(target, k -> new LinkedHashMap<>());
        Request replaced = incoming.remove(sender);
        incoming.put(sender, request);
        return new Added(request, replaced == null || replaced.expired(now) ? null : replaced);
    }

    /** Live requests sent to {@code target}, oldest first. */
    synchronized List<Request> incoming(UUID target) {
        long now = this.clock.getAsLong();
        Map<UUID, Request> incoming = this.byTarget.get(target);
        if (incoming == null) {
            return List.of();
        }
        List<Request> live = new ArrayList<>();
        for (Request request : incoming.values()) {
            if (!request.expired(now)) {
                live.add(request);
            }
        }
        return live;
    }

    /** Live requests sent by {@code sender}, oldest first. */
    synchronized List<Request> outgoing(UUID sender) {
        long now = this.clock.getAsLong();
        List<Request> live = new ArrayList<>();
        for (Map<UUID, Request> incoming : this.byTarget.values()) {
            Request request = incoming.get(sender);
            if (request != null && !request.expired(now)) {
                live.add(request);
            }
        }
        live.sort(Comparator.comparingLong(Request::id));
        return live;
    }

    /** Removes and returns the live request from {@code sender} to {@code target}. */
    synchronized Optional<Request> take(UUID target, UUID sender) {
        return take(target, sender, -1);
    }

    /**
     * Removes and returns the live request from {@code sender} to {@code target}; with {@code id >= 0} only when it
     * is exactly that request (not a newer one that replaced it).
     */
    synchronized Optional<Request> take(UUID target, UUID sender, long id) {
        Map<UUID, Request> incoming = this.byTarget.get(target);
        if (incoming == null) {
            return Optional.empty();
        }
        Request request = incoming.get(sender);
        if (request == null || (id >= 0 && request.id() != id)) {
            return Optional.empty();
        }
        incoming.remove(sender);
        if (incoming.isEmpty()) {
            this.byTarget.remove(target);
        }
        return request.expired(this.clock.getAsLong()) ? Optional.empty() : Optional.of(request);
    }

    /** Removes and returns every expired request. */
    synchronized List<Request> expire() {
        long now = this.clock.getAsLong();
        List<Request> expired = new ArrayList<>();
        Iterator<Map<UUID, Request>> targets = this.byTarget.values().iterator();
        while (targets.hasNext()) {
            Map<UUID, Request> incoming = targets.next();
            Iterator<Request> requests = incoming.values().iterator();
            while (requests.hasNext()) {
                Request request = requests.next();
                if (request.expired(now)) {
                    expired.add(request);
                    requests.remove();
                }
            }
            if (incoming.isEmpty()) {
                targets.remove();
            }
        }
        return expired;
    }

    /** Removes and returns every request sent by or to {@code player} (they left). */
    synchronized List<Request> removeAll(UUID player) {
        List<Request> removed = new ArrayList<>();
        Map<UUID, Request> incoming = this.byTarget.remove(player);
        if (incoming != null) {
            removed.addAll(incoming.values());
        }
        Iterator<Map<UUID, Request>> targets = this.byTarget.values().iterator();
        while (targets.hasNext()) {
            Map<UUID, Request> requests = targets.next();
            Request request = requests.remove(player);
            if (request != null) {
                removed.add(request);
            }
            if (requests.isEmpty()) {
                targets.remove();
            }
        }
        return removed;
    }

    /** Removes and returns every live request sent by {@code sender}. */
    synchronized List<Request> cancelAll(UUID sender) {
        long now = this.clock.getAsLong();
        List<Request> cancelled = new ArrayList<>();
        Iterator<Map<UUID, Request>> targets = this.byTarget.values().iterator();
        while (targets.hasNext()) {
            Map<UUID, Request> requests = targets.next();
            Request request = requests.remove(sender);
            if (request != null && !request.expired(now)) {
                cancelled.add(request);
            }
            if (requests.isEmpty()) {
                targets.remove();
            }
        }
        cancelled.sort(Comparator.comparingLong(Request::id));
        return cancelled;
    }

    /** All stored requests, expired ones included (metrics and tests). */
    synchronized int size() {
        int size = 0;
        for (Map<UUID, Request> incoming : this.byTarget.values()) {
            size += incoming.size();
        }
        return size;
    }
}
