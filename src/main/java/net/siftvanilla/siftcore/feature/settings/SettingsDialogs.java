package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingTexts;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The settings dialogs, built from the core settings registry ({@link PlayerSettings}) every time they open, so any
 * feature's settings appear without changes here:
 * <ul>
 *   <li>the group list: one button per {@link SettingCategory} that holds a setting the player may see, in category
 *       order (settings registered without a category are in the General group at the end);</li>
 *   <li>a group's page: a form with one input per setting (its label and description above): a switch for a toggle,
 *       a cycling button for a choice (only the options the player may pick) and a slider for a number (its unit in
 *       the label). A setting the server locked shows its value as text instead of an input. Pages hold
 *       {@code page-size} settings; changes made on one page are carried to the others and saved together, Back
 *       leaves without saving.</li>
 * </ul>
 * Saving stores only the settings the player changed (never the stale value a dialog showed), each re-checked by the
 * registry (still offered, still allowed, not locked, not cancelled by a listener). Runs on the player's thread.
 */
final class SettingsDialogs {

    private static final int BUTTON_WIDTH = 150;

    /** A group as one player sees it: the category and the settings in it they may see. */
    record Shown(SettingCategory category, List<Registry.Entry<?>> entries) {
    }

    private final Services services;
    private final Lang lang;
    private final Setting<SettingsConfig> config;

    SettingsDialogs(Services services, Setting<SettingsConfig> config) {
        this.services = services;
        this.lang = services.lang();
        this.config = config;
    }

    /** The groups the player sees, in order, each with at least one setting. Safe from any thread. */
    List<Shown> groups(Player player) {
        PlayerSettings settings = this.services.settings();
        Registry registry = settings.registry();
        List<Shown> shown = new ArrayList<>();
        for (SettingCategory category : registry.categories()) {
            List<Registry.Entry<?>> visible = new ArrayList<>();
            for (Registry.Entry<?> entry : registry.in(category.id())) {
                if (settings.visible(entry, player::hasPermission)) {
                    visible.add(entry);
                }
            }
            if (!visible.isEmpty()) {
                shown.add(new Shown(category, List.copyOf(visible)));
            }
        }
        return shown;
    }

    /** Opens the settings: the group list, or the only group. {@code back} runs on Back; null shows Close. */
    void open(Player player, Button.Handler back) {
        List<Shown> groups = groups(player);
        if (groups.isEmpty()) {
            this.services.dialogs().show(player, this.services.templates().notice(this.lang.get(SettingsMessages.TITLE),
                List.of(this.lang.get(SettingsMessages.EMPTY)), back));
            return;
        }
        if (groups.size() == 1 && this.config.get().skipSingleGroup()) {
            showGroup(player, groups.getFirst().category().id(), 1, Map.of(), back);
            return;
        }
        showList(player, groups, back);
    }

    /** Opens one group by id (any case); returns false when the player sees no setting in a group of that id. */
    boolean openGroup(Player player, String id, Button.Handler back) {
        for (Shown group : groups(player)) {
            if (group.category().id().equalsIgnoreCase(id)) {
                showGroup(player, group.category().id(), 1, Map.of(), back);
                return true;
            }
        }
        return false;
    }

    private boolean listed(List<Shown> groups) {
        return groups.size() > 1 || !this.config.get().skipSingleGroup();
    }

