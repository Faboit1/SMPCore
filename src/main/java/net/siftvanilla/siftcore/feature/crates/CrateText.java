package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;

/**
 * Pieces of text every crate message shares: crate names in their crate's colour, key amounts, and reward names in
 * their rarity's colour (money in the money colour, shards in the shards colour).
 */
final class CrateText {

    private final Lang lang;
    private final Supplier<CratesSettings> settings;

    CrateText(Lang lang, Supplier<CratesSettings> settings) {
        this.lang = lang;
        this.settings = settings;
    }

    Lang lang() {
        return this.lang;
    }

    /** The crate's name in its colour ({@code Common}). */
    static Component name(Crate crate) {
        return Component.text(crate.name(), crate.color());
    }

    /** The name of a crate id: the crate's coloured name, or the id itself when the crate is gone. */
    Component name(String crateId) {
        Crate crate = this.settings.get().crate(crateId);
        return crate == null ? Component.text(crateId) : name(crate);
    }

    /** {@code <name>} as the crate's coloured name. */
    static Arg nameArg(Crate crate) {
        return Arg.component("name", name(crate));
    }

    /** {@code 1 Common key}, {@code 3 Common keys}, the crate's name in its colour. */
    Component keys(long count, Crate crate) {
        return keys(count, name(crate));
    }

    /** The same for a crate id (the id itself when the crate is gone). */
    Component keys(long count, String crateId) {
        return keys(count, name(crateId));
    }

    private Component keys(long count, Component name) {
        return count == 1
            ? this.lang.get(CratesMessages.KEYS_ONE, Arg.component("name", name))
            : this.lang.get(CratesMessages.KEYS_MANY, Arg.number("count", count), Arg.component("name", name));
    }

    /** No keys, 1 key, 3 keys. */
    Component count(long count) {
        if (count <= 0) {
            return this.lang.get(CratesMessages.COUNT_NONE);
        }
        return count == 1 ? this.lang.get(CratesMessages.COUNT_ONE) : this.lang.get(CratesMessages.COUNT_MANY, Arg.number("count", count));
    }

    /** The colour a reward's name is shown in: its rarity's colour. */
    TextColor color(Reward reward) {
        return this.settings.get().rarity(reward.rarity()).color();
    }

    /** The rarity's label in its colour ({@code Legendary}). */
    static Component rarity(Rarity rarity) {
        return Component.text(rarity.label(), rarity.color());
    }

    /**
     * The reward's name for messages: its display text, inserted literally, in its rarity's colour. A money reward
     * without its own display text shows the amount in the money colour, and shards are always in the shards colour.
     */
    Component reward(Reward reward) {
        if (reward.kind() instanceof Reward.Money money && !reward.customDisplay()) {
            return this.lang.moneyComponent(money.amount());
        }
        if (reward.kind() instanceof Reward.Shards) {
            return Component.text(reward.display(), this.lang.style().palette().shards());
        }
        return Component.text(reward.display(), color(reward));
    }

    /**
     * {@link #reward} as a message argument for the messenger: a money reward stays an amount, so each reader gets it
     * in their own money format (an announcement made while the winner's screen is rendered must not carry the
     * winner's choice).
     */
    Arg rewardArg(String name, Reward reward) {
        if (reward.kind() instanceof Reward.Money money && !reward.customDisplay()) {
            return Arg.money(name, money.amount());
        }
        return Arg.component(name, reward(reward));
    }

    /** One line per reward won over several openings, in the order first won: {@code 16 iron ingots, 3 times}. */
    List<Component> wins(List<CrateOpener.Won> wins) {
        Map<String, Reward> rewards = new LinkedHashMap<>();
        Map<String, Integer> times = new LinkedHashMap<>();
        for (CrateOpener.Won won : wins) {
            rewards.putIfAbsent(won.reward().id(), won.reward());
            times.merge(won.reward().id(), 1, Integer::sum);
        }
        List<Component> lines = new ArrayList<>(rewards.size());
        rewards.forEach((id, reward) -> {
            int count = times.get(id);
            Arg rewardArg = Arg.component("reward", reward(reward));
            lines.add(count == 1 ? this.lang.get(CratesMessages.BATCH_LINE_ONCE, rewardArg)
                : this.lang.get(CratesMessages.BATCH_LINE, rewardArg, Arg.number("times", count)));
        });
        return lines;
    }
}
