package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.FriendAddEvent;
import net.siftvanilla.siftcore.api.event.FriendRemoveEvent;
import net.siftvanilla.siftcore.api.event.FriendRequestEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.storage.SqlWork;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Every friends action, from the click or command to the message. An action starts on the acting player's thread
 * with the checks memory can answer (loading, self, vanish, account age, the in-flight guard, the rate buckets),
 * fires its cancellable event there, then runs one write unit ({@link FriendStore}) that checks the stored state and
 * changes it atomically. When the unit has committed, memory takes the committed pair state, and only then is anyone
 * told: nothing is said or audited for a change that did not happen.
 * <p>
 * Actions return a {@link Reply} future. From a command every message goes to chat or the action bar; from a dialog a
 * refusal is only returned (the dialog shows it inside itself) while successes are still sent.
 */
final class FriendService {

    /** Where an action came from, which decides how a refusal is shown. */
    enum Via {
        /** A command: every message is sent. */
        COMMAND,
        /** A dialog button: refusals are returned for the dialog to show; successes are sent. */
        DIALOG
    }

    /**
     * How an action ended.
     *
     * @param ok      whether it did what the player asked
     * @param message the refusal (or success) text, null when nothing needs showing
     */
    record Reply(boolean ok, Component message) {

        static final Reply SILENT_REFUSAL = new Reply(false, null);
    }

    /** The UUID staff actions are guarded under when the console runs them. */
    static final UUID CONSOLE = new UUID(0L, 0L);

    static final String BYPASS_RATE = "siftcore.bypass.cooldown";

    private final Services services;
    private final Setting<FriendsSettings> settings;
    private final FriendGraph graph;
    private final FriendStore store;
    private final FriendPrefs prefs;
    private final FriendLinks links;
    private final RateLimiter rates;
    private final InFlight inFlight;
    private final RequestAlerts alerts;
    private final Messenger messenger;
    private final Lang lang;
    private final PlayerDirectory directory;
    private final Logger logger;

    FriendService(Services services, Setting<FriendsSettings> settings, FriendGraph graph, FriendStore store,
                  FriendPrefs prefs, FriendLinks links, RateLimiter rates, InFlight inFlight, RequestAlerts alerts) {
        this.services = services;
        this.settings = settings;
        this.graph = graph;
        this.store = store;
        this.prefs = prefs;
        this.links = links;
        this.rates = rates;
        this.inFlight = inFlight;
        this.alerts = alerts;
        this.messenger = services.messenger();
        this.lang = services.lang();
        this.directory = services.directory();
        this.logger = services.plugin().getLogger();
    }

    // ------------------------------------------------------------------ reads (memory, any thread)

    FriendGraph graph() {
        return this.graph;
    }

    FriendStore store() {
        return this.store;
    }

    FriendPrefs prefs() {
        return this.prefs;
    }

    FriendLinks links() {
        return this.links;
    }

    InFlight inFlight() {
        return this.inFlight;
    }

    FriendsSettings settings() {
        return this.settings.get();
    }

    String name(UUID player) {
        return this.directory.name(player);
    }

    long now() {
        return System.currentTimeMillis();
    }

    /** The player's friend limit right now (rank, default and hard cap), from memory: the default when not loaded. */
    int limit(UUID player) {
        FriendGraph.Node node = this.graph.node(player);
        return this.settings.get().limit(node == null ? 0 : node.rankLimit());
    }

    /**
     * The player's friend limit as the write units check it: from memory while loaded, otherwise from the rank limit
     * stored in {@code friend_profiles} (an offline player keeps the limit of their rank).
     */
    int limit(UUID player, int storedRankLimit) {
        FriendGraph.Node node = this.graph.node(player);
        return this.settings.get().limit(node == null ? storedRankLimit : node.rankLimit());
    }

    /** Whether ranks can still raise a limit (it is below the hard cap). */
    boolean rankCanRaise(int limit) {
        return limit < this.settings.get().hardCap();
    }

