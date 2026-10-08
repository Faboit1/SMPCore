package net.siftvanilla.siftcore.feature.crates;

import java.util.List;

/**
 * A crate.
 *
 * @param id      stable id (config key), stored with keys and in the crate log
 * @param name    plain short name ({@code Basic}); text says "Basic crate" and "Basic key"
 * @param icon    item key of the crate's icon
 * @param rewards rewards in file order
 * @param blocks  block positions from {@code features/crates.yml} that act as this crate
 */
public record Crate(String id, String name, String icon, List<Reward> rewards, List<BlockKey> blocks) {

    public Crate {
        rewards = List.copyOf(rewards);
        blocks = List.copyOf(blocks);
    }

    public Reward reward(String rewardId) {
        for (Reward reward : this.rewards) {
            if (reward.id().equals(rewardId)) {
                return reward;
            }
        }
        return null;
    }
}
