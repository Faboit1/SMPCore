package net.siftvanilla.siftcore.core.config;

import java.util.List;

/** Thrown when a config file cannot be used. Carries every problem found, not just the first. */
public final class ConfigException extends Exception {

    private final List<ConfigProblem> problems;

    public ConfigException(List<ConfigProblem> problems) {
        super(problems.size() + " config problem(s): " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<ConfigProblem> problems() {
        return this.problems;
    }
}
