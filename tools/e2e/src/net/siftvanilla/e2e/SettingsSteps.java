package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Lang;

/**
 * Reading and changing settings through the settings dialog as a player does: every setting is a button reading
 * "Label: value" (a switch "ON"/"OFF", a choice its option, a number its value), a click flips a switch or moves a
 * choice to its next option at once, and a number's button opens a slider whose Done stores it. Each button's tooltip
 * lists a choice's options ("• Chat") and says "Set by the server." for a locked setting.
 * <p>
 * Scenarios name settings by their dialog input key ({@code sound_volume}), as they did when the dialog was a form:
 * {@link #read} maps the buttons of a page back to their settings through the registry, and {@link #form} turns a page
 * into the form-shaped view older steps read ({@code inputs()}, {@code toggleValue}, {@code choiceValue},
 * {@code options()}, {@code range}).
 */
final class SettingsSteps {

    private SettingsSteps() {
    }

    /**
     * One setting as a page shows it.
     *
     * @param kind    {@code toggle}, {@code choice} or {@code range} (the old input kinds)
     * @param value   its value in stored form ({@code true}, an option id, a number)
     * @param text    the value as the button shows it ({@code ON}, {@code Above the hotbar}, {@code 60%})
     * @param options for a choice, the option ids its tooltip lists (those the player may pick), in order
     */
    record Shown(Registry.Entry<?> entry, String key, String kind, String value, String text, boolean locked, Bot.Button button,
                 List<String> options) {
    }

    private static Lang lang(E2E e2e) {
        return e2e.services().lang();
    }

    /** Every setting a page shows, by input key, in page order (other buttons, like Reset or Back, are left out). */
    static Map<String, Shown> read(E2E e2e, Bot.SeenDialog page) {
        Registry registry = e2e.services().settings().registry();
        String group = page.title().endsWith(" settings") ? page.title().substring(0, page.title().length() - " settings".length()) : null;
        Map<String, Shown> shown = new LinkedHashMap<>();
        for (Bot.Button button : page.buttons()) {
            Registry.Entry<?> best = null;
            String bestLabel = null;
            for (Registry.Entry<?> entry : registry.entries()) {
                String label = lang(e2e).plain(entry.setting().label());
                if (!button.label().startsWith(label + ": ")) {
                    continue;
                }
                boolean inGroup = group != null && lang(e2e).plain(entry.category().label()).equals(group);
                boolean bestInGroup = best != null && group != null && lang(e2e).plain(best.category().label()).equals(group);
                if (best == null || label.length() > bestLabel.length() || (label.length() == bestLabel.length() && inGroup && !bestInGroup)) {
                    best = entry;
                    bestLabel = label;
                }
            }
            if (best == null) {
                continue;
            }
            String text = button.label().substring(bestLabel.length() + 2);
            shown.put(best.inputKey(), shown(e2e, best, button, text));
        }
        return shown;
    }

    private static Shown shown(E2E e2e, Registry.Entry<?> entry, Bot.Button button, String text) {
        String tooltip = button.tooltip() == null ? "" : button.tooltip();
        boolean locked = tooltip.lines().anyMatch(line -> line.equals("Set by the server."));
        PlayerSetting<?> setting = entry.setting();
        return switch (setting) {
            case Toggle toggle -> new Shown(entry, entry.inputKey(), "toggle", "ON".equals(text) ? "true" : "OFF".equals(text) ? "false" : null,
                text, locked, button, List.of());
            case Choice<?> choice -> {
                List<String> options = new ArrayList<>();
                for (String line : tooltip.lines().toList()) {
                    if (line.startsWith("• ")) {
                        String id = optionId(e2e, choice, line.substring(2));
                        if (id != null) {
                            options.add(id);
                        }
                    }
                }
                yield new Shown(entry, entry.inputKey(), "choice", optionId(e2e, choice, text), text, locked, button, List.copyOf(options));
            }
            case NumberSetting number -> new Shown(entry, entry.inputKey(), "range", number(text), text, locked, button, List.of());
        };
    }

    private static String optionId(E2E e2e, Choice<?> choice, String text) {
        for (Choice.Option<?> option : choice.options()) {
            if (option.text(lang(e2e)).equals(text)) {
                return option.id();
            }
        }
        return null;
    }

