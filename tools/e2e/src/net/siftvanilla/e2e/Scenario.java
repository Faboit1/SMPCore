package net.siftvanilla.e2e;

/** One end-to-end scenario. */
public interface Scenario {

    String name();

    void run(E2E e2e) throws Exception;
}
