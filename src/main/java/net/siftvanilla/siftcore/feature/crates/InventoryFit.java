package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out, without touching the inventory, whether stacks would all fit the way the game adds items: first onto
 * matching stacks with room, then into empty slots. Pure; the caller says how stacks compare.
 */
final class InventoryFit {

    /** How stacks are read. */
    interface Stacks<S> {
        boolean empty(S stack);

        boolean similar(S a, S b);

        int amount(S stack);

        int maxStack(S stack);
    }

    private InventoryFit() {
    }

    /**
     * True when every stack of {@code incoming} fits into {@code slots} at once.
     *
     * @param slots    the inventory's storage slots (null or empty entries are free)
     * @param incoming the stacks to add
     */
    static <S> boolean fitsAll(List<S> slots, List<S> incoming, Stacks<S> stacks) {
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
        for (S stack : incoming) {
            if (stack == null || stacks.empty(stack)) {
                continue;
            }
            int max = Math.max(1, stacks.maxStack(stack));
            int remaining = stacks.amount(stack);
            for (int i = 0; i < size && remaining > 0; i++) {
                S kind = kinds.get(i);
                if (kind != null && amounts[i] < max && stacks.similar(kind, stack)) {
                    int moved = Math.min(max - amounts[i], remaining);
                    amounts[i] += moved;
                    remaining -= moved;
                }
            }
            for (int i = 0; i < size && remaining > 0; i++) {
                if (kinds.get(i) == null) {
                    int moved = Math.min(max, remaining);
                    kinds.set(i, stack);
                    amounts[i] = moved;
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }
}
