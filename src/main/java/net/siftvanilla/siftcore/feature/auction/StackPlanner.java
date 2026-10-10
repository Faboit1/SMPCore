package net.siftvanilla.siftcore.feature.auction;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which stacks would fit into an inventory, without touching it, the way the game adds items: first onto
 * matching stacks that have room, then into empty slots. Stacks are planned in order and each one either fits
 * completely (and occupies its space for the stacks after it) or is skipped, so claims never hand out half a stack.
 * Pure; the caller says how stacks compare.
 *
 * @param <S> the stack type
 */
public final class StackPlanner<S> {

    /** How the planner reads stacks. */
    public interface Stacks<S> {
        /** True for a missing or empty slot. */
        boolean empty(S stack);

        /** True when the two stacks may merge (same item and data). */
        boolean similar(S a, S b);

        int amount(S stack);

        int maxStack(S stack);
    }

    private final Stacks<S> stacks;

    public StackPlanner(Stacks<S> stacks) {
        this.stacks = stacks;
    }

    /**
     * Returns the indexes (into {@code incoming}) of the stacks that fit, in order.
     *
     * @param slots    the inventory's storage slots (null or empty entries are free)
     * @param incoming the stacks to place
     */
    public List<Integer> fitting(List<S> slots, List<S> incoming) {
        int size = slots.size();
        List<S> kinds = new ArrayList<>(size);
        int[] amounts = new int[size];
        for (int i = 0; i < size; i++) {
            S slot = slots.get(i);
            if (slot == null || this.stacks.empty(slot)) {
                kinds.add(null);
            } else {
                kinds.add(slot);
                amounts[i] = this.stacks.amount(slot);
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < incoming.size(); index++) {
            S stack = incoming.get(index);
            if (stack == null || this.stacks.empty(stack)) {
                result.add(index);
                continue;
            }
            int max = Math.max(1, this.stacks.maxStack(stack));
            int remaining = this.stacks.amount(stack);
            int[] add = new int[size];
            for (int i = 0; i < size && remaining > 0; i++) {
                S kind = kinds.get(i);
                if (kind != null && amounts[i] < max && this.stacks.similar(kind, stack)) {
                    int moved = Math.min(max - amounts[i], remaining);
                    add[i] = moved;
                    remaining -= moved;
                }
            }
            List<Integer> filledEmpty = new ArrayList<>();
            for (int i = 0; i < size && remaining > 0; i++) {
                if (kinds.get(i) == null) {
                    int moved = Math.min(max, remaining);
                    add[i] = moved;
                    remaining -= moved;
                    filledEmpty.add(i);
                }
            }
            if (remaining > 0) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                amounts[i] += add[i];
            }
            for (int i : filledEmpty) {
                kinds.set(i, stack);
            }
            result.add(index);
        }
        return result;
    }

    /** True when every stack fits at once. */
    public boolean fitsAll(List<S> slots, List<S> incoming) {
        return fitting(slots, incoming).size() == incoming.size();
    }
}
