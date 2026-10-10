package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;

/** The kits' recurring bits of text: statuses, key lists and name lists, all from {@code lang/kits.yml}. */
final class KitText {

    private final Lang lang;
    private final Function<String, Component> crateName;

    /** Crate keys are named after the capitalized crate id. */
    KitText(Lang lang) {
        this(lang, null);
    }

    /**
     * @param crateName a crate's name as players know it, in its colour ({@code CrateKeys#crateName}); the capitalized
     *                  id when it is null or only knows the id
     */
    KitText(Lang lang, Function<String, Component> crateName) {
        this.lang = lang;
        this.crateName = crateName;
    }

    Lang lang() {
        return this.lang;
    }

    /** A status as a short word or time: "ready", "in 3h 20m", "claimed", or "locked" when not permitted. */
    Component status(KitStatus status, boolean permitted) {
        if (!permitted) {
            return this.lang.get(KitsMessages.STATUS_LOCKED);
        }
        return switch (status) {
            case KitStatus.Ready ready -> this.lang.get(KitsMessages.STATUS_READY);
            case KitStatus.Waiting waiting -> this.lang.get(KitsMessages.STATUS_WAITING, Arg.time("time", waiting.shown()));
            case KitStatus.Claimed claimed -> this.lang.get(KitsMessages.STATUS_CLAIMED);
        };
    }

    /** The status as plain text, for placeholders and logs. */
    String plainStatus(KitStatus status, boolean permitted) {
        return net.siftvanilla.siftcore.core.text.TextStyle.plain(status(status, permitted));
    }

    /** Crate keys: "1 Common key, 2 Rare keys", each crate by the name players know it by (in its colour). */
    Component keys(Map<String, Integer> keys) {
        List<Component> parts = new ArrayList<>(keys.size());
        keys.forEach((crate, amount) -> parts.add(amount == 1
            ? this.lang.get(KitsMessages.KEYS_ONE, Arg.component("crate", crateName(crate)))
            : this.lang.get(KitsMessages.KEYS_MANY, Arg.number("count", amount), Arg.component("crate", crateName(crate)))));
        return join(parts);
    }

    /** The crate's configured name (the shipped {@code basic} crate is called Common), or the capitalized id. */
    private Component crateName(String crate) {
        Component name = this.crateName == null ? null : this.crateName.apply(crate);
        if (name == null || net.siftvanilla.siftcore.core.text.TextStyle.plain(name).equals(crate)) {
            return Component.text(PlainText.capitalize(crate));
        }
        return name;
    }

    /** Kit names: "Daily, Supporter". */
    Component names(List<Kit> kits) {
        List<Component> parts = new ArrayList<>(kits.size());
        for (Kit kit : kits) {
            parts.add(Component.text(kit.name()));
        }
        return join(parts);
    }

    /** Joins parts with the separator from the lang file. */
    Component join(List<Component> parts) {
        return Component.join(JoinConfiguration.separator(this.lang.get(KitsMessages.SEPARATOR)), parts);
    }

    /** A time left rounded up to whole seconds, so it never reads "0s" while something is still waiting. */
    static Duration roundUp(Duration left) {
        long millis = Math.max(0, left.toMillis());
        return Duration.ofSeconds(Math.max(1, (millis + 999) / 1000));
    }
}
