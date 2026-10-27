package net.siftvanilla.siftcore.core;

import java.util.List;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.selftest.SelfTest;

/**
 * A gameplay feature. Features are constructed by the composition root with everything they need (constructor
 * injection), then enabled in order. A feature owns its package, its {@code features/<id>.yml} config, its
 * {@code lang/<id>.yml} text, its commands and listeners.
 */
public interface Feature {

    /** Stable id, also the base name of its config and lang files. */
    String id();

    /** Loads state and registers listeners and timers. Storage is migrated and the ledger is loaded. */
    void enable() throws Exception;

    /** Flushes state and stops timers. Must not throw. */
    default void disable() {
    }

    /** Commands to register (collected once at startup). */
    default List<SiftCommand> commands() {
        return List.of();
    }

    /** Adds this feature's checks to the self-test. */
    default void selfTest(SelfTest test) {
    }
}
