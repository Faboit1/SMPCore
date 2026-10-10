package net.siftvanilla.siftcore.core.scheduler;

/**
 * A handle to a scheduled task that can be cancelled. Cancelling twice is harmless.
 */
@FunctionalInterface
public interface Task {

    Task NONE = () -> { };

    void cancel();
}
