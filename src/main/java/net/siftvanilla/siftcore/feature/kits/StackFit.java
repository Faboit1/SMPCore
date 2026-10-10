package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out, without touching the inventory, which whole stacks fit the way the game adds items: first onto
 * matching stacks with room, then into empty slots. A stack either fits completely or is left out (claim box
 * deliveries can't be split). Pure; the caller says how stacks compare.
 */
final class StackFit {

    /** How stacks are read. */
    interface Stacks<S> {
        boolean empty(S stack);

        boolean similar(S a, S b);

        int amount(S stack);

        int maxStack(S stack);
    }

    private StackFit() {
    }

    /**
     * The indexes of {@code incoming} that fit into {@code slots} when added one after another, in order. A stack
     * that does not fit completely is skipped and later (smaller) ones may still fit.
     *
     * @param slots    the inventory's storage slots (null or empty entries are free)
     * @param incoming the stacks to add
     */
    static <S> List<Integer> plan(List<S> slots, List<S> incoming, Stacks<S> stacks) {
        int size = slots.size();
        List<S> kinds = new ArrayList<>(size);
        int[] amounts = new int[size];
        for (int i = 0; i < size; i++) {
            S slot = slots.get(i);
            if (slot == null || stacks.empty(slot)) {
                kinds.add(null);
            } else {
                kinds.add(slot);
                amounts[i] = stacks.amount(slot);
            }
        }
        List<Integer> fitting = new ArrayList<>();
        for (int index = 0; index < incoming.size(); index++) {
            S stack = incoming.get(index);
            if (stack == null || stacks.empty(stack)) {
                fitting.add(index);
                continue;
            }
            List<S> tryKinds = new ArrayList<>(kinds);
            int[] tryAmounts = amounts.clone();
            if (place(tryKinds, tryAmounts, stack, stacks)) {
                kinds = tryKinds;
                amounts = tryAmounts;
                fitting.add(index);
            }
        }
        return fitting;
    }

    /** True when the stack fits completely. */
    static <S> boolean fits(List<S> slots, S stack, Stacks<S> stacks) {
        return plan(slots, List.of(stack), stacks).size() == 1;
    }

    private static <S> boolean place(List<S> kinds, int[] amounts, S stack, Stacks<S> stacks) {
        int max = Math.max(1, stacks.maxStack(stack));
        int remaining = stacks.amount(stack);
        for (int i = 0; i < kinds.size() && remaining > 0; i++) {
            S kind = kinds.get(i);
            if (kind != null && amounts[i] < max && stacks.similar(kind, stack)) {
                int moved = Math.min(max - amounts[i], remaining);
                amounts[i] += moved;
                remaining -= moved;
            }
        }
        for (int i = 0; i < kinds.size() && remaining > 0; i++) {
            if (kinds.get(i) == null) {
                int moved = Math.min(max, remaining);
                kinds.set(i, stack);
                amounts[i] = moved;
                remaining -= moved;
            }
        }
        return remaining <= 0;
    }
}
