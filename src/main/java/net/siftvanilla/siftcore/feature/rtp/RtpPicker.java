package net.siftvanilla.siftcore.feature.rtp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;

/**
 * The random teleport picker in the dialog style: nothing above the buttons, one button per place in its own colour
 * (with the time left when it can't be used yet), and what it is in the button's tooltip: how far out players land,
 * the price only when it costs something, and whether it is ready.
 */
final class RtpPicker {

    private RtpPicker() {
    }

    /**
     * A place the player may pick.
     *
     * @param maxRadius how far out the ring reaches inside the world border now
     * @param cooldown  the time left of the player's cooldown (zero when ready)
     */
    record Choice(RtpSettings.Region region, long maxRadius, Duration cooldown) {
    }

    static View view(Lang lang, Templates templates, List<Choice> choices, Function<RtpSettings.Region, Button.Handler> go,
                     Button.Handler back) {
        List<Button> buttons = new ArrayList<>(choices.size());
        for (Choice choice : choices) {
            RtpSettings.Region region = choice.region();
            // The picker shows the price, so starting from it never asks again; the window closes as the warmup starts.
            buttons.add(Button.of(label(lang, choice), tooltip(lang, choice), go.apply(region)).closes());
        }
        return choices.size() > 3
            ? templates.grid(lang.get(RtpMessages.MENU_TITLE), buttons, back)
            : templates.column(lang.get(RtpMessages.MENU_TITLE), buttons, back);
    }

    /** The place's name in its colour, then "(42s)" while it can't be used yet. */
    static Component label(Lang lang, Choice choice) {
        RtpSettings.Region region = choice.region();
        Component name = Component.text(region.name(), region.color() != null ? region.color() : lang.style().palette().primary());
        if (choice.cooldown().isZero()) {
            return name;
        }
        return name.append(lang.get(RtpMessages.MENU_BUTTON_WAIT, Arg.text("time", Durations.format(choice.cooldown()))));
    }

    /** How far out, the price (only when there is one), and whether it is ready. */
    static Component tooltip(Lang lang, Choice choice) {
        RtpSettings.Region region = choice.region();
        List<Component> lines = new ArrayList<>(3);
        lines.add(lang.get(RtpMessages.MENU_RANGE, Arg.text("min", Lang.number(region.minRadius())),
            Arg.text("max", Lang.number(choice.maxRadius()))));
        if (region.cost() > 0) {
            lines.add(lang.get(RtpMessages.MENU_COST, Arg.money("amount", region.cost())));
        }
        lines.add(choice.cooldown().isZero() ? lang.get(RtpMessages.MENU_READY)
            : lang.get(RtpMessages.MENU_WAIT, Arg.text("time", Durations.format(choice.cooldown()))));
        return Templates.lines(lines);
    }
}
