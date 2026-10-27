package net.siftvanilla.siftcore.core.config;

/** Settings holders for tests, without a config file. */
public final class TestSettings {

    private TestSettings() {
    }

    /** A holder with this value, as if the config had just loaded. */
    public static <S> Setting<S> of(S value) {
        return new Setting<>(value);
    }

    /** Replaces the value, as a successful {@code /sift reload} does (reload listeners run). */
    public static <S> void reload(Setting<S> setting, S value) {
        setting.set(value);
    }
}