    /** The whole number a button shows ("60%", "1,000 keys"), or null. */
    private static String number(String text) {
        StringBuilder digits = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) || (c == '-' && digits.isEmpty())) {
                digits.append(c);
            } else if (c != ',' && !digits.isEmpty()) {
                break;
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    /**
     * The page as the form it used to be: an input per setting that is not locked (a toggle, a choice with the options
     * it offers, or a slider with the number's range), starting on the values the buttons show.
     */
    static Bot.SeenDialog form(E2E e2e, Bot.SeenDialog page) {
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, Boolean> initials = new LinkedHashMap<>();
        Map<String, List<String>> options = new LinkedHashMap<>();
        Map<String, String> choiceInitial = new LinkedHashMap<>();
        Map<String, List<String>> optionLabels = new LinkedHashMap<>();
        Map<String, Bot.RangeSeen> ranges = new LinkedHashMap<>();
        for (Shown shown : read(e2e, page).values()) {
            if (shown.locked()) {
                continue;
            }
            inputs.put(shown.key(), shown.kind());
            switch (shown.entry().setting()) {
                case Toggle toggle -> initials.put(shown.key(), Boolean.parseBoolean(shown.value()));
                case Choice<?> choice -> {
                    options.put(shown.key(), shown.options());
                    choiceInitial.put(shown.key(), shown.value());
                    List<String> labels = new ArrayList<>();
                    for (String id : shown.options()) {
                        labels.add(choice.option(id).text(lang(e2e)));
                    }
                    optionLabels.put(shown.key(), labels);
                }
                case NumberSetting number -> {
                    String unit = number.unit() == null ? "" : lang(e2e).plain(number.unit()).strip();
                    String label = lang(e2e).plain(number.label()) + (unit.isEmpty() ? "" : " (" + unit + ")");
                    ranges.put(shown.key(), new Bot.RangeSeen(number.min(), number.max(), (float) number.step(),
                        shown.value() == null ? number.defaultNumber() : Float.parseFloat(shown.value()), "options.generic_value", label));
                }
            }
        }
        return new Bot.SeenDialog(page.type(), page.title(), page.body(), page.buttons(), inputs, page.at(), page.after(), Map.of(),
            initials, options, choiceInitial, optionLabels, ranges);
    }

    /** Waits for a dialog titled exactly {@code title}. */
    static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals(title), bot.name + " sees '" + title + "': "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** {@code /settings <group>} and waits for the freshly sent page. */
    static Bot.SeenDialog openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        e2e.sleep(700);
        bot.command("settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** The setting with this input key on the page open now; fails when the page doesn't show it. */
    static Shown setting(E2E e2e, Bot bot, String key) {
        Bot.SeenDialog page = bot.dialog();
        e2e.expect(page != null, bot.name + " has a settings page open");
        Shown shown = read(e2e, page).get(key);
        e2e.expect(shown != null, page.title() + " shows " + key + ": " + labels(page));
        return shown;
    }

    private static List<String> labels(Bot.SeenDialog page) {
        return page.buttons().stream().map(Bot.Button::label).toList();
    }

    /**
     * Sets one setting on the page open now the way a player does: a switch is clicked when it shows the other state, a
     * choice is clicked until it shows the option (which its tooltip must list), and a number's button opens the slider,
     * whose Done stores the value. Ends back on the page, showing the value. {@code value} is a Boolean for a switch, an
     * option id for a choice, a number for a number setting.
     */
    static void set(E2E e2e, Bot bot, String key, Object value) {
        String title = bot.dialog().title();
        Shown shown = setting(e2e, bot, key);
        e2e.expect(!shown.locked(), key + " is not locked");
        switch (shown.entry().setting()) {
            case Toggle toggle -> {
                String wanted = Boolean.toString(Boolean.parseBoolean(String.valueOf(value)));
                if (!wanted.equals(shown.value())) {
                    e2e.click(bot, shown.button().label());
                    page(e2e, bot, title);
                }
            }
            case Choice<?> choice -> {
                String wanted = String.valueOf(value);
                e2e.expect(shown.options().contains(wanted), key + " offers " + wanted + ": " + shown.options());
                for (int guard = 0; guard <= Choice.MAX_OPTIONS && !wanted.equals(setting(e2e, bot, key).value()); guard++) {
                    e2e.click(bot, setting(e2e, bot, key).button().label());
                    page(e2e, bot, title);
                }
            }
            case NumberSetting number -> {
                float wanted = Float.parseFloat(String.valueOf(value));
                e2e.click(bot, shown.button().label());
                Bot.SeenDialog slider = page(e2e, bot, lang(e2e).plain(number.label()));
                e2e.expect(slider.range(key) != null, "the slider of " + key + ": " + slider.inputs());
                e2e.click(bot, "Done", Map.of(key, wanted));
                page(e2e, bot, title);
            }
        }
        e2e.expect(String.valueOf(value instanceof Float f ? (long) (float) f : value).equals(setting(e2e, bot, key).value()),
            key + " shows " + value + ": " + setting(e2e, bot, key).text());
    }

    /** Sets several settings on the page open now ({@link #set}). */
    static void apply(E2E e2e, Bot bot, Map<String, Object> wanted) {
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            set(e2e, bot, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Sets the values of {@code values} that differ from what {@code before} (a page read with {@link #form}) showed:
     * for steps that used to change a form's values and press Save.
     */
    static void applyChanged(E2E e2e, Bot bot, Bot.SeenDialog before, Map<String, Object> values) {
        Map<String, Object> shownValues = before.values();
        Map<String, Object> changed = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Object was = shownValues.get(key);
            if (was == null || !String.valueOf(was).equals(String.valueOf(value))) {
                changed.put(key, value);
            }
        });
        apply(e2e, bot, changed);
    }

    /** Opens a group with {@code /settings <group>} and sets settings on it. */
    static void edit(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        openGroup(e2e, bot, group, title);
        apply(e2e, bot, wanted);
    }

    /** Every setting a group shows the player: a choice's option ids, or the kind ({@code toggle}, {@code range}). */
    static Map<String, List<String>> inputs(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog page = openGroup(e2e, bot, group, title);
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        form(e2e, page).inputs().forEach((key, kind) -> inputs.put(key, read(e2e, page).get(key).kind().equals("choice")
            ? read(e2e, page).get(key).options() : List.of(kind)));
        return inputs;
    }
}
