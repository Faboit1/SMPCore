package net.siftvanilla.siftcore.feature.settings;

import java.util.function.Function;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The settings catalog as a Markdown table, generated from the live registry ({@code /sift settings catalog}) so the
 * docs always list exactly the settings the running plugin has. Pure.
 */
final class SettingsTable {

    private SettingsTable() {
    }

    /**
     * One table row per setting, group by group in dialog order: id, kind, values, built-in default, group, the
     * permission it needs, and notes (not listed in the dialog, not offered on this server right now).
     *
     * @param text a message as plain text
     */
    static String markdown(Registry registry, Function<MessageKey, String> text, String version) {
        StringBuilder md = new StringBuilder();
        md.append("Every player setting SiftCore ").append(version).append(" registers (").append(registry.byId().size())
            .append("), generated with `/sift settings catalog`. Values are what `/settings`, `/sift settings` and ")
            .append("`features/settings.yml` take; the default is the built-in one (the server can change it).\n\n");
        md.append("| Group | Id | Kind | Values | Default | Label | Permission | Notes |\n|---|---|---|---|---|---|---|---|\n");
        for (SettingCategory category : registry.categories()) {
            for (Registry.Entry<?> entry : registry.in(category.id())) {
                md.append(row(entry, text)).append('\n');
            }
        }
        return md.toString();
    }

    static <T> String row(Registry.Entry<T> entry, Function<MessageKey, String> text) {
        PlayerSetting<T> setting = entry.setting();
        String notes = !entry.options().listed() ? "staff tools and code only"
            : !entry.offered() ? "not offered now (its feature is off or does not read it yet)" : "";
        if (entry.options().apply() == net.siftvanilla.siftcore.core.player.SettingOptions.Apply.INSTANT) {
            notes = notes.isEmpty() ? "applies at once" : notes + "; applies at once";
        }
        return "| " + cell(text.apply(entry.category().label())) + " (`" + entry.category().id() + "`) | `" + entry.id() + "` | "
            + setting.kind().name().toLowerCase(java.util.Locale.ROOT) + " | " + cell(values(setting, text)) + " | `"
            + setting.encode(setting.defaultValue()) + "` | " + cell(text.apply(setting.label())) + " | "
            + (setting.permission() == null ? "" : "`" + setting.permission() + "`") + " | " + cell(notes) + " |";
    }

    /** The values a setting takes, as the table shows them. */
    static String values(PlayerSetting<?> setting, Function<MessageKey, String> text) {
        return switch (setting) {
            case Toggle toggle -> "true, false";
            case Choice<?> choice -> String.join(", ", choice.optionIds());
            case NumberSetting number -> {
                String unit = number.unit() == null ? "" : " (" + text.apply(number.unit()).strip() + ")";
                yield number.min() + "-" + number.max() + (number.step() == 1 ? "" : " step " + number.step()) + unit;
            }
        };
    }

    /** Text safe inside a table cell. */
    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
