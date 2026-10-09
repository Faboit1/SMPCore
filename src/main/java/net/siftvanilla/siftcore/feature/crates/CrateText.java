package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;

/** Pieces of text every crate message shares: key amounts and reward names. */
final class CrateText {

    private final Lang lang;

    CrateText(Lang lang) {
        this.lang = lang;
    }

    Lang lang() {
        return this.lang;
    }

    /** {@code 1 Basic key}, {@code 3 Basic keys}. */
    Component keys(long count, String crateName) {
        return count == 1
            ? this.lang.get(CratesMessages.KEYS_ONE, Arg.text("name", crateName))
            : this.lang.get(CratesMessages.KEYS_MANY, Arg.number("count", count), Arg.text("name", crateName));
    }

    /** No keys, 1 key, 3 keys. */
    Component count(long count) {
        if (count <= 0) {
            return this.lang.get(CratesMessages.COUNT_NONE);
        }
        return count == 1 ? this.lang.get(CratesMessages.COUNT_ONE) : this.lang.get(CratesMessages.COUNT_MANY, Arg.number("count", count));
    }

    /**
     * The reward's name for messages: its display text, inserted literally. A money reward without its own display
     * text shows the amount in the money colour.
     */
    Component reward(Reward reward) {
        if (reward.kind() instanceof Reward.Money money && !reward.customDisplay()) {
            return this.lang.moneyComponent(money.amount());
        }
        return Component.text(reward.display());
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
        return Arg.component(name, Component.text(reward.display()));
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
