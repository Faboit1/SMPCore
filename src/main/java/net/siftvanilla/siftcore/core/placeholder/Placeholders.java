package net.siftvanilla.siftcore.core.placeholder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;

/**
 * SiftCore's placeholders, exposed through PlaceholderAPI as {@code %siftcore_<name>%} and used internally by the
 * scoreboard and tab list. Exact names are registered by features; prefixed names (e.g. {@code top_money_})
 * receive the rest of the identifier as an argument. Resolvers must be cheap and thread-safe (read caches only).
 */
public final class Placeholders {

    private final Map<String, Function<OfflinePlayer, String>> exact = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed = new ConcurrentHashMap<>();
    private final Map<String, String> docs = new TreeMap<>();

    public void register(String name, String description, Function<OfflinePlayer, String> resolver) {
        if (this.exact.putIfAbsent(name, resolver) != null) {
            throw new IllegalStateException("Placeholder " + name + " is registered twice");
        }
        synchronized (this.docs) {
            this.docs.put(name, description);
        }
    }

    /** Registers {@code prefix<argument>}, e.g. {@code top_money_name_<rank>}. */
    public void registerPrefix(String prefix, String usage, String description, BiFunction<OfflinePlayer, String, String> resolver) {
        if (this.prefixed.putIfAbsent(prefix, resolver) != null) {
            throw new IllegalStateException("Placeholder prefix " + prefix + " is registered twice");
        }
        synchronized (this.docs) {
            this.docs.put(usage, description);
        }
    }

    /** Resolves a placeholder; returns null when unknown. */
    public String resolve(OfflinePlayer player, String name) {
        Function<OfflinePlayer, String> resolver = this.exact.get(name);
        if (resolver != null) {
            return resolver.apply(player);
        }
        String best = null;
        for (String prefix : this.prefixed.keySet()) {
            if (name.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? null : this.prefixed.get(best).apply(player, name.substring(best.length()));
    }

    /** Name or usage to description, sorted (for the docs and /sift placeholders). */
    public Map<String, String> documentation() {
        synchronized (this.docs) {
            return Map.copyOf(this.docs);
        }
    }
}
