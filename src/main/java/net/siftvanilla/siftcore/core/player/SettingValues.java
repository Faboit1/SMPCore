package net.siftvanilla.siftcore.core.player;

import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;

/**
 * Setting values as players see them on buttons, coloured the same way everywhere: a switch's ON in the on colour and
 * OFF in the off colour; a choice's option in the accent colour, or in the off colour when it means "nothing" (Off,
 * Nobody, Never); a number with its unit in the accent colour. Money and shard amounts inside an option label keep
 * their own colours ("From $10,000"). {@link PlayerSetting#display} gives the same values as plain text.
 */
public final class SettingValues {

    /** Option ids that switch something off or let nobody in: shown in the off colour. */
    public static final Set<String> OFF_LIKE = Set.of("off", "nobody", "never", "none");

    private SettingValues() {
    }

    /** A value of a setting, coloured. */
    public static <T> Component value(Lang lang, PlayerSetting<T> setting, T value) {
        Palette palette = lang.style().palette();
        return switch (setting) {
            case Toggle toggle -> state(lang, Boolean.TRUE.equals(value));
            case Choice<T> choice -> {
                Choice.Option<T> option = choice.optionOf(value);
                yield option == null ? Component.text(String.valueOf(value), palette.accent()) : option(lang, option);
            }
            case NumberSetting number -> Component.text(number.display(lang, number.cast(value)), palette.accent());
        };
    }

    /** "ON" in the on colour or "OFF" in the off colour. */
    public static Component state(Lang lang, boolean on) {
        return lang.get(on ? CoreMessages.UI_ON : CoreMessages.UI_OFF);
    }

    /** An option's label, coloured: the off colour for {@link #OFF_LIKE} options, else the accent colour. */
    public static Component option(Lang lang, Choice.Option<?> option) {
        return lang.get(option.label(), option.args().toArray(Arg[]::new)).color(tone(lang.style().palette(), option.id()));
    }

    /** The colour an option id is shown in. */
    public static TextColor tone(Palette palette, String optionId) {
        return OFF_LIKE.contains(optionId) ? palette.off() : palette.accent();
    }
}
