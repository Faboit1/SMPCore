package net.siftvanilla.siftcore.feature.crates;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;

/**
 * A crate.
 *
 * @param id      stable id (config key), stored with keys and in the crate log
 * @param name    plain short name ({@code Common}); text says "Common crate" and "Common key"
 * @param icon    item key of the crate's icon
 * @param rewards rewards in file order
 * @param blocks  block positions from {@code features/crates.yml} that act as this crate
 * @param tier    the crate's place in the ladder (1 is the lowest); crates are listed by tier
 * @param color   the crate's colour: its name everywhere, its hologram, particles and opening animation
 */
public record Crate(String id, String name, String icon, List<Reward> rewards, List<BlockKey> blocks, int tier, TextColor color) {

    /** The colour of a crate without one of its own. */
    public static final TextColor DEFAULT_COLOR = TextColor.color(0xFFFFFF);

    public Crate {
        rewards = List.copyOf(rewards);
        blocks = List.copyOf(blocks);
        color = color == null ? DEFAULT_COLOR : color;
    }

    /** A crate without a tier, in the default colour. */
    public Crate(String id, String name, String icon, List<Reward> rewards, List<BlockKey> blocks) {
        this(id, name, icon, rewards, blocks, 0, DEFAULT_COLOR);
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
