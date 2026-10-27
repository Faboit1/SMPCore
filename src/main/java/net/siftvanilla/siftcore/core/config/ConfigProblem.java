package net.siftvanilla.siftcore.core.config;

/** One precise problem found while validating a config or lang file. */
public record ConfigProblem(String file, String path, String message) {

    @Override
    public String toString() {
        return this.file + ": '" + this.path + "' " + this.message;
    }
}
