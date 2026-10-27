package net.siftvanilla.siftcore.integration.placeholderapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * The {@code %siftcore_<name>%} placeholders for PlaceholderAPI, also answered as {@code %siftvanilla_<name>%} (the
 * server's name, which configs written for the server tend to use), answered by SiftCore's placeholder registry
 * ({@code core.placeholder.Placeholders}), the same values the scoreboard and tab list use. PlaceholderAPI asks on
 * whatever thread wants a value (verified on Canvas: the main, region and async scheduler threads); the registry's
 * resolvers only read thread-safe caches, so this is safe from any of them.
 * <p>
 * Registered with {@code persist() = true}, so {@code /papi reload} keeps it. Only loaded after checking that
 * PlaceholderAPI is enabled.
 */
public final class SiftCoreExpansion extends PlaceholderExpansion {

    /** The plugin name PlaceholderAPI registers under. */
    public static final String PLUGIN = "PlaceholderAPI";
    public static final String IDENTIFIER = "siftcore";
    /** The second identifier: the same placeholders under the server's name. */
    public static final String ALIAS = "siftvanilla";

    private final String identifier;
    private final Plugin plugin;
    private final Placeholders placeholders;
    private final Logger logger;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private SiftCoreExpansion(String identifier, Plugin plugin, Placeholders placeholders, Logger logger) {
        this.identifier = identifier;
        this.plugin = plugin;
        this.placeholders = placeholders;
        this.logger = logger;
    }

    /**
     * Registers the expansion under both identifiers; returns the action that unregisters them, or null when
     * PlaceholderAPI refused the main one. A refused alias is only a warning.
     */
    public static Runnable register(Plugin plugin, Placeholders placeholders, Logger logger) {
        SiftCoreExpansion expansion = new SiftCoreExpansion(IDENTIFIER, plugin, placeholders, logger);
        if (!expansion.register()) {
            logger.severe("PlaceholderAPI refused the siftcore expansion (another expansion already uses the identifier "
                + IDENTIFIER + "); %" + IDENTIFIER + "_...% placeholders will not work.");
            return null;
        }
        SiftCoreExpansion alias = new SiftCoreExpansion(ALIAS, plugin, placeholders, logger);
        boolean aliased = alias.register();
        if (!aliased) {
            logger.warning("PlaceholderAPI refused the " + ALIAS + " alias (another expansion uses it); use %" + IDENTIFIER
                + "_<name>%.");
        }
        logger.info("Registered " + placeholders.documentation().size() + " placeholders with PlaceholderAPI as %"
            + IDENTIFIER + "_<name>%" + (aliased ? " and %" + ALIAS + "_<name>%." : "."));
        return () -> {
            if (expansion.isRegistered()) {
                expansion.unregister();
            }
            if (alias.isRegistered()) {
                alias.unregister();
            }
        };
    }

    @Override
    public String getIdentifier() {
        return this.identifier;
    }

    @Override
    public String getAuthor() {
        return String.join(", ", this.plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return this.plugin.getPluginMeta().getVersion();
    }

    @Override
    public String getRequiredPlugin() {
        return this.plugin.getName();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        List<String> names = new ArrayList<>();
        for (String name : this.placeholders.documentation().keySet()) {
            names.add("%" + this.identifier + "_" + name + "%");
        }
        names.sort(null);
        return names;
    }

    /**
     * Answers {@code %siftcore_<params>%}: the value, or null for names SiftCore does not have (PlaceholderAPI then
     * leaves the text as it was). A resolver that needs a player gets none for requests without one; that and any
     * other failure answers null too, reported once per name.
     */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        try {
            return this.placeholders.resolve(player, params);
        } catch (RuntimeException e) {
            if (player != null && this.reported.add(params)) {
                this.logger.log(Level.WARNING, "The placeholder %" + this.identifier + "_" + params + "% failed", e);
            }
            return null;
        }
    }
}
