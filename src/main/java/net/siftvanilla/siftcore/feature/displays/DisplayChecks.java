package net.siftvanilla.siftcore.feature.displays;

import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * What displays.yml is validated against on a running server: loaded worlds, the registered placeholders and the
 * templates of displays created in-game.
 * <p>
 * Placeholders are only judged once every feature has registered its own ({@link #arm()} after startup), so at
 * startup an unknown placeholder simply shows "-" and only {@code /sift reload} reports it as a problem.
 * Thread-safe: reads volatile snapshots and thread-safe server lookups only.
 */
final class DisplayChecks implements DisplaysSettings.Context {

    private final Placeholders placeholders;
    private final TextStyle style;
    private volatile boolean armed;
    private volatile Map<String, String> placedTemplates = Map.of();

    DisplayChecks(Placeholders placeholders, TextStyle style) {
        this.placeholders = placeholders;
        this.style = style;
    }

    /** From now on unknown and per-player placeholders are problems. */
    void arm() {
        this.armed = true;
    }

    void placedTemplates(Map<String, String> templates) {
        this.placedTemplates = Map.copyOf(templates);
    }

    @Override
    public TextStyle style() {
        return this.style;
    }

    @Override
    public boolean worldExists(String world) {
        return Bukkit.getWorld(world) != null;
    }

    @Override
    public List<String> worlds() {
        return Bukkit.getWorlds().stream().map(World::getName).toList();
    }

    @Override
    public DisplaysSettings.PlaceholderStatus placeholder(String name) {
        if (!this.armed) {
            return DisplaysSettings.PlaceholderStatus.KNOWN;
        }
        return status(this.placeholders, name);
    }

    /** Asks the registry for the value a display would show (resolvers are cheap cache reads). */
    static DisplaysSettings.PlaceholderStatus status(Placeholders placeholders, String name) {
        try {
            return placeholders.resolve(null, name) == null
                ? DisplaysSettings.PlaceholderStatus.UNKNOWN : DisplaysSettings.PlaceholderStatus.KNOWN;
        } catch (RuntimeException e) {
            return DisplaysSettings.PlaceholderStatus.PER_PLAYER;
        }
    }

    @Override
    public Map<String, String> placedTemplates() {
        return this.placedTemplates;
    }
}
