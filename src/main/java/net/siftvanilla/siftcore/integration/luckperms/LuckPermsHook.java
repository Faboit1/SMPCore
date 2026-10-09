package net.siftvanilla.siftcore.integration.luckperms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.event.user.UserUnloadEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.siftvanilla.siftcore.core.integration.Ranks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * LuckPerms for SiftCore: rank labels ({@link Ranks}) and the group grants store deliveries make.
 * <p>
 * A rank label is the player's {@code siftcore-rank} meta value (usually set on a group:
 * {@code /lp group baron meta set siftcore-rank Baron}), otherwise their primary group's display name, otherwise the
 * group's name with a capital letter; groups listed as hidden (the default group) show no label. Formatting is
 * removed ({@link RankText}); the coloured label ({@link Ranks#component}) takes its colour from the
 * {@code siftcore-rank-gradient} or {@code siftcore-rank-color} meta value, or the secondary colour without one.
 * Labels are cached per player and dropped whenever LuckPerms recalculates that player
 * or any group, so lookups are a map read and safe from any thread. Players LuckPerms has not loaded (offline) have
 * no label and the group {@code default}.
 * <p>
 * Only loaded after checking that LuckPerms is enabled.
 */
public final class LuckPermsHook {

    /** The plugin name LuckPerms registers under. */
    public static final String PLUGIN = "LuckPerms";

    /**
     * What the labels are made of (follows reloads).
     *
     * @param metaKey      the meta value that sets the label ({@code siftcore-rank})
     * @param colorMeta    the meta value with the rank's colour ({@code siftcore-rank-color}: {@code #5FA8FF} or a name)
     * @param gradientMeta the meta value with the rank's gradient ({@code siftcore-rank-gradient}: {@code #FF6AD5:#B26BFF})
     * @param hiddenGroups primary groups without a label
     */
    public record Options(String metaKey, String colorMeta, String gradientMeta, Set<String> hiddenGroups) {
        public Options {
            hiddenGroups = Set.copyOf(hiddenGroups);
        }
    }

    /** How a player holds a group directly (global context only). */
    public record Hold(boolean permanent, Instant expiry) {

        public static final Hold NONE = new Hold(false, null);

        public boolean held() {
            return this.permanent || this.expiry != null;
        }
    }

    private record Rank(String label, String group, TextColor color, List<TextColor> gradient) {
        static final Rank NONE = new Rank("", "default", null, List.of());
    }

    private final LuckPerms api;
    private final Supplier<Options> options;
    private final Supplier<TextColor> secondary;
    private final Map<UUID, Rank> cache = new ConcurrentHashMap<>();
    /** Bumped on every invalidation, so a label computed from data that changed meanwhile is not cached. */
    private final AtomicLong generation = new AtomicLong();
    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();
    private final Ranks ranks;

    private LuckPermsHook(LuckPerms api, Supplier<Options> options, Supplier<TextColor> secondary) {
        this.api = api;
        this.options = options;
        this.secondary = secondary;
        this.ranks = new Ranks() {
            @Override
            public String label(UUID player) {
                return rank(player).label();
            }

            @Override
            public Component component(Player player) {
                Rank rank = rank(player.getUniqueId());
                return RankText.styled(rank.label(), rank.color(), rank.gradient(), LuckPermsHook.this.secondary.get());
            }

            @Override
            public String group(UUID player) {
                return rank(player).group();
            }
        };
    }

    /** Connects to the running LuckPerms and listens for the changes that invalidate labels. */
    public static LuckPermsHook connect(Plugin plugin, Supplier<Options> options, Supplier<TextColor> secondary, Logger logger) {
        LuckPermsHook hook = new LuckPermsHook(LuckPermsProvider.get(), options, secondary);
        var bus = hook.api.getEventBus();
        hook.subscriptions.add(bus.subscribe(plugin, UserDataRecalculateEvent.class, event -> hook.forget(event.getUser().getUniqueId())));
        hook.subscriptions.add(bus.subscribe(plugin, UserUnloadEvent.class, event -> hook.forget(event.getUser().getUniqueId())));
        hook.subscriptions.add(bus.subscribe(plugin, GroupDataRecalculateEvent.class, event -> hook.invalidate()));
        logger.info("LuckPerms found: rank labels come from LuckPerms and store deliveries can grant groups.");
        return hook;
    }

    public Ranks ranks() {
        return this.ranks;
    }

    /** The rank's colour as {@code #RRGGBB} (the first stop of a gradient), empty without a label or a colour. */
    public String colorHex(UUID player) {
        Rank rank = rank(player);
        TextColor color = rank.gradient().isEmpty() ? rank.color() : rank.gradient().getFirst();
        return color == null || rank.label().isEmpty() ? "" : RankText.hex(color);
    }

    /** Forgets every cached label (a group changed, or a reload changed the options). */
    public void invalidate() {
        this.generation.incrementAndGet();
        this.cache.clear();
    }

    private void forget(UUID player) {
        this.generation.incrementAndGet();
        this.cache.remove(player);
    }

    public void unregister() {
        for (EventSubscription<?> subscription : this.subscriptions) {
            subscription.close();
        }
        this.subscriptions.clear();
        this.cache.clear();
    }

    private Rank rank(UUID player) {
        Rank cached = this.cache.get(player);
        if (cached != null) {
            return cached;
        }
        long before = this.generation.get();
        User user = this.api.getUserManager().getUser(player);
        if (user == null) {
            return Rank.NONE;
        }
        Rank rank = compute(user);
        if (this.generation.get() == before) {
            this.cache.put(player, rank);
        }
        return rank;
    }

    private Rank compute(User user) {
        Options options = this.options.get();
        String group = user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        CachedMetaData meta = user.getCachedData().getMetaData();
        String value = meta.getMetaValue(options.metaKey());
        String label;
        if (value != null && !value.isBlank()) {
            label = RankText.plain(value);
        } else if (options.hiddenGroups().contains(group)) {
            label = "";
        } else {
            Group loaded = this.api.getGroupManager().getGroup(group);
            String display = loaded == null ? null : loaded.getDisplayName();
            label = display == null || display.isBlank() ? RankText.fromGroup(group) : RankText.plain(display);
        }
        return new Rank(label, group, RankText.color(meta.getMetaValue(options.colorMeta())),
            RankText.gradient(meta.getMetaValue(options.gradientMeta())));
    }

    // ------------------------------------------------------------------ store grants

    /** Whether LuckPerms has a group with this name. */
    public boolean groupExists(String group) {
        return this.api.getGroupManager().getGroup(group.toLowerCase(Locale.ROOT)) != null;
    }

    /** How the player holds the group directly, loading them from LuckPerms storage when offline. */
    public CompletableFuture<Hold> hold(UUID player, String group) {
        String name = group.toLowerCase(Locale.ROOT);
        return this.api.getUserManager().loadUser(player).thenApply(user -> holdOf(user, name));
    }

    /**
     * Makes sure the player holds the group at least until {@code until} (null: permanently). Idempotent: when they
     * already hold it as long or longer nothing changes; otherwise their shorter timed grants of that group are
     * replaced by one that ends at {@code until}. Completes with whether anything changed, after LuckPerms saved.
     */
    public CompletableFuture<Boolean> ensure(UUID player, String group, Instant until) {
        String name = group.toLowerCase(Locale.ROOT);
        AtomicBoolean changed = new AtomicBoolean();
        return this.api.getUserManager().modifyUser(player, user -> {
            Hold hold = holdOf(user, name);
            if (hold.permanent() || (until != null && hold.expiry() != null && !hold.expiry().isBefore(until))) {
                return;
            }
            for (InheritanceNode node : globalNodes(user, name)) {
                if (node.hasExpiry()) {
                    user.data().remove(node);
                }
            }
            InheritanceNode.Builder builder = InheritanceNode.builder(name);
            if (until != null) {
                builder.expiry(until);
            }
            user.data().add(builder.build());
            changed.set(true);
        }).thenApply(ignored -> changed.get());
    }

    /**
     * Takes a group back (store refunds). Idempotent: {@code removePermanent} removes the player's permanent grant of
     * the group; with {@code cutTimed}, their timed grants that end after {@code cutTo} are cut to end then, or removed
     * when {@code cutTo} is null or already past. Completes with whether anything changed, after LuckPerms saved.
     */
    public CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo) {
        String name = group.toLowerCase(Locale.ROOT);
        AtomicBoolean changed = new AtomicBoolean();
        return this.api.getUserManager().modifyUser(player, user -> {
            Instant now = Instant.now();
            boolean cutAway = cutTo == null || !cutTo.isAfter(now);
            boolean shortened = false;
            for (InheritanceNode node : globalNodes(user, name)) {
                if (!node.hasExpiry()) {
                    if (removePermanent) {
                        user.data().remove(node);
                        changed.set(true);
                    }
                } else if (cutTimed && (cutAway || node.getExpiry().isAfter(cutTo))) {
                    user.data().remove(node);
                    changed.set(true);
                    shortened |= !cutAway;
                }
            }
            if (shortened) {
                user.data().add(InheritanceNode.builder(name).expiry(cutTo).build());
            }
        }).thenApply(ignored -> changed.get());
    }

    private static Hold holdOf(User user, String group) {
        boolean permanent = false;
        Instant expiry = null;
        for (InheritanceNode node : globalNodes(user, group)) {
            if (!node.hasExpiry()) {
                permanent = true;
            } else if (!node.hasExpired() && (expiry == null || node.getExpiry().isAfter(expiry))) {
                expiry = node.getExpiry();
            }
        }
        return permanent ? new Hold(true, null) : expiry == null ? Hold.NONE : new Hold(false, expiry);
    }

    private static List<InheritanceNode> globalNodes(User user, String group) {
        List<InheritanceNode> nodes = new ArrayList<>();
        for (Node node : user.data().toCollection()) {
            if (node instanceof InheritanceNode inheritance && inheritance.getValue() && inheritance.getContexts().isEmpty()
                && inheritance.getGroupName().equalsIgnoreCase(group)) {
                nodes.add(inheritance);
            }
        }
        return nodes;
    }
}
