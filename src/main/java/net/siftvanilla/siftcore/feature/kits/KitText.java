package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;

/** The kits' recurring bits of text: statuses, key lists and name lists, all from {@code lang/kits.yml}. */
final class KitText {

    private final Lang lang;

    KitText(Lang lang) {
        this.lang = lang;
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

    /** Crate keys: "1 Basic key, 2 Rare keys". Crate ids are shown capitalized. */
    Component keys(Map<String, Integer> keys) {
        List<Component> parts = new ArrayList<>(keys.size());
        keys.forEach((crate, amount) -> parts.add(amount == 1
            ? this.lang.get(KitsMessages.KEYS_ONE, Arg.text("crate", PlainText.capitalize(crate)))
            : this.lang.get(KitsMessages.KEYS_MANY, Arg.number("count", amount), Arg.text("crate", PlainText.capitalize(crate)))));
        return join(parts);
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