    /**
     * The requests the player can see, sender to time sent, newest first: pending, not run out, and not from a player
     * they ignore (checked again here because the ignore may be newer than the request).
     */
    Map<UUID, Long> incoming(UUID player) {
        Map<UUID, Long> raw = this.graph.incoming(player, this.settings.get().rules().expiredBefore(now()));
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : raw.entrySet()) {
            if (!this.links.ignores().ignores(player, entry.getKey())) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<UUID, Long> result = new LinkedHashMap<>();
        entries.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** The requests the player sent and still sees as waiting (hidden ones look the same), newest first. */
    Map<UUID, Long> outgoing(UUID player) {
        Map<UUID, Long> raw = this.graph.outgoing(player, this.settings.get().rules().expiredBefore(now()));
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(raw.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<UUID, Long> result = new LinkedHashMap<>();
        entries.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    // ------------------------------------------------------------------ requests

    /** {@code sender} asks {@code target} to be friends. Call on the sender's thread. */
    CompletableFuture<Reply> request(Player sender, UUID target, Via via) {
        UUID self = sender.getUniqueId();
        String targetName = name(target);
        if (self.equals(target)) {
            return refuse(sender, via, FriendsMessages.REQUEST_SELF);
        }
        if (!this.graph.isLoaded(self)) {
            return refuse(sender, via, FriendsMessages.LOADING);
        }
        FriendsSettings s = this.settings.get();
        if (s.blockWhileVanished() && this.links.vanish().vanished(self)) {
            return refuse(sender, via, FriendsMessages.REQUEST_VANISHED);
        }
        long now = now();
        long firstJoin = this.directory.get(self).map(PlayerDirectory.Known::firstJoin).orElse(now);
        long wait = firstJoin + s.minAccountAge().toMillis() - now;
        if (wait > 0) {
            return refuse(sender, via, FriendsMessages.REQUEST_TOO_NEW, Arg.time("time", Duration.ofMillis(wait)));
        }
        if (!this.services.commands().cooldown(sender, "friend")) {
            return CompletableFuture.completedFuture(Reply.SILENT_REFUSAL);
        }
        if (!this.inFlight.tryAcquire(self, target)) {
            return refuse(sender, via, FriendsMessages.BUSY);
        }
        if (!sender.hasPermission(BYPASS_RATE)) {
            Duration slow = this.rates.tryAcquire(self, this.directory.ipHash(self), s.perMinute(), now);
            if (!slow.isZero()) {
                this.inFlight.release(self, target);
                return refuse(sender, via, FriendsMessages.REQUEST_SLOW_DOWN, Arg.time("time", slow));
            }
        }
        if (!new FriendRequestEvent(self, target).callEvent()) {
            this.inFlight.release(self, target);
            return refuse(sender, via, FriendsMessages.CANCELLED);
        }
        return runRequest(sender, target, targetName, via, false);
    }

    /** Runs the request unit; {@code allowMutual} after the add event of a mutual request ran. Holds the guard. */
    private CompletableFuture<Reply> runRequest(Player sender, UUID target, String targetName, Via via, boolean allowMutual) {
        UUID self = sender.getUniqueId();
        FriendsSettings s = this.settings.get();
        // A vanished sender (only possible with requests.block-while-vanished off) who becomes friends at once must
        // not show up live: the target then finds it in their next login summary, as if they had been away.
        boolean targetOnline = Bukkit.getPlayer(target) != null && !this.links.vanish().vanished(self);
        FriendStore.RequestFlags flags = new FriendStore.RequestFlags(this.links.ignores().ignores(target, self),
            this.links.teams().sameTeam(self, target), limit(self), allowMutual, targetOnline);
        CompletableFuture<Reply> reply = new CompletableFuture<>();
        this.store.write(this.store.request(self, target, s.rules(), flags)).whenComplete((result, error) -> {
            if (error != null) {
                fail(sender, via, "a friend request", error, reply, self, target);
                return;
            }
            boolean handOver = false;
            try {
                this.graph.apply(result.changes());
                switch (result.outcome()) {
                    case MUTUAL_NEEDED -> {
                        handOver = true;
                        mutual(sender, target, targetName, via, reply);
                    }
                    case SENT, SHADOWED -> {
                        // A hidden request reads exactly like a sent one: the sender can't tell the difference.
                        reply.complete(succeed(sender, FriendsMessages.REQUEST_SENT, Arg.text("name", targetName)));
                        if (result.outcome() == Outcome.SENT) {
                            this.alerts.sent(target, self);
                        }
                    }
                    case BECAME_FRIENDS -> {
                        nowFriends(sender, target, targetOnline);
                        reply.complete(new Reply(true, null));
                    }
                    default -> reply.complete(refusal(sender, via, result.outcome(), self, targetName));
                }
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "Finishing a friend request failed", t);
                reply.complete(Reply.SILENT_REFUSAL);
            } finally {
                if (!handOver) {
                    this.inFlight.release(self, target);
                }
            }
        });
        return reply;
    }

    /** The target had already asked the sender: it becomes an accept, with its add event on the sender's thread. */
    private void mutual(Player sender, UUID target, String targetName, Via via, CompletableFuture<Reply> reply) {
        UUID self = sender.getUniqueId();
        var task = this.services.scheduler().entity(sender, () -> {
            if (!new FriendAddEvent(self, target, FriendAddEvent.Cause.MUTUAL).callEvent()) {
                this.inFlight.release(self, target);
                reply.complete(refuseNow(sender, via, FriendsMessages.CANCELLED));
                return;
            }
            runRequest(sender, target, targetName, via, true).whenComplete((r, e) -> reply.complete(r == null ? Reply.SILENT_REFUSAL : r));
        }, () -> {
            this.inFlight.release(self, target);
            reply.complete(Reply.SILENT_REFUSAL);
        });
        if (task == Task.NONE) {
            this.inFlight.release(self, target);
            reply.complete(Reply.SILENT_REFUSAL);
        }
    }

    /** {@code accepter} accepts the request {@code requester} sent them. Call on the accepter's thread. */
    CompletableFuture<Reply> accept(Player accepter, UUID requester, Via via) {
        UUID self = accepter.getUniqueId();
        String name = name(requester);
        if (!this.graph.isLoaded(self)) {
            return refuse(accepter, via, FriendsMessages.LOADING);
        }
        // Accepting tells the requester at once, which would show that a vanished player is online.
        boolean vanished = this.links.vanish().vanished(self);
        if (vanished && this.settings.get().blockWhileVanished()) {
            return refuse(accepter, via, FriendsMessages.ACCEPT_VANISHED);
        }
        if (!incoming(self).containsKey(requester)) {
            return refuse(accepter, via, FriendsMessages.REQUEST_GONE, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, requester)) {
            return refuse(accepter, via, FriendsMessages.BUSY);
        }
        if (!new FriendAddEvent(self, requester, FriendAddEvent.Cause.REQUEST).callEvent()) {
            this.inFlight.release(self, requester);
            return refuse(accepter, via, FriendsMessages.CANCELLED);
        }
        // With the vanish block off, a vanished accepter's friendship reaches the requester through the login summary
        // instead of live, like an accept made while the requester was away.
        boolean requesterOnline = Bukkit.getPlayer(requester) != null && !vanished;
        boolean ignored = this.links.ignoredEitherWay(self, requester);
        return unit(accepter, requester, via, "accepting a request",
            this.store.accept(self, requester, this.settings.get().rules(), limit(self), requesterOnline, ignored),
            result -> switch (result.outcome()) {
                case BECAME_FRIENDS -> {
                    Player other = requesterOnline ? Bukkit.getPlayer(requester) : null;
                    this.messenger.send(accepter, FriendsMessages.ALERT_NOW_FRIENDS, Arg.text("name", name));
                    if (other != null) {
                        this.messenger.send(other, FriendsMessages.ALERT_ACCEPTED, Arg.text("name", accepter.getName()));
                    } else if (requesterOnline) {
                        this.store.write(this.store.markNotice(requester, self));
                    }
                    yield new Reply(true, null);
                }
                default -> refusal(accepter, via, result.outcome(), self, name);
            });
    }

    /** {@code target} denies {@code sender}'s request. The sender is never told. */
    CompletableFuture<Reply> deny(Player target, UUID sender, Via via) {
        UUID self = target.getUniqueId();
        String name = name(sender);
        if (!this.graph.isLoaded(self)) {
            return refuse(target, via, FriendsMessages.LOADING);
        }
        if (!incoming(self).containsKey(sender)) {
            return refuse(target, via, FriendsMessages.REQUEST_GONE, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, sender)) {
            return refuse(target, via, FriendsMessages.BUSY);
        }
        return unit(target, sender, via, "denying a request", this.store.deny(self, sender, this.settings.get().rules()),
            result -> result.outcome() == Outcome.DONE
                ? succeed(target, FriendsMessages.REQUEST_DENIED, Arg.text("name", name))
                : refusal(target, via, result.outcome(), self, name));
    }

    /** {@code target} denies every request they can see, in one unit. */
    CompletableFuture<Reply> denyAll(Player target, Via via) {
        UUID self = target.getUniqueId();
        if (!this.graph.isLoaded(self)) {
            return refuse(target, via, FriendsMessages.LOADING);
        }
        if (!this.inFlight.tryAcquire(self, self)) {
            return refuse(target, via, FriendsMessages.BUSY);
        }
        return unit(target, self, via, "denying all requests", this.store.denyAll(self, this.settings.get().rules()),
            result -> result.outcome() == Outcome.DONE
                ? succeed(target, FriendsMessages.REQUEST_DENIED_ALL, Arg.number("count", result.count()))
                : refuseNow(target, via, FriendsMessages.REQUEST_NONE));
    }

    /** {@code sender} withdraws the request they sent to {@code target}. */
    CompletableFuture<Reply> cancel(Player sender, UUID target, Via via) {
        UUID self = sender.getUniqueId();
        String name = name(target);
        if (!this.graph.isLoaded(self)) {
            return refuse(sender, via, FriendsMessages.LOADING);
        }
        if (!outgoing(self).containsKey(target)) {
            return refuse(sender, via, FriendsMessages.REQUEST_NOT_SENT, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, target)) {
            return refuse(sender, via, FriendsMessages.BUSY);
        }
        return unit(sender, target, via, "cancelling a request", this.store.cancel(self, target, this.settings.get().rules()),
            result -> result.outcome() == Outcome.DONE
                ? succeed(sender, FriendsMessages.REQUEST_CANCELLED, Arg.text("name", name))
                : refuseNow(sender, via, FriendsMessages.REQUEST_NOT_SENT, Arg.text("name", name)));
    }

    // ------------------------------------------------------------------ friendships

    /** {@code player} ends the friendship with {@code friend}. The friend is never told. */
    CompletableFuture<Reply> remove(Player player, UUID friend, Via via) {
        UUID self = player.getUniqueId();
        String name = name(friend);
        if (!this.graph.isLoaded(self)) {
            return refuse(player, via, FriendsMessages.LOADING);
        }
        if (!this.graph.friends(self, friend)) {
            return refuse(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, friend)) {
            return refuse(player, via, FriendsMessages.BUSY);
        }
        if (!new FriendRemoveEvent(self, friend, FriendRemoveEvent.Cause.PLAYER).callEvent()) {
            this.inFlight.release(self, friend);
            return refuse(player, via, FriendsMessages.CANCELLED);
        }
        return unit(player, friend, via, "removing a friend", this.store.remove(self, friend, self.toString(), false),
            result -> result.outcome() == Outcome.DONE
                ? succeed(player, FriendsMessages.PROFILE_REMOVED, Arg.text("name", name))
                : refuseNow(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name)));
    }

    /** Marks or unmarks {@code friend} as one of {@code player}'s favourites. */
    CompletableFuture<Reply> favourite(Player player, UUID friend, boolean desired, Via via) {
        UUID self = player.getUniqueId();
        String name = name(friend);
        if (!this.graph.isLoaded(self)) {
            return refuse(player, via, FriendsMessages.LOADING);
        }
        if (!this.graph.friends(self, friend)) {
            return refuse(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, friend)) {
            return refuse(player, via, FriendsMessages.BUSY);
        }
        int cap = this.settings.get().favourites();
        if (desired && cap == 0) {
            this.inFlight.release(self, friend);
            return refuse(player, via, FriendsMessages.PROFILE_FAVOURITES_OFF);
        }
        return unit(player, friend, via, "changing a favourite", this.store.favourite(self, friend, desired, cap),
            result -> switch (result.outcome()) {
                case DONE -> succeed(player, desired ? FriendsMessages.PROFILE_FAVOURITED : FriendsMessages.PROFILE_UNFAVOURITED,
                    Arg.text("name", name));
                case FAVOURITES_FULL -> refuseNow(player, via, FriendsMessages.PROFILE_FAVOURITES_FULL, Arg.number("count", cap));
                default -> refuseNow(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name));
            });
    }

