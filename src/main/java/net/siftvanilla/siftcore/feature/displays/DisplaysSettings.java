package net.siftvanilla.siftcore.feature.displays;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.TextStyle;

/**
 * Parsed {@code features/displays.yml}: templates, default looks, displays defined in the file and right-click
 * commands. Every value is validated and every mistake becomes a precise problem.
 *
 * @param templates          template name to compiled template, in file order
 * @param defaults           looks used unless a display overrides them
 * @param displays           displays defined in the file, in file order
 * @param interactionEnabled whether leaderboards get a click box
 * @param clickCommands      template name to the command a right-click runs (no slash)
 * @param clickCooldown      time between two clicks by one player
 */
public record DisplaysSettings(
    Map<String, DisplayTemplate> templates,
    DisplayOptions defaults,
    Map<String, ConfigDisplay> displays,
    boolean interactionEnabled,
    Map<String, String> clickCommands,
    Duration clickCooldown) {

    /** Display and template names: up to 32 lowercase letters, digits, - and _. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final double MAX_COORDINATE = 30_000_000;
    public static final double MIN_Y = -2048;
    public static final double MAX_Y = 4096;

    public DisplaysSettings {
        templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        displays = Collections.unmodifiableMap(new LinkedHashMap<>(displays));
        clickCommands = Map.copyOf(clickCommands);
    }

    /** A display written in displays.yml; {@code position} is null until it is placed. */
    public record ConfigDisplay(String id, String template, DisplayPosition position, DisplayOptions options) {
    }

    /** What a placeholder name resolves to right now. */
    public enum PlaceholderStatus {
        /** Provided (or not checkable yet, during startup). */
        KNOWN,
        /** No feature provides it. */
        UNKNOWN,
        /** Provided, but its value depends on the player looking at it. */
        PER_PLAYER
    }

    /** The server facts the file is validated against (a fake in unit tests). */
    public interface Context {

        /** The text style used to check tags. */
        TextStyle style();

        boolean worldExists(String world);

        /** Names of the loaded worlds, for messages. */
        List<String> worlds();

        PlaceholderStatus placeholder(String name);

        /** Templates used by displays created in-game (display name to template name). */
        Map<String, String> placedTemplates();
    }

    public static DisplaysSettings parse(ConfigReader r, Context context) {
        DisplayOptions defaults = DisplayOptions.read(r.section("defaults"), DisplayOptions.FALLBACK, true);
        Map<String, DisplayTemplate> templates = readTemplates(r.section("templates"), context);
        Map<String, ConfigDisplay> displays = readDisplays(r, templates, defaults, context);

        ConfigReader interaction = r.section("interaction");
        boolean enabled = interaction.bool("enabled", true);
        Duration cooldown = interaction.duration("cooldown", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1));
        Map<String, String> commands = new LinkedHashMap<>();
        ConfigReader commandSection = interaction.section("commands", false);
        for (String template : commandSection.keys()) {
            String command = commandSection.string(template, "").strip();
            if (command.startsWith("/")) {
                command = command.substring(1).strip();
            }
            if (!templates.containsKey(template)) {
                commandSection.problem(template, "is not a template in this file (templates: " + String.join(", ", templates.keySet()) + ")");
            } else if (command.isEmpty()) {
                commandSection.problem(template, "is empty (expected a command like baltop)");
            } else {
                commands.put(template, command);
            }
        }

        ConfigReader templateSection = r.section("templates", false);
        context.placedTemplates().forEach((display, template) -> {
            if (!displays.containsKey(display) && !templates.containsKey(template)) {
                templateSection.problem(template, "is missing, but the display '" + display + "' placed in-game uses it. "
                    + "Keep the template or delete the display with /displays delete " + display);
            }
        });
        return new DisplaysSettings(templates, defaults, displays, enabled, commands, cooldown);
    }

    private static Map<String, DisplayTemplate> readTemplates(ConfigReader section, Context context) {
        Map<String, DisplayTemplate> templates = new LinkedHashMap<>();
        for (String id : section.keys()) {
            if (!NAME.matcher(id).matches()) {
                section.problem(id, "is not a valid template name (use up to 32 lowercase letters, digits, - and _)");
                continue;
            }
            List<String> lines = section.stringList(id, null);
            if (lines == null) {
                continue;
            }
            DisplayTemplate template = DisplayTemplate.compile(id, lines, context.style(),
                (line, message) -> section.problem(id, line == 0 ? message : "line " + line + " " + message));
            List<String> unknown = new ArrayList<>();
            List<String> perPlayer = new ArrayList<>();
            for (String name : template.placeholders()) {
                switch (context.placeholder(name)) {
                    case UNKNOWN -> unknown.add("{" + name + "}");
                    case PER_PLAYER -> perPlayer.add("{" + name + "}");
                    case KNOWN -> {
                    }
                }
            }
            if (!unknown.isEmpty()) {
                section.problem(id, "uses placeholders that no feature provides: " + String.join(", ", unknown)
                    + " (/sift placeholders lists them)");
            }
            if (!perPlayer.isEmpty()) {
                section.problem(id, "uses placeholders that are different for every player, so a display can't show them: "
                    + String.join(", ", perPlayer));
            }
            templates.put(id, template);
        }
        return templates;
    }

    private static Map<String, ConfigDisplay> readDisplays(ConfigReader r, Map<String, DisplayTemplate> templates,
                                                           DisplayOptions defaults, Context context) {
        Map<String, ConfigDisplay> displays = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : r.children("displays").entrySet()) {
            String id = entry.getKey();
            ConfigReader d = entry.getValue();
            if (!NAME.matcher(id).matches()) {
                r.problem("displays." + id, "is not a valid display name (use up to 32 lowercase letters, digits, - and _)");
                continue;
            }
            String template = d.string("template", "");
            if (d.has("template") && !templates.containsKey(template)) {
                d.problem("template", "'" + template + "' is not a template in this file (templates: "
                    + String.join(", ", templates.keySet()) + ")");
            }
            DisplayPosition position = readPosition(d, context);
            DisplayOptions options = DisplayOptions.read(d, defaults, false);
            displays.put(id, new ConfigDisplay(id, template, position, options));
        }
        return displays;
    }

    private static DisplayPosition readPosition(ConfigReader d, Context context) {
        boolean any = d.has("world") || d.has("x") || d.has("y") || d.has("z");
        if (!any) {
            if (d.has("yaw")) {
                d.problem("yaw", "needs world, x, y and z as well");
            }
            return null;
        }
        String world = d.string("world", "");
        double x = d.decimal("x", -MAX_COORDINATE, MAX_COORDINATE, 0);
        double y = d.decimal("y", MIN_Y, MAX_Y, 0);
        double z = d.decimal("z", -MAX_COORDINATE, MAX_COORDINATE, 0);
        float yaw = d.has("yaw") ? (float) d.decimal("yaw", -360, 360, 0) : 0f;
        if (d.has("world") && !context.worldExists(world)) {
            d.problem("world", "'" + world + "' is not a loaded world (loaded: " + String.join(", ", context.worlds()) + ")");
        }
        return new DisplayPosition(world, x, y, z, yaw);
    }
}
