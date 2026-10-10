package net.siftvanilla.siftcore.core.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds the current parsed settings of one config file. Features keep the holder and call {@link #get()} whenever
 * they need a value, so a successful reload is picked up everywhere without re-wiring.
 */
public final class Setting<S> {

    private volatile S value;
    private final List<Consumer<S>> listeners = new CopyOnWriteArrayList<>();

    Setting(S initial) {
        this.value = initial;
    }

    public S get() {
        return this.value;
    }

    /** Runs after every successful reload (and not on the initial load). */
    public void onReload(Consumer<S> listener) {
        this.listeners.add(listener);
    }

    void set(S value) {
        this.value = value;
        for (Consumer<S> listener : this.listeners) {
            listener.accept(value);
        }
    }
}