    /** Sets (or with an empty text, clears) {@code player}'s private note on {@code friend}. */
    CompletableFuture<Reply> note(Player player, UUID friend, String text, Via via) {
        UUID self = player.getUniqueId();
        String name = name(friend);
        if (!this.graph.isLoaded(self)) {
            return refuse(player, via, FriendsMessages.LOADING);
        }
        if (!this.graph.friends(self, friend)) {
            return refuse(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name));
        }
        if (!this.inFlight.tryAcquire(self, friend)) {
            return refuse(player, via, FriendsMessages.BUSY);
        }
        String note = NoteText.clean(text);
        return unit(player, friend, via, "saving a note", this.store.note(self, friend, note.isEmpty() ? null : note),
            result -> result.outcome() == Outcome.DONE
                ? succeed(player, note.isEmpty() ? FriendsMessages.PROFILE_NOTE_CLEARED : FriendsMessages.PROFILE_NOTE_SAVED,
                    Arg.text("name", name))
                : refuseNow(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", name)));
    }

    // ------------------------------------------------------------------ staff

    /**
     * Staff make {@code a} and {@code b} friends: no limits and no privacy, only the hard cap; their requests both
     * ways are removed. Call on the sender's thread (the global thread for the console).
     */
    CompletableFuture<Void> staffAdd(CommandSender actor, UUID a, UUID b) {
        String nameA = name(a);
        String nameB = name(b);
        if (a.equals(b)) {
            this.messenger.send(actor, FriendsMessages.STAFF_SAME);
            return CompletableFuture.completedFuture(null);
        }
        UUID guard = guardId(actor);
        if (!this.inFlight.tryAcquire(guard, pairKey(a, b))) {
            this.messenger.send(actor, FriendsMessages.BUSY);
            return CompletableFuture.completedFuture(null);
        }
        if (!new FriendAddEvent(a, b, FriendAddEvent.Cause.STAFF).callEvent()) {
            this.inFlight.release(guard, pairKey(a, b));
            this.messenger.send(actor, FriendsMessages.STAFF_CANCELLED);
            return CompletableFuture.completedFuture(null);
        }
        boolean aOnline = Bukkit.getPlayer(a) != null;
        boolean bOnline = Bukkit.getPlayer(b) != null;
        int cap = this.settings.get().hardCap();
        String actorId = actorId(actor);
        return this.store.write(this.store.staffAdd(a, b, actorId, cap, aOnline, bOnline)).handle((result, error) -> {
            this.inFlight.release(guard, pairKey(a, b));
            if (error != null) {
                this.logger.log(Level.WARNING, "A staff friend add failed", error);
                this.messenger.send(actor, FriendsMessages.STAFF_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
                return null;
            }
            this.graph.apply(result.changes());
            switch (result.outcome()) {
                case BECAME_FRIENDS -> {
                    this.messenger.send(actor, FriendsMessages.STAFF_ADDED, Arg.text("first", nameA), Arg.text("second", nameB));
                    tellNowFriends(a, b, aOnline);
                    tellNowFriends(b, a, bOnline);
                    this.services.audit().record(actorId, "friends.add", a.toString(), nameA + " and " + nameB + " (" + b + ")");
                }
                case ALREADY_FRIENDS -> this.messenger.send(actor, FriendsMessages.STAFF_ALREADY_FRIENDS, Arg.text("first", nameA),
                    Arg.text("second", nameB));
                case SENDER_FULL -> this.messenger.send(actor, FriendsMessages.STAFF_FULL, Arg.text("name", nameA), Arg.number("cap", cap));
                case TARGET_FULL -> this.messenger.send(actor, FriendsMessages.STAFF_FULL, Arg.text("name", nameB), Arg.number("cap", cap));
                default -> this.messenger.send(actor, FriendsMessages.STAFF_FAILED, Arg.text("reason", result.outcome().name()));
            }
            return null;
        });
    }

    /** Staff end the friendship of {@code a} and {@code b}. Neither is told. */
    CompletableFuture<Void> staffRemove(CommandSender actor, UUID a, UUID b) {
        String nameA = name(a);
        String nameB = name(b);
        UUID guard = guardId(actor);
        if (!this.inFlight.tryAcquire(guard, pairKey(a, b))) {
            this.messenger.send(actor, FriendsMessages.BUSY);
            return CompletableFuture.completedFuture(null);
        }
        if (!new FriendRemoveEvent(a, b, FriendRemoveEvent.Cause.STAFF).callEvent()) {
            this.inFlight.release(guard, pairKey(a, b));
            this.messenger.send(actor, FriendsMessages.STAFF_CANCELLED);
            return CompletableFuture.completedFuture(null);
        }
        String actorId = actorId(actor);
        return this.store.write(this.store.remove(a, b, actorId, true)).handle((result, error) -> {
            this.inFlight.release(guard, pairKey(a, b));
            if (error != null) {
                this.logger.log(Level.WARNING, "A staff friend removal failed", error);
                this.messenger.send(actor, FriendsMessages.STAFF_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
                return null;
            }
            this.graph.apply(result.changes());
            if (result.outcome() == Outcome.DONE) {
                this.messenger.send(actor, FriendsMessages.STAFF_REMOVED, Arg.text("first", nameA), Arg.text("second", nameB));
                this.services.audit().record(actorId, "friends.remove", a.toString(), nameA + " and " + nameB + " (" + b + ")");
            } else {
                this.messenger.send(actor, FriendsMessages.STAFF_NOT_FRIENDS, Arg.text("first", nameA), Arg.text("second", nameB));
            }
            return null;
        });
    }

    /** Staff delete every request {@code player} sent or received, in any state. */
    CompletableFuture<Void> staffClear(CommandSender actor, UUID player) {
        String name = name(player);
        UUID guard = guardId(actor);
        if (!this.inFlight.tryAcquire(guard, player)) {
            this.messenger.send(actor, FriendsMessages.BUSY);
            return CompletableFuture.completedFuture(null);
        }
        String actorId = actorId(actor);
        return this.store.write(this.store.clearRequests(player, actorId)).handle((result, error) -> {
            this.inFlight.release(guard, player);
            if (error != null) {
                this.logger.log(Level.WARNING, "Clearing friend requests failed", error);
                this.messenger.send(actor, FriendsMessages.STAFF_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
                return null;
            }
            this.graph.apply(result.changes());
            this.messenger.send(actor, FriendsMessages.STAFF_CLEARED, Arg.text("name", name), Arg.number("count", result.count()));
            this.services.audit().record(actorId, "friends.clear-requests", player.toString(), name + ", " + result.count() + " rows");
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs a unit for an action on the pair (actor, other), whose guard the caller holds: applies the committed
     * state to memory, then lets {@code finish} tell the player, then frees the guard.
     */
    private CompletableFuture<Reply> unit(Player actor, UUID other, Via via, String what, SqlWork<FriendStore.Result> work,
                                          Function<FriendStore.Result, Reply> finish) {
        UUID self = actor.getUniqueId();
        CompletableFuture<Reply> reply = new CompletableFuture<>();
        this.store.write(work).whenComplete((result, error) -> {
            if (error != null) {
                fail(actor, via, what, error, reply, self, other);
                return;
            }
            try {
                this.graph.apply(result.changes());
                reply.complete(finish.apply(result));
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "Finishing " + what + " failed", t);
                reply.complete(Reply.SILENT_REFUSAL);
            } finally {
                this.inFlight.release(self, other);
            }
        });
        return reply;
    }

    private void fail(Player actor, Via via, String what, Throwable error, CompletableFuture<Reply> reply, UUID self, UUID other) {
        this.logger.log(Level.WARNING, "Storing " + what + " for " + actor.getName() + " failed; nothing was changed", error);
        this.inFlight.release(self, other);
        reply.complete(refuseNow(actor, via, CoreMessages.ACTION_FAILED));
    }

    /** Tells both sides they are friends now (an offline side finds it in their login summary). */
    private void nowFriends(Player actor, UUID other, boolean otherWasOnline) {
        this.messenger.send(actor, FriendsMessages.ALERT_NOW_FRIENDS, Arg.text("name", name(other)));
        if (otherWasOnline) {
            tellNowFriends(other, actor.getUniqueId(), true);
        }
        // Otherwise the unit marked it for the other side's login summary (they were offline, or the actor is vanished).
    }

    /** Tells {@code player} about the new friend {@code friend}, or keeps it for their login summary. */
    private void tellNowFriends(UUID player, UUID friend, boolean wasOnline) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.messenger.send(online, FriendsMessages.ALERT_NOW_FRIENDS, Arg.text("name", name(friend)));
        } else if (wasOnline) {
            // They left between the unit and now: the unit did not mark it for the summary, so mark it now.
            this.store.write(this.store.markNotice(player, friend));
        }
    }

    /** The refusal text for a unit outcome. */
    private Reply refusal(Player player, Via via, Outcome outcome, UUID self, String otherName) {
        FriendsSettings s = this.settings.get();
        return switch (outcome) {
            case ALREADY_FRIENDS -> refuseNow(player, via, FriendsMessages.ALREADY_FRIENDS, Arg.text("name", otherName));
            case ALREADY_SENT -> refuseNow(player, via, FriendsMessages.REQUEST_ALREADY_SENT, Arg.text("name", otherName));
            case OUTGOING_FULL -> refuseNow(player, via, FriendsMessages.REQUEST_OUTGOING_FULL, Arg.number("count", s.maxOutgoing()));
            case DAILY_CAP -> refuseNow(player, via, FriendsMessages.REQUEST_DAILY_CAP);
            case SENDER_FULL -> {
                int limit = limit(self);
                yield refuseNow(player, via, rankCanRaise(limit) ? FriendsMessages.REQUEST_SENDER_FULL
                    : FriendsMessages.REQUEST_SENDER_FULL_MAX, Arg.number("limit", limit));
            }
            case TARGET_FULL -> refuseNow(player, via, FriendsMessages.REQUEST_TARGET_FULL, Arg.text("name", otherName));
            case PRIVATE -> refuseNow(player, via, FriendsMessages.REQUEST_PRIVATE, Arg.text("name", otherName));
            case NOT_FRIENDS -> refuseNow(player, via, FriendsMessages.NOT_FRIENDS, Arg.text("name", otherName));
            case FAVOURITES_FULL -> refuseNow(player, via, FriendsMessages.PROFILE_FAVOURITES_FULL, Arg.number("count", s.favourites()));
            default -> refuseNow(player, via, FriendsMessages.REQUEST_GONE, Arg.text("name", otherName));
        };
    }

    private CompletableFuture<Reply> refuse(Player player, Via via, MessageKey key, Arg... args) {
        return CompletableFuture.completedFuture(refuseNow(player, via, key, args));
    }

    /** A refusal: sent when it came from a command; from a dialog only its sound plays and the dialog shows it. */
    private Reply refuseNow(Player player, Via via, MessageKey key, Arg... args) {
        if (via == Via.COMMAND) {
            this.messenger.send(player, key, args);
        } else {
            this.messenger.feedback(player, key.feedback());
        }
        return new Reply(false, this.lang.get(key, args));
    }

    private Reply succeed(Player player, MessageKey key, Arg... args) {
        this.messenger.send(player, key, args);
        return new Reply(true, this.lang.get(key, args));
    }

    private static UUID guardId(CommandSender actor) {
        return actor instanceof Player player ? player.getUniqueId() : CONSOLE;
    }

    private static String actorId(CommandSender actor) {
        return actor instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    /** One key for an unordered pair (staff actions on a and b, or b and a, share it). */
    static UUID pairKey(UUID a, UUID b) {
        boolean ordered = a.compareTo(b) <= 0;
        String key = (ordered ? a : b) + ":" + (ordered ? b : a);
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
