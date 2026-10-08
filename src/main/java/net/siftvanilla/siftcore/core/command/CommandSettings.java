package net.siftvanilla.siftcore.core.command;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code commands.yml}: per-command enabled flag, aliases, cooldown and the plugin it yields to. */
public record CommandSettings(Map<String, Entry> commands) {

    /**
     * Settings of one command; {@code aliases} null means "use the built-in aliases". {@code yieldTo} names a plugin
     * that takes the command over while it runs (null: none), so the command is only registered without it.
     */
    public record Entry(boolean enabled, List<String> aliases, Duration cooldown, String yieldTo) {
    }

    public static final Entry DEFAULT = new Entry(true, null, Duration.ZERO, null);

    public Entry get(String command) {
        return this.commands.getOrDefault(command, DEFAULT);
    }

    public static CommandSettings parse(ConfigReader reader) {
        Map<String, Entry> map = new HashMap<>();
        for (Map.Entry<String, ConfigReader> child : reader.children("commands").entrySet()) {
            ConfigReader section = child.getValue();
            boolean enabled = section.has("enabled") ? section.bool("enabled", true) : true;
            List<String> aliases = section.has("aliases") ? section.stringList("aliases", null) : null;
            if (aliases != null) {
                for (String alias : aliases) {
                    if (!alias.matches("[a-z0-9_-]{1,32}")) {
                        section.problem("aliases", "contains '" + alias + "'; aliases must be lowercase letters, digits, - or _");
                    }
                }
            }
            Duration cooldown = section.has("cooldown")
                ? section.duration("cooldown", Duration.ZERO, Duration.ofDays(30), Duration.ZERO)
                : Duration.ZERO;
            String yieldTo = section.has("yield-to") ? section.string("yield-to", "") : null;
            if (yieldTo != null && !yieldTo.matches("[A-Za-z0-9_.-]{1,64}")) {
                section.problem("yield-to", "must be a plugin name (letters, digits, _, . or -), not '" + yieldTo + "'");
                yieldTo = null;
            }
            map.put(child.getKey(), new Entry(enabled, aliases, cooldown, yieldTo));
        }
        return new CommandSettings(Map.copyOf(map));
    }
}
