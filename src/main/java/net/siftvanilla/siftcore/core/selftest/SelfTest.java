package net.siftvanilla.siftcore.core.selftest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Collects named checks from every feature and runs them for {@code /sift selftest}. A check returns null when it
 * passes or a short description of what is wrong. Async checks return a future.
 */
public final class SelfTest {

    /** One check result. */
    public record Result(String feature, String name, boolean passed, String detail, long micros) {
    }

    private record Check(String feature, String name, Supplier<CompletableFuture<String>> run) {
    }

    private final List<Check> checks = new ArrayList<>();

    /** A synchronous check that can run on any thread. */
    public void check(String feature, String name, Supplier<String> check) {
        this.checks.add(new Check(feature, name, () -> {
            try {
                return CompletableFuture.completedFuture(check.get());
            } catch (Throwable t) {
                return CompletableFuture.completedFuture("threw " + t);
            }
        }));
    }

    /** An asynchronous check. */
    public void checkAsync(String feature, String name, Supplier<CompletableFuture<String>> check) {
        this.checks.add(new Check(feature, name, check));
    }

    /** Runs every check in registration order and completes with all results. */
    public CompletableFuture<List<Result>> run() {
        List<Result> results = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Check check : this.checks) {
            chain = chain.thenCompose(ignored -> {
                long start = System.nanoTime();
                CompletableFuture<String> future;
                try {
                    future = check.run().get();
                } catch (Throwable t) {
                    future = CompletableFuture.completedFuture("threw " + t);
                }
                return future.handle((detail, error) -> {
                    String problem = error != null ? "threw " + error : detail;
                    synchronized (results) {
                        results.add(new Result(check.feature(), check.name(), problem == null, problem,
                            (System.nanoTime() - start) / 1_000));
                    }
                    return null;
                });
            });
        }
        return chain.thenApply(ignored -> List.copyOf(results));
    }

    public int size() {
        return this.checks.size();
    }
}
