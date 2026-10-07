package net.siftvanilla.siftcore.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Typed, validating view over a YAML section. Every read either returns a valid value or records a precise
 * {@link ConfigProblem} and returns the fallback, so one pass reports every mistake in a file at once.
 * Callers check {@link #problems()} (shared with child readers) after reading.
 */
public final class ConfigReader {

    private final String file;
    private final String prefix;
    private final ConfigurationSection section;
    private final List<ConfigProblem> problems;

    public ConfigReader(String file, ConfigurationSection section) {
        this(file, "", section, new ArrayList<>());
    }

    private ConfigReader(String file, String prefix, ConfigurationSection section, List<ConfigProblem> problems) {
        this.file = file;
        this.prefix = prefix;
        this.section = section;
        this.problems = problems;
    }

    public String file() {
        return this.file;
    }

    public List<ConfigProblem> problems() {
        return Collections.unmodifiableList(this.problems);
    }

    /** Throws if any problem was recorded by this reader or a child of it. */
    public void throwIfProblems() throws ConfigException {
        if (!this.problems.isEmpty()) {
            throw new ConfigException(this.problems);
        }
    }

    public String fullPath(String path) {
        return this.prefix.isEmpty() ? path : this.prefix + "." + path;
    }

    public void problem(String path, String message) {
        this.problems.add(new ConfigProblem(this.file, fullPath(path), message));
    }

    public boolean has(String path) {
        return this.section != null && this.section.contains(path);
    }

    /** All direct child keys of this section, in file order. */
    public Set<String> keys() {
        return this.section == null ? Set.of() : this.section.getKeys(false);
    }

    /** A child section; missing sections become an empty reader and record a problem when required. */
    public ConfigReader section(String path, boolean required) {
        ConfigurationSection child = this.section == null ? null : this.section.getConfigurationSection(path);
        if (child == null && required) {
            problem(path, "is missing (expected a section)");
        }
        return new ConfigReader(this.file, fullPath(path), child, this.problems);
    }

    public ConfigReader section(String path) {
        return section(path, true);
    }

    /** Each child section of {@code path}, keyed by its name, in file order. */
    public Map<String, ConfigReader> children(String path) {
        ConfigReader parent = section(path, false);
        Map<String, ConfigReader> result = new LinkedHashMap<>();
        if (parent.section == null) {
            return result;
        }
        for (String key : parent.section.getKeys(false)) {
            if (parent.section.isConfigurationSection(key)) {
                result.put(key, parent.section(key, true));
            } else {
                parent.problem(key, "must be a section");
            }
        }
        return result;
    }

    private Object raw(String path) {
        return this.section == null ? null : this.section.get(path);
    }

    public String string(String path, String fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected text)");
            return fallback;
        }
        if (value instanceof ConfigurationSection || value instanceof List<?>) {
            problem(path, "must be text, got a " + (value instanceof List<?> ? "list" : "section"));
            return fallback;
        }
        return String.valueOf(value);
    }

    public String optionalString(String path, String fallback) {
        return raw(path) == null ? fallback : string(path, fallback);
    }

    public boolean bool(String path, boolean fallback) {
        Object value = raw(path);
        if (value instanceof Boolean b) {
            return b;
        }
        problem(path, value == null ? "is missing (expected true or false)" : "must be true or false, got '" + value + "'");
        return fallback;
    }

    public int integer(String path, int min, int max, int fallback) {
        long value = longValue(path, min, max, fallback);
        return (int) value;
    }

    public long longValue(String path, long min, long max, long fallback) {
        Object value = raw(path);
        long parsed;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            parsed = ((Number) value).longValue();
        } else if (value instanceof String s && s.trim().matches("-?\\d+")) {
            try {
                parsed = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                problem(path, "is too large");
                return fallback;
            }
        } else {
            problem(path, value == null ? "is missing (expected a whole number)" : "must be a whole number, got '" + value + "'");
            return fallback;
        }
        if (parsed < min || parsed > max) {
            problem(path, "must be between " + min + " and " + max + ", got " + parsed);
            return fallback;
        }
        return parsed;
    }

    public double decimal(String path, double min, double max, double fallback) {
        Object value = raw(path);
        double parsed;
        if (value instanceof Number n) {
            parsed = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                parsed = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                problem(path, "must be a number, got '" + s + "'");
                return fallback;
            }
        } else {
            problem(path, value == null ? "is missing (expected a number)" : "must be a number, got '" + value + "'");
            return fallback;
        }
        if (Double.isNaN(parsed) || parsed < min || parsed > max) {
            problem(path, "must be between " + min + " and " + max + ", got " + parsed);
            return fallback;
        }
        return parsed;
    }

    public Duration duration(String path, Duration min, Duration max, Duration fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected a duration like 30s, 5m, 1h30m or 2d)");
            return fallback;
        }
        Duration parsed;
        try {
            parsed = Durations.parse(String.valueOf(value));
        } catch (IllegalArgumentException | ArithmeticException e) {
            problem(path, e.getMessage() == null ? "is not a valid duration" : e.getMessage() + ", got '" + value + "'");
            return fallback;
        }
        if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
            problem(path, "must be between " + Durations.format(min) + " and " + Durations.format(max) + ", got " + value);
            return fallback;
        }
        return parsed;
    }

    /** A money amount written like {@code 1500}, {@code 1.5k} or {@code 2m}. */
    public long money(String path, MoneyFormat format, boolean allowZero, long fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected an amount like 1500, 1.5k or 2m)");
            return fallback;
        }
        MoneyFormat.ParseResult result = format.parse(String.valueOf(value), allowZero);
        if (!result.ok()) {
            problem(path, switch (result.error()) {
                case EMPTY -> "is empty";
                case NOT_A_NUMBER -> "must be an amount like 1500, 1.5k or 2m, got '" + value + "'";
                case NOT_WHOLE -> "must be a whole amount, got '" + value + "'";
                case NOT_POSITIVE -> "must be " + (allowZero ? "zero or more" : "more than zero") + ", got '" + value + "'";
                case TOO_LARGE -> "is larger than the maximum of " + format.maxAmount();
            });
            return fallback;
        }
        return result.amount();
    }

    public List<String> stringList(String path, List<String> fallback) {
        Object value = raw(path);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o == null || o instanceof Map<?, ?> || o instanceof List<?>) {
                    problem(path, "must be a list of text values");
                    return fallback;
                }
                result.add(String.valueOf(o));
            }
            return result;
        }
        problem(path, value == null ? "is missing (expected a list)" : "must be a list, got '" + value + "'");
        return fallback;
    }

    public List<String> optionalStringList(String path) {
        return raw(path) == null ? List.of() : stringList(path, List.of());
    }

    public <E extends Enum<E>> E enumValue(String path, Class<E> type, E fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected one of " + names(type) + ")");
            return fallback;
        }
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        problem(path, "must be one of " + names(type) + ", got '" + value + "'");
        return fallback;
    }

    private static String names(Class<? extends Enum<?>> type) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            names.add(constant.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return String.join(", ", names);
    }

    /** A namespaced key; a bare value gets the {@code minecraft:} namespace. */
    public Key key(String path, Key fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected a key like minecraft:stone)");
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        try {
            return text.contains(":") ? Key.key(text) : Key.key(Key.MINECRAFT_NAMESPACE, text);
        } catch (RuntimeException e) {
            problem(path, "is not a valid key, got '" + value + "'");
            return fallback;
        }
    }

    /**
     * Reads a value with a custom parser. The parser throws {@link IllegalArgumentException} with a short reason
     * on bad input.
     */
    public <T> T custom(String path, Function<String, T> parser, String expected, T fallback) {
        Object value = raw(path);
        if (value == null) {
            problem(path, "is missing (expected " + expected + ")");
            return fallback;
        }
        try {
            return parser.apply(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            problem(path, (e.getMessage() == null ? "is invalid" : e.getMessage()) + ", got '" + value + "' (expected " + expected + ")");
            return fallback;
        }
    }
}
