package net.siftvanilla.siftcore.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Registry of every config file and its parser. Startup parses each file once (problems are reported and fallbacks
 * used so the server still starts). {@link #reload()} parses everything first and applies nothing unless every file
 * is valid, so a typo can never half-apply a reload.
 */
public final class Configs {

    private record Entry<S>(String file, Function<ConfigReader, S> parser, Setting<S> holder) {
    }

    private final YamlFiles files;
    private final Map<String, Entry<?>> entries = new LinkedHashMap<>();

    public Configs(YamlFiles files) {
        this.files = files;
    }

    public YamlFiles files() {
        return this.files;
    }

    /**
     * Registers a file and parses it now. Problems are appended to {@code problems}; the returned holder then
     * contains the parser's fallback values for the broken keys.
     */
    public synchronized <S> Setting<S> register(String file, Function<ConfigReader, S> parser, List<ConfigProblem> problems) {
        if (this.entries.containsKey(file)) {
            throw new IllegalStateException(file + " is registered twice");
        }
        S initial;
        try {
            YamlConfiguration yaml = this.files.load(file);
            ConfigReader reader = new ConfigReader(file, yaml);
            initial = parser.apply(reader);
            problems.addAll(reader.problems());
        } catch (ConfigException e) {
            problems.addAll(e.problems());
            ConfigReader reader = new ConfigReader(file, this.files.bundled(file));
            initial = parser.apply(reader);
            problems.addAll(reader.problems());
        }
        Setting<S> holder = new Setting<>(initial);
        this.entries.put(file, new Entry<>(file, parser, holder));
        return holder;
    }

    /** Re-parses every file; applies all of them only if none has a problem. Returns the problems (empty = applied). */
    public synchronized List<ConfigProblem> reload() {
        List<ConfigProblem> problems = new ArrayList<>();
        Map<Entry<?>, Object> parsed = new LinkedHashMap<>();
        for (Entry<?> entry : this.entries.values()) {
            try {
                YamlConfiguration yaml = this.files.load(entry.file());
                ConfigReader reader = new ConfigReader(entry.file(), yaml);
                Object value = entry.parser().apply(reader);
                problems.addAll(reader.problems());
                parsed.put(entry, value);
            } catch (ConfigException e) {
                problems.addAll(e.problems());
            } catch (RuntimeException e) {
                problems.add(new ConfigProblem(entry.file(), "(file)", "could not be applied: " + e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        parsed.forEach(Configs::apply);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static <S> void apply(Entry<S> entry, Object value) {
        entry.holder().set((S) value);
    }

    public synchronized List<String> fileNames() {
        return List.copyOf(this.entries.keySet());
    }
}
