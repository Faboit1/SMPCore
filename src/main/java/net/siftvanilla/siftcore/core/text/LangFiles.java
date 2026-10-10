package net.siftvanilla.siftcore.core.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.config.ConfigException;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.YamlFiles;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads the per-feature lang files {@code lang/<id>.yml} and merges them for {@link Lang}. Each file's top-level
 * keys belong to that file (for example {@code lang/economy.yml} holds {@code economy:}), so problems are reported
 * against the right file.
 */
public final class LangFiles {

    private final YamlFiles files;
    private final List<String> ids;

    public LangFiles(YamlFiles files, List<String> ids) {
        this.files = files;
        this.ids = List.copyOf(ids);
    }

    /** Loads every file and validates every registered key. Returns all problems. */
    public List<ConfigProblem> load(Lang lang) {
        List<ConfigProblem> problems = new ArrayList<>();
        MemoryConfiguration user = new MemoryConfiguration();
        MemoryConfiguration bundled = new MemoryConfiguration();
        Map<String, String> owner = new HashMap<>();
        for (String id : this.ids) {
            String file = "lang/" + id + ".yml";
            YamlConfiguration jar = this.files.bundled(file);
            copy(jar, bundled);
            for (String top : jar.getKeys(false)) {
                owner.put(top, file);
            }
            try {
                copy(this.files.load(file), user);
            } catch (ConfigException e) {
                problems.addAll(e.problems());
                copy(jar, user);
            }
        }
        for (ConfigProblem problem : lang.load(user, bundled, "lang")) {
            String top = problem.path().contains(".") ? problem.path().substring(0, problem.path().indexOf('.')) : problem.path();
            String file = owner.getOrDefault(top, "lang/" + top + ".yml");
            problems.add(new ConfigProblem(problem.file().startsWith("(jar)") ? "(jar) " + file : file, problem.path(), problem.message()));
        }
        return problems;
    }

    private static void copy(YamlConfiguration from, MemoryConfiguration to) {
        for (String key : from.getKeys(true)) {
            if (!from.isConfigurationSection(key)) {
                to.set(key, from.get(key));
            }
        }
    }

    public List<String> ids() {
        return this.ids;
    }
}
