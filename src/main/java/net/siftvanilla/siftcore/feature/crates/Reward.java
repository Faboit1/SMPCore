package net.siftvanilla.siftcore.feature.crates;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One reward of a crate.
 *
 * @param id            stable id (config key), stored in the crate log
 * @param weight        relative weight; the chance is the weight divided by the crate's total
 * @param rarity        rarity id
 * @param display       plain text naming the reward in messages ({@code 8 diamonds}, {@code $5,000})
 * @param customDisplay whether {@code display} was written in the config (otherwise it was generated)
 * @param icon          item key of the preview icon, or null to show the reward's own item
 * @param kind          what the reward gives
 */
public record Reward(String id, double weight, String rarity, String display, boolean customDisplay, String icon, Kind kind) {

    public Reward {
        Objects.requireNonNull(id);
        Objects.requireNonNull(rarity);
        Objects.requireNonNull(display);
        Objects.requireNonNull(kind);
    }

    /** What a reward gives. */
    public sealed interface Kind permits Item, Money, Shards, Keys, Spawner, Command {
    }

    /**
     * Items.
     *
     * @param item     item key ({@code minecraft:diamond})
     * @param amount   how many
     * @param name     plain custom name, or null for the vanilla name
     * @param lore     plain lore lines (may be empty)
     * @param enchants enchantment key to level (stored enchantments for enchanted books)
     */
    public record Item(String item, int amount, String name, List<String> lore, Map<String, Integer> enchants) implements Kind {
        public Item {
            lore = List.copyOf(lore);
            enchants = Map.copyOf(enchants);
        }
    }

    /** Money, created with the ledger source kind {@code crate_reward}. */
    public record Money(long amount) implements Kind {
    }

    /** Shards, created with the ledger source kind {@code crate_reward}. */
    public record Shards(long amount) implements Kind {
    }

    /** Keys of a crate (may be the same crate). */
    public record Keys(String crate, int amount) implements Kind {
    }

    /** Spawner items made by the spawners feature; left out while it can't make this mob's spawner. */
    public record Spawner(String mob, int amount) implements Kind {

        /** The mob id the spawners feature uses ({@code zombie}, not {@code minecraft:zombie}). */
        public String mobId() {
            return this.mob.startsWith("minecraft:") ? this.mob.substring("minecraft:".length()) : this.mob;
        }
    }

    /** Console commands run on the global thread after the reward is stored; {@code %player%} is the winner's name. */
    public record Command(List<String> commands) implements Kind {
        public Command {
            commands = List.copyOf(commands);
        }
    }

    public boolean isMoney() {
        return this.kind instanceof Money;
    }
}
