package net.siftvanilla.siftcore.feature.crates;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * What one key of a crate pays out on average, for balancing ({@code /crates info}). Pure logic.
 *
 * @param money        average money per key
 * @param shards       average shards per key
 * @param itemWorth    average sell value of the items per key (what the server would pay for them)
 * @param keys         average keys of each crate per key
 * @param commandShare chance that a key runs a command reward
 */
record ExpectedValue(double money, double shards, double itemWorth, Map<String, Double> keys, double commandShare) {

    /**
     * @param rewards the rewards that can currently be won
     * @param worth   the sell value of an item or spawner reward (0 when it can't be sold)
     */
    static ExpectedValue of(List<Reward> rewards, ToLongFunction<Reward> worth) {
        double total = 0;
        for (Reward reward : rewards) {
            total += reward.weight();
        }
        double money = 0;
        double shards = 0;
        double items = 0;
        double commands = 0;
        Map<String, Double> keys = new LinkedHashMap<>();
        if (total <= 0) {
            return new ExpectedValue(0, 0, 0, Map.of(), 0);
        }
        for (Reward reward : rewards) {
            double chance = reward.weight() / total;
            switch (reward.kind()) {
                case Reward.Money m -> money += chance * m.amount();
                case Reward.Shards s -> shards += chance * s.amount();
                case Reward.Keys k -> keys.merge(k.crate(), chance * k.amount(), Double::sum);
                case Reward.Command c -> commands += chance;
                case Reward.Item i -> items += chance * worth.applyAsLong(reward);
                case Reward.Spawner s -> items += chance * worth.applyAsLong(reward);
            }
        }
        return new ExpectedValue(money, shards, items, java.util.Collections.unmodifiableMap(keys), commands);
    }
}