    private void showList(Player player, List<Shown> groups, Button.Handler back) {
        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(SettingsMessages.GROUPS_INTRO));
        List<Button> buttons = new ArrayList<>(groups.size());
        for (Shown group : groups) {
            SettingCategory category = group.category();
            lines.add(this.lang.get(SettingsMessages.GROUP_LINE, Arg.component("sprite", icon(category)),
                Arg.text("label", this.lang.plain(category.label())), Arg.text("description", this.lang.plain(category.description()))));
            buttons.add(new Button(Component.text(this.lang.plain(category.label())), this.lang.get(category.description()),
                BUTTON_WIDTH, s -> showGroup(s.player(), category.id(), 1, Map.of(), back)));
        }
        this.services.dialogs().show(player, this.services.templates().list(this.lang.get(SettingsMessages.TITLE), lines,
            buttons, 2, back));
    }

    /** The category's icon followed by a space, or nothing when it has none (or it does not resolve). */
    private Component icon(SettingCategory category) {
        Icons icons = this.lang.style().icons();
        if (category.icon() == null || !icons.has(category.icon())) {
            return Component.empty();
        }
        return icons.component(category.icon()).append(Component.space());
    }

    /**
     * One page of a group. {@code pending} holds the settings changed on other pages and not saved yet (setting id to
     * the chosen value in stored form).
     */
    private void showGroup(Player player, String id, int page, Map<String, String> pending, Button.Handler back) {
        List<Shown> groups = groups(player);
        Shown group = null;
        for (Shown candidate : groups) {
            if (candidate.category().id().equals(id)) {
                group = candidate;
            }
        }
        if (group == null) {
            // Nothing left to show in this group (a permission went away): start over.
            open(player, back);
            return;
        }
        PlayerSettings settings = this.services.settings();
        boolean listed = listed(groups);
        int pageSize = this.config.get().pageSize();
        int pages = SettingsForm.pages(group.entries().size(), pageSize);
        int current = SettingsForm.clampPage(page, group.entries().size(), pageSize);
        List<Registry.Entry<?>> onPage = SettingsForm.page(group.entries(), current, pageSize);

        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(SettingsMessages.INTRO));
        List<Input> inputs = new ArrayList<>(onPage.size());
        List<SettingsForm.Field> fields = new ArrayList<>(onPage.size());
        List<String> pageIds = new ArrayList<>(onPage.size());
        for (Registry.Entry<?> entry : onPage) {
            String label = this.lang.plain(entry.setting().label());
            if (settings.locked(entry.setting())) {
                lines.add(this.lang.get(SettingsMessages.LOCKED_LINE, Arg.text("label", label), Arg.text("value", displayed(player, entry))));
                continue;
            }
            String shown = pending.getOrDefault(entry.id(), encoded(player, entry));
            lines.add(this.lang.get(SettingsMessages.LINE, Arg.text("label", label),
                Arg.text("description", this.lang.plain(entry.setting().description()))));
            inputs.add(input(player, entry, shown));
            fields.add(new SettingsForm.Field(entry.inputKey(), entry.id(), entry.setting().kind(), shown));
            pageIds.add(entry.id());
        }
        if (pages > 1) {
            lines.add(this.lang.get(SettingsMessages.PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        long elsewhere = SettingsForm.effective(pending, setting -> currentEncoded(player, setting)).keySet().stream()
            .filter(setting -> !pageIds.contains(setting)).count();
        if (elsewhere > 0) {
            lines.add(this.lang.get(SettingsMessages.PENDING, Arg.number("count", elsewhere)));
        }

        List<Button> buttons = new ArrayList<>(3);
        Button save = Button.of(this.lang.get(SettingsMessages.SAVE), s -> {
            save(s.player(), SettingsForm.merge(pending, fields, s.values().asMap()));
            if (listed) {
                showList(s.player(), groups(s.player()), back);
            }
        }).width(BUTTON_WIDTH);
        // Without a list to return to, saving finishes: the dialog closes at once.
        buttons.add(listed ? save : save.closes());
        if (current > 1) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.PREVIOUS), s -> showGroup(s.player(), id, current - 1,
                SettingsForm.merge(pending, fields, s.values().asMap()), back)).width(BUTTON_WIDTH));
        }
        if (current < pages) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.NEXT), s -> showGroup(s.player(), id, current + 1,
                SettingsForm.merge(pending, fields, s.values().asMap()), back)).width(BUTTON_WIDTH));
        }
        Button exit;
        if (listed) {
            exit = Button.of(this.lang.get(CoreMessages.UI_BACK), s -> showList(s.player(), groups(s.player()), back));
        } else if (back != null) {
            exit = Button.of(this.lang.get(CoreMessages.UI_BACK), back);
        } else {
            exit = Button.of(this.lang.get(CoreMessages.UI_CLOSE), null);
        }
        Component title = listed
            ? this.lang.get(SettingsMessages.GROUP_TITLE, Arg.text("label", this.lang.plain(group.category().label())))
            : this.lang.get(SettingsMessages.TITLE);
        Body body = Body.text(Component.join(JoinConfiguration.newlines(), lines));
        this.services.dialogs().show(player, new View(View.Kind.FORM, title, List.of(body), inputs, buttons,
            exit.width(Templates.WIDE), 2, true));
    }

    /** The input for one setting, showing {@code shown} (stored form). */
    private <T> Input input(Player player, Registry.Entry<T> entry, String shown) {
        String label = this.lang.plain(entry.setting().label());
        return switch (entry.setting()) {
            case Toggle toggle -> Templates.toggle(entry.inputKey(), Component.text(label), Boolean.parseBoolean(shown));
            case Choice<T> choice -> {
                List<Input.Option> options = new ArrayList<>();
                for (Choice.Option<T> option : this.services.settings().options(entry, player::hasPermission)) {
                    options.add(new Input.Option(option.id(), Component.text(option.text(this.lang))));
                }
                if (options.isEmpty()) {
                    Choice.Option<T> only = choice.option(shown);
                    options.add(new Input.Option(only == null ? choice.encode(choice.defaultValue()) : only.id(),
                        Component.text(only == null ? choice.display(this.lang, choice.defaultValue()) : only.text(this.lang))));
                }
                yield Templates.choice(entry.inputKey(), Component.text(label), options, shown);
            }
            case NumberSetting number -> {
                String unit = number.unit() == null ? "" : this.lang.plain(number.unit()).strip();
                String text = unit.isEmpty() ? label : label + " (" + unit + ")";
                yield Templates.range(entry.inputKey(), Component.text(text), number.min(), number.max(), number.step(),
                    number.decode(shown).orElse(number.defaultValue()));
            }
        };
    }

    /** A setting's value for the player (their permissions applied) in stored form. */
    private <T> String encoded(Player player, Registry.Entry<T> entry) {
        return entry.setting().encode(this.services.settings().get(player, entry.setting()));
    }

    /** A setting's value for the player as text ("On", "Everyone", "30%"). */
    private <T> String displayed(Player player, Registry.Entry<T> entry) {
        return entry.setting().display(this.lang, this.services.settings().get(player, entry.setting()));
    }

    /** A setting's value now in stored form, or null when no setting has that id any more. */
    private String currentEncoded(Player player, String id) {
        Registry.Entry<?> entry = this.services.settings().registry().entry(id);
        return entry == null ? null : encoded(player, entry);
    }

    /**
     * Saves the pending changes that still change something, each through the registry's checks; confirms on the
     * action bar (or wherever the player's feedback goes) and names a setting that couldn't be changed.
     */
    private void save(Player player, Map<String, String> pending) {
        PlayerSettings settings = this.services.settings();
        Map<String, String> changes = SettingsForm.effective(pending, id -> currentEncoded(player, id));
        Registry.Entry<?> last = null;
        String refused = null;
        int saved = 0;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Registry.Entry<?> entry = settings.registry().entry(change.getKey());
            if (entry == null) {
                continue;
            }
            SetResult result = apply(player, entry, change.getValue());
            if (result == SetResult.CHANGED) {
                last = entry;
                saved++;
            } else if (result != SetResult.UNCHANGED) {
                refused = this.lang.plain(entry.setting().label());
            }
        }
        var messenger = this.services.messenger();
        if (saved == 1) {
            String label = this.lang.plain(last.setting().label());
            if (last.setting() instanceof Toggle toggle) {
                messenger.send(player, SettingsMessages.SAVED_ONE, Arg.text("label", label),
                    Arg.text("state", this.lang.plain(settings.get(player, toggle) ? SettingTexts.STATE_ON : SettingTexts.STATE_OFF)));
            } else {
                messenger.send(player, SettingsMessages.SAVED_VALUE, Arg.text("label", label), Arg.text("value", displayed(player, last)));
            }
        } else if (saved > 1) {
            messenger.send(player, SettingsMessages.SAVED_MANY, Arg.number("count", saved));
        }
        if (refused != null) {
            messenger.send(player, SettingsMessages.REFUSED, Arg.text("label", refused));
        } else if (saved == 0) {
            messenger.send(player, SettingsMessages.UNCHANGED);
        }
    }

    private <T> SetResult apply(Player player, Registry.Entry<T> entry, String encoded) {
        T value = entry.setting().decodeOrNull(encoded);
        if (value == null) {
            return SetResult.INVALID;
        }
        return this.services.settings().set(player, entry.setting(), value, Change.dialog(player.getName()));
    }
}
