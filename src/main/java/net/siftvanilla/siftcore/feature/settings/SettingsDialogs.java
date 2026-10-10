package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SettingTexts;
import net.siftvanilla.siftcore.core.player.SettingValues;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.settings.SettingsGroups.Shown;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The settings dialogs, built from the core settings registry ({@link PlayerSettings}) every time they open, so any
 * feature's settings appear without changes here. Every screen is buttons (the dialog style): what a button does is in
 * its tooltip, nothing is written above the buttons, nothing is paged (the dialog scrolls), nothing waits for a Save.
 * <ul>
 *   <li>the group list: one button per {@link SettingCategory} that holds a setting the player may see, in the group's
 *       colour with its icon (tooltip: what it covers, how many settings, how many changed), then Search settings and
 *       Changed settings;</li>
 *   <li>a page of settings ({@link Scope}: one group, the results of a search, or the settings the player changed):
 *       one button per setting showing its value, "Label: value". A switch flips at once ("Sounds: ON" turns to
 *       "Sounds: OFF"), a choice moves to its next option at once (only options the player may pick), and a number
 *       opens a small slider dialog whose Done stores it and comes back. The page shows again with the new value; a
 *       change that is refused (a listener, a lock) shows in red on it. A setting the server locked shows its value
 *       greyed ("Set by the server"). The tooltip says what the setting does, its options or range, its default and
 *       when it takes effect. A group's page ends with Reset this group (when the player changed something in it), the
 *       changed settings with Reset everything; both ask first;</li>
 *   <li>the search form.</li>
 * </ul>
 * Every change goes through the registry with the player's permissions (still offered, still allowed, not locked, not
 * cancelled by a listener). Buttons show the next screen in place (the dialog stays until it arrives), and Search
 * shows the client's waiting screen while the results are built. Runs on the player's thread.
 */
final class SettingsDialogs {

    /** The Reset button's tooltip names at most this many settings (and says how many more). */
    private static final int RESET_LINES = 12;

    /** Which settings a page shows. */
    sealed interface Scope permits Group, Search, Changed {
    }

    /** One group's settings. */
    record Group(String id) implements Scope {
    }

    /** The settings matching a search, best first. */
    record Search(String query) implements Scope {
    }

    /**
     * The settings the player had changed when the list opened ({@link SettingsClicks#snapshot}): one flipped back to
     * its default stays listed until the list opens again.
     */
    record Changed(List<String> ids) implements Scope {
        Changed {
            ids = List.copyOf(ids);
        }
    }

    /** Where a page leads: {@code up} is its Back (null: Close), {@code back} what the group list's Back does. */
    private record Nav(Scope scope, Button.Handler up, Button.Handler back) {
    }

    private final PlayerSettings settings;
    private final Lang lang;
    private final Templates templates;
    private final Messenger messenger;
    private final BiConsumer<Player, View> screens;
    private final Supplier<SettingsConfig> config;

    SettingsDialogs(Services services, Supplier<SettingsConfig> config) {
        this(services.settings(), services.lang(), services.templates(), services.messenger(),
            (player, view) -> services.dialogs().show(player, view), config);
    }

    /** @param screens shows a view to a player (the dialog router) */
    SettingsDialogs(PlayerSettings settings, Lang lang, Templates templates, Messenger messenger, BiConsumer<Player, View> screens,
                    Supplier<SettingsConfig> config) {
        this.settings = settings;
        this.lang = lang;
        this.templates = templates;
        this.messenger = messenger;
        this.screens = screens;
        this.config = config;
    }

    private void show(Player player, View view) {
        this.screens.accept(player, view);
    }

    private Palette palette() {
        return this.lang.style().palette();
    }

    // ------------------------------------------------------------------ what a player sees

    Viewer viewer(Player player) {
        return Viewer.of(this.settings, player);
    }

    /** Whether the viewer sees a setting (offered, not hidden by the server, permitted). Safe from any thread. */
    boolean visible(Registry.Entry<?> entry, Viewer viewer) {
        return this.settings.visible(entry, viewer::has);
    }

    /** Whether the viewer may change a setting now: they see it and the server did not lock it. */
    boolean changeable(Registry.Entry<?> entry, Viewer viewer) {
        return visible(entry, viewer) && !this.settings.locked(entry.setting());
    }

    /** The groups the viewer sees, in order, each with at least one setting. Safe from any thread. */
    List<Shown> groups(Viewer viewer) {
        return SettingsGroups.groups(this.settings.registry(), entry -> visible(entry, viewer), this.config.get().categories());
    }

    List<Shown> groups(Player player) {
        return groups(viewer(player));
    }

    /** The settings among {@code entries} the viewer changed from the default. */
    List<Registry.Entry<?>> changed(Viewer viewer, List<Registry.Entry<?>> entries) {
        List<Registry.Entry<?>> changed = new ArrayList<>();
        for (Registry.Entry<?> entry : entries) {
            if (this.settings.changed(viewer.id(), entry.setting())) {
                changed.add(entry);
            }
        }
        return changed;
    }

    /** Every setting the viewer sees, group by group. */
    List<Registry.Entry<?>> all(List<Shown> groups) {
        List<Registry.Entry<?>> all = new ArrayList<>();
        for (Shown group : groups) {
            all.addAll(group.entries());
        }
        return all;
    }

    private boolean listed(List<Shown> groups) {
        return groups.size() > 1 || !this.config.get().skipSingleGroup();
    }

    /** What Back does on a page opened directly: the group list when there is one, else {@code back} (null: Close). */
    private Button.Handler home(List<Shown> groups, Button.Handler back) {
        return listed(groups) ? s -> showList(s.player(), back) : back;
    }

    // ------------------------------------------------------------------ entry points

    /** Opens the settings: the group list, or the only group. {@code back} runs on Back; null shows Close. */
    void open(Player player, Button.Handler back) {
        showList(player, back);
    }

    /**
     * Opens one group by id (any case); returns false when the player sees no setting in a group of that id. With a
     * {@code back} (a feature's own menu linking to its settings) Back returns there; without one (a command) it leads
     * to the group list.
     */
    boolean openGroup(Player player, String id, Button.Handler back) {
        List<Shown> groups = groups(player);
        Shown group = SettingsGroups.find(groups, id);
        if (group == null) {
            return false;
        }
        showPage(player, new Nav(new Group(group.id()), back != null ? back : home(groups, null), back), null);
        return true;
    }

    /** The page of the group that holds a setting (for a chat line to open), or null when the player doesn't see it. */
    View settingPage(Viewer viewer, String settingId, Button.Handler back) {
        List<Shown> groups = groups(viewer);
        Shown group = SettingsGroups.holding(groups, settingId);
        if (group == null) {
            return null;
        }
        return pageView(viewer, new Nav(new Group(group.id()), home(groups, back), back));
    }

    /** Opens the search: the form when {@code query} is blank, else its results. */
    void openSearch(Player player, String query, Button.Handler back) {
        Button.Handler up = home(groups(player), back);
        String cleaned = SettingsSearch.clean(query);
        if (SettingsSearch.words(cleaned).isEmpty()) {
            showSearchForm(player, cleaned, up, back);
        } else {
            showResults(player, cleaned, up, back);
        }
    }

    /** Opens the settings the player changed. */
    void openChanged(Player player, Button.Handler back) {
        showChanged(player, home(groups(player), back), back);
    }

    /**
     * Opens the confirmation that resets a group ({@code all}: every setting the player sees). Returns false (and shows
     * nothing) when there is no such group or nothing in it to reset.
     */
    boolean openReset(Player player, String target, Button.Handler back) {
        Viewer viewer = viewer(player);
        List<Shown> groups = groups(viewer);
        Button.Handler up = home(groups, back);
        if ("all".equalsIgnoreCase(target)) {
            if (changed(viewer, all(groups)).isEmpty()) {
                return false;
            }
            Consumer<Player> after = p -> showList(p, back);
            showResetAll(player, after, after);
            return true;
        }
        Shown group = SettingsGroups.find(groups, target);
        if (group == null || changed(viewer, group.entries()).isEmpty()) {
            return false;
        }
        Consumer<Player> page = p -> showPage(p, new Nav(new Group(group.id()), up, back), null);
        showResetGroup(player, group.id(), page, page);
        return true;
    }

    // ------------------------------------------------------------------ the group list

    private void showList(Player player, Button.Handler back) {
        Viewer viewer = viewer(player);
        List<Shown> groups = groups(viewer);
        if (groups.isEmpty()) {
            show(player, this.templates.notice(this.lang.get(SettingsMessages.TITLE), List.of(this.lang.get(SettingsMessages.EMPTY)), back));
            return;
        }
        if (!listed(groups)) {
            showPage(player, new Nav(new Group(groups.getFirst().id()), back, back), null);
            return;
        }
        show(player, listView(viewer, groups, back));
    }

    /** The group list for a viewer who sees more than one group: a coloured button per group, Search and Changed. */
    View listView(Viewer viewer, List<Shown> groups, Button.Handler back) {
        int total = 0;
        int changedTotal = 0;
        List<Button> buttons = new ArrayList<>(groups.size() + 2);
        for (Shown group : groups) {
            SettingCategory category = group.category();
            int count = group.entries().size();
            int changed = changed(viewer, group.entries()).size();
            total += count;
            changedTotal += changed;
            Component label = icon(category).append(Component.text(this.lang.plain(category.label()), color(category)));
            Component tooltip = this.lang.get(SettingsMessages.GROUP_TOOLTIP, Arg.text("description", this.lang.plain(category.description())),
                Arg.number("count", count), Arg.number("changed", changed));
            buttons.add(Button.of(label, tooltip, s -> showPage(s.player(), new Nav(new Group(category.id()), up(back), back), null)));
        }
        buttons.add(Button.of(this.lang.get(SettingsMessages.SEARCH_BUTTON), this.lang.get(SettingsMessages.SEARCH_BUTTON_TOOLTIP),
            s -> showSearchForm(s.player(), "", up(back), back)));
        buttons.add(Button.of(this.lang.get(SettingsMessages.CHANGED_BUTTON, Arg.component("count", accent(changedTotal))),
            this.lang.get(SettingsMessages.CHANGED_BUTTON_TOOLTIP, Arg.number("changed", changedTotal), Arg.number("total", total)),
            s -> showChanged(s.player(), up(back), back)));
        return this.templates.grid(this.lang.get(SettingsMessages.TITLE), buttons, back);
    }

    /** Back to the group list. */
    private Button.Handler up(Button.Handler back) {
        return s -> showList(s.player(), back);
    }

    /** The group's icon followed by a space, or nothing when it has none (or it does not resolve). */
    private Component icon(SettingCategory category) {
        Icons icons = this.lang.style().icons();
        String icon = SettingsGroups.icon(category, this.config.get().categories());
        if (icon == null || !icons.has(icon)) {
            return Component.empty();
        }
        return icons.component(icon).append(Component.space());
    }

    /** The group's button colour (the primary text colour when it has none). */
    private TextColor color(SettingCategory category) {
        TextColor color = SettingsGroups.color(category, this.config.get().categories());
        return color == null ? palette().primary() : color;
    }

    private Component accent(long number) {
        return Component.text(Lang.number(number), palette().accent());
    }

    // ------------------------------------------------------------------ pages of settings

    /** Shows a page, with a red line when {@code error} is set; when it shows nothing any more, the group list. */
    private void showPage(Player player, Nav nav, Component error) {
        View view = pageView(viewer(player), nav);
        if (view == null) {
            // Nothing left to show (a permission went away, the server hid the settings): start over.
            showList(player, nav.back());
            return;
        }
        show(player, error == null ? view : view.withError(error, FormValues.EMPTY));
    }

    /** The settings a scope shows, in order (empty when nothing matches or the group is gone). */
    List<Registry.Entry<?>> entries(List<Shown> groups, Scope scope) {
        return switch (scope) {
            case Group group -> {
                Shown shown = SettingsGroups.find(groups, group.id());
                yield shown == null ? List.of() : shown.entries();
            }
            case Search search -> searchResults(groups, search.query());
            case Changed changed -> SettingsClicks.snapshot(all(groups), changed.ids());
        };
    }

    /** A group's page, a search's results or the changed settings; null when a group or search shows nothing. */
    View pageView(Viewer viewer, Scope scope, Button.Handler up, Button.Handler back) {
        return pageView(viewer, new Nav(scope, up, back));
    }

    private View pageView(Viewer viewer, Nav nav) {
        List<Shown> groups = groups(viewer);
        List<Registry.Entry<?>> entries = entries(groups, nav.scope());
        if (entries.isEmpty() && !(nav.scope() instanceof Changed)) {
            return null;
        }
        List<Button> buttons = new ArrayList<>(entries.size() + 1);
        for (Registry.Entry<?> entry : entries) {
            buttons.add(button(viewer, entry, nav));
        }
        List<Registry.Entry<?>> changed = changed(viewer, entries);
        List<Component> lines = new ArrayList<>(1);
        switch (nav.scope()) {
            case Group(String id) when !changed.isEmpty() -> buttons.add(Button.of(this.lang.get(SettingsMessages.RESET_BUTTON),
                this.lang.get(SettingsMessages.RESET_BUTTON_TOOLTIP, Arg.number("count", changed.size())),
                s -> showResetGroup(s.player(), id, p -> showPage(p, nav, null), p -> showPage(p, nav, null))));
            case Changed snapshot when !changed.isEmpty() -> {
                // After resetting everything the list would be empty: go up (to the group list) when there is one.
                Consumer<Player> after = p -> {
                    if (nav.up() != null) {
                        showList(p, nav.back());
                    } else {
                        showChanged(p, null, nav.back());
                    }
                };
                buttons.add(Button.of(this.lang.get(SettingsMessages.RESET_ALL_BUTTON),
                    this.lang.get(SettingsMessages.RESET_ALL_TOOLTIP, Arg.number("count", changed(viewer, all(groups)).size())),
                    s -> showResetAll(s.player(), after, p -> showPage(p, nav, null))));
            }
            case Changed snapshot when entries.isEmpty() -> lines.add(this.lang.get(SettingsMessages.CHANGED_NONE));
            default -> {
            }
        }
        Component title = switch (nav.scope()) {
            // The only group, opened instead of a list of one, is just "Settings".
            case Group group -> listed(groups)
                ? this.lang.get(SettingsMessages.GROUP_TITLE, Arg.text("label", this.lang.plain(entries.getFirst().category().label())))
                : this.lang.get(SettingsMessages.TITLE);
            case Search(String query) -> this.lang.get(SettingsMessages.SEARCH_RESULTS_TITLE, Arg.text("query", query));
            case Changed snapshot -> this.lang.get(SettingsMessages.CHANGED_TITLE);
        };
        return this.templates.column(title, lines, buttons, nav.up());
    }

    /** One setting's button: its value, what a click does, and a tooltip saying what it is. */
    private <T> Button button(Viewer viewer, Registry.Entry<T> entry, Nav nav) {
        PlayerSetting<T> setting = entry.setting();
        Component label = Component.text(this.lang.plain(setting.label()));
        T shown = viewer.value(setting);
        boolean locked = this.settings.locked(setting);
        Component tooltip = Templates.lines(tooltip(viewer, entry, shown, nav.scope(), locked));
        if (locked) {
            // Greyed: the value's own colours (ON green) would make it look like a switch that works.
            return Button.of(this.lang.get(SettingsMessages.BUTTON_LOCKED, Arg.component("label", label),
                Arg.text("value", TextStyle.plain(SettingValues.value(this.lang, setting, shown)))), tooltip,
                s -> showPage(s.player(), nav, null));
        }
        return switch (setting) {
            case Toggle toggle -> this.templates.switchButton(label, Boolean.TRUE.equals(shown), tooltip,
                s -> change(s.player(), entry, setting.cast(SettingsClicks.flipped(Boolean.TRUE.equals(shown))), nav));
            case Choice<T> choice -> this.templates.choiceButton(label, SettingValues.value(this.lang, setting, shown), tooltip,
                s -> cycle(s.player(), entry, choice, shown, nav));
            case NumberSetting number -> this.templates.choiceButton(label, SettingValues.value(this.lang, setting, shown), tooltip,
                s -> showSlider(s.player(), entry, number, nav));
        };
    }

    /** The lines of a setting's tooltip. */
    private <T> List<Component> tooltip(Viewer viewer, Registry.Entry<T> entry, T shown, Scope scope, boolean locked) {
        PlayerSetting<T> setting = entry.setting();
        List<Component> lines = new ArrayList<>();
        if (!(scope instanceof Group)) {
            lines.add(this.lang.get(SettingsMessages.TOOLTIP_GROUP, Arg.component("group",
                Component.text(this.lang.plain(entry.category().label()), color(entry.category())))));
        }
        lines.add(this.lang.get(SettingsMessages.TOOLTIP_DESCRIPTION, Arg.text("description", this.lang.plain(setting.description()))));
        if (locked) {
            lines.add(this.lang.get(SettingsMessages.TOOLTIP_LOCKED));
            return lines;
        }
        switch (setting) {
            case Choice<T> choice -> {
                Choice.Option<T> current = choice.optionOf(shown);
                for (Choice.Option<T> option : this.settings.options(entry, viewer::has)) {
                    lines.add(option == current
                        ? this.lang.get(SettingsMessages.TOOLTIP_OPTION_CURRENT, Arg.component("option", SettingValues.option(this.lang, option)))
                        : this.lang.get(SettingsMessages.TOOLTIP_OPTION, Arg.text("option", option.text(this.lang))));
                }
            }
            case NumberSetting number -> lines.add(number.step() == 1
                ? this.lang.get(SettingsMessages.TOOLTIP_RANGE, Arg.text("min", number.display(this.lang, number.min())),
                    Arg.text("max", number.display(this.lang, number.max())))
                : this.lang.get(SettingsMessages.TOOLTIP_RANGE_STEPS, Arg.text("min", number.display(this.lang, number.min())),
                    Arg.text("max", number.display(this.lang, number.max())), Arg.text("step", number.display(this.lang, number.step()))));
            case Toggle toggle -> {
            }
        }
        lines.add(this.lang.get(SettingsMessages.TOOLTIP_DEFAULT, Arg.component("default",
            SettingValues.value(this.lang, setting, this.settings.defaultValue(setting)))));
        if (entry.options().apply() == SettingOptions.Apply.REJOIN) {
            lines.add(this.lang.get(SettingsMessages.TOOLTIP_REJOIN));
        }
        lines.add(this.lang.get(switch (setting) {
            case Toggle toggle -> SettingsMessages.TOOLTIP_SWITCH;
            case Choice<T> choice -> SettingsMessages.TOOLTIP_CHOICE;
            case NumberSetting number -> SettingsMessages.TOOLTIP_NUMBER;
        }));
        return lines;
    }

    /** Moves a choice to the option after the one its button showed, among those the player may pick now. */
    private <T> void cycle(Player player, Registry.Entry<T> entry, Choice<T> choice, T shown, Nav nav) {
        List<String> offered = new ArrayList<>();
        Map<String, T> values = new HashMap<>();
        for (Choice.Option<T> option : this.settings.options(entry, player::hasPermission)) {
            offered.add(option.id());
            values.put(option.id(), option.value());
        }
        Choice.Option<T> current = choice.optionOf(shown);
        String next = SettingsClicks.next(offered, current == null ? null : current.id());
        if (next == null) {
            showPage(player, nav, null);
            return;
        }
        change(player, entry, values.get(next), nav);
    }

    /** Stores a value the player picked and shows the page again with it (and in red why, when it was refused). */
    private <T> void change(Player player, Registry.Entry<T> entry, T value, Nav nav) {
        SetResult result = value == null ? SetResult.INVALID : this.settings.set(player, entry.setting(), value, Change.dialog(player.getName()));
        Component error = refusal(entry, result);
        if (error != null) {
            this.messenger.feedback(player, Feedback.ERROR);
        }
        showPage(player, nav, error);
    }

    /** Why a change was refused, for the page; null when it went through (or already was so). */
    private Component refusal(Registry.Entry<?> entry, SetResult result) {
        if (result.succeeded()) {
            return null;
        }
        Arg label = Arg.text("label", this.lang.plain(entry.setting().label()));
        return this.lang.get(result == SetResult.LOCKED ? SettingsMessages.LOCKED : SettingsMessages.REFUSED, label);
    }

    /** The small dialog that picks a number with a slider: Done stores it, both buttons come back to the page. */
    private <T> void showSlider(Player player, Registry.Entry<T> entry, NumberSetting number, Nav nav) {
        if (!changeable(entry, viewer(player))) {
            showPage(player, nav, null);
            return;
        }
        String label = this.lang.plain(number.label());
        Input.Range range = new Input.Range(entry.inputKey(), Component.text(numberLabel(number, label)), number.min(), number.max(),
            number.step(), viewer(player).value(number), null, Templates.LONG);
        show(player, this.templates.number(Component.text(label), range,
            s -> change(s.player(), entry, entry.setting().cast(s.values().number(entry.inputKey())), nav),
            s -> showPage(s.player(), nav, null)));
    }

    /** A slider's label with its unit, like "Sound volume (%)" (Bedrock forms show only the label). */
    String numberLabel(NumberSetting number, String label) {
        String unit = number.unit() == null ? "" : this.lang.plain(number.unit()).strip();
        return unit.isEmpty() ? label : label + " (" + unit + ")";
    }

    /** A setting's value for the viewer (their permissions applied) in stored form. */
    <T> String encoded(Viewer viewer, Registry.Entry<T> entry) {
        return entry.setting().encode(viewer.value(entry.setting()));
    }

    /** A setting's value for the viewer as text ("On", "Everyone", "30%"). */
    <T> String displayed(Viewer viewer, Registry.Entry<T> entry) {
        return entry.setting().display(this.lang, viewer.value(entry.setting()));
    }

    /** A setting's default (the server's lock, else its default, else the built-in one) as text. */
    <T> String defaultText(Registry.Entry<T> entry) {
        PlayerSetting<T> setting = entry.setting();
        return setting.display(this.lang, this.settings.defaultValue(setting));
    }

    /**
     * Says what one setting is now, after a command changed it: "Mention alerts turned off." for a switch, "Sound
     * volume set to 60%." otherwise.
     */
    void confirm(Player player, Registry.Entry<?> entry) {
        String label = this.lang.plain(entry.setting().label());
        if (entry.setting() instanceof Toggle toggle) {
            this.messenger.send(player, SettingsMessages.SAVED_ONE, Arg.text("label", label),
                Arg.text("state", this.lang.plain(this.settings.get(player, toggle) ? SettingTexts.STATE_ON : SettingTexts.STATE_OFF)));
        } else {
            this.messenger.send(player, SettingsMessages.SAVED_VALUE, Arg.text("label", label), Arg.text("value", displayed(viewer(player), entry)));
        }
    }

    // ------------------------------------------------------------------ resetting

    /**
     * Asks whether to put a group's changed settings back to their defaults. {@code done} runs after the reset,
     * {@code cancel} on Cancel or when nothing is left to reset.
     */
    private void showResetGroup(Player player, String groupId, Consumer<Player> done, Consumer<Player> cancel) {
        Viewer viewer = viewer(player);
        Shown group = SettingsGroups.find(groups(viewer), groupId);
        List<Registry.Entry<?>> changed = group == null ? List.of() : changed(viewer, group.entries());
        if (changed.isEmpty()) {
            if (group == null) {
                this.messenger.send(player, SettingsMessages.RESET_NOTHING_ALL);
            } else {
                this.messenger.send(player, SettingsMessages.RESET_NOTHING, Arg.text("label", this.lang.plain(group.category().label())));
            }
            cancel.accept(player);
            return;
        }
        String label = this.lang.plain(group.category().label());
        show(player, resetView(viewer, this.lang.get(SettingsMessages.RESET_TITLE, Arg.text("label", label)), changed, s -> {
            reset(s.player(), changed);
            done.accept(s.player());
        }, s -> cancel.accept(s.player())));
    }

    /** Asks whether to put every changed setting the player sees back to its default. */
    private void showResetAll(Player player, Consumer<Player> done, Consumer<Player> cancel) {
        Viewer viewer = viewer(player);
        List<Registry.Entry<?>> changed = changed(viewer, all(groups(viewer)));
        if (changed.isEmpty()) {
            this.messenger.send(player, SettingsMessages.RESET_NOTHING_ALL);
            cancel.accept(player);
            return;
        }
        show(player, resetView(viewer, this.lang.get(SettingsMessages.RESET_ALL_TITLE), changed, s -> {
            reset(s.player(), changed);
            done.accept(s.player());
        }, s -> cancel.accept(s.player())));
    }

    /** The confirmation: how many go back; the Reset button's tooltip names them with their value and default. */
    private View resetView(Viewer viewer, Component title, List<Registry.Entry<?>> changed, Button.Handler yes, Button.Handler no) {
        List<Component> names = new ArrayList<>();
        for (int i = 0; i < changed.size() && i < RESET_LINES; i++) {
            Registry.Entry<?> entry = changed.get(i);
            names.add(this.lang.get(SettingsMessages.RESET_LINE, Arg.text("label", this.lang.plain(entry.setting().label())),
                Arg.text("value", displayed(viewer, entry)), Arg.text("default", defaultText(entry))));
        }
        if (changed.size() > RESET_LINES) {
            names.add(this.lang.get(SettingsMessages.RESET_MORE, Arg.number("count", changed.size() - RESET_LINES)));
        }
        View confirm = this.templates.confirm(title, List.of(this.lang.get(SettingsMessages.RESET_BODY, Arg.number("count", changed.size()))),
            this.lang.get(SettingsMessages.RESET_CONFIRM), this.lang.get(CoreMessages.UI_CANCEL), yes, no);
        List<Button> buttons = new ArrayList<>(confirm.buttons());
        buttons.set(0, buttons.getFirst().tooltip(Templates.lines(names)));
        return new View(confirm.kind(), confirm.title(), confirm.body(), confirm.inputs(), buttons, confirm.exit(), confirm.columns(),
            confirm.escapable());
    }

    /** Puts settings back to their defaults (deletes their rows) and says so. */
    void reset(Player player, List<Registry.Entry<?>> entries) {
        List<PlayerSetting<?>> list = new ArrayList<>(entries.size());
        for (Registry.Entry<?> entry : entries) {
            list.add(entry.setting());
        }
        int count = this.settings.reset(player.getUniqueId(), list, Change.reset(player.getName()));
        if (count == 1 && entries.size() == 1) {
            this.messenger.send(player, SettingsMessages.RESET_ONE, Arg.text("label", this.lang.plain(entries.getFirst().setting().label())));
        } else {
            this.messenger.send(player, SettingsMessages.RESET_MANY, Arg.number("count", Math.max(count, 0)));
        }
    }

    // ------------------------------------------------------------------ changed settings

    private void showChanged(Player player, Button.Handler up, Button.Handler back) {
        show(player, changedView(viewer(player), up, back));
    }

    /** The settings the viewer changed, as buttons, with Reset everything. */
    View changedView(Viewer viewer, Button.Handler up, Button.Handler back) {
        List<String> ids = new ArrayList<>();
        for (Registry.Entry<?> entry : changed(viewer, all(groups(viewer)))) {
            ids.add(entry.id());
        }
        return pageView(viewer, new Nav(new Changed(ids), up, back));
    }

    // ------------------------------------------------------------------ search

    private void showSearchForm(Player player, String query, Button.Handler up, Button.Handler back) {
        show(player, searchForm(query, up, back));
    }

    /** The search form: one text field; Search waits for the results. */
    View searchForm(String query, Button.Handler up, Button.Handler back) {
        Input field = Templates.text("query", this.lang.get(SettingsMessages.SEARCH_INPUT), query, SettingsSearch.MAX_QUERY);
        List<Button> buttons = List.of(
            Button.of(this.lang.get(SettingsMessages.SEARCH_SUBMIT), this.lang.get(SettingsMessages.SEARCH_SUBMIT_TOOLTIP), s -> {
                String typed = SettingsSearch.clean(s.values().text("query"));
                if (SettingsSearch.words(typed).isEmpty()) {
                    s.error(this.lang.get(SettingsMessages.SEARCH_EMPTY));
                    return;
                }
                showResults(s.player(), typed, up, back);
            }).width(Templates.HALF).waits(),
            (up == null ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null) : Button.of(this.lang.get(CoreMessages.UI_BACK), up))
                .width(Templates.HALF).waits());
        return new View(View.Kind.FORM, this.lang.get(SettingsMessages.SEARCH_TITLE), List.<Body>of(), List.of(field), buttons, null, 2, true);
    }

    /** A search's results, or a notice that nothing matched (Back returns to the form). */
    private void showResults(Player player, String query, Button.Handler up, Button.Handler back) {
        Button.Handler form = s -> showSearchForm(s.player(), query, up, back);
        View view = pageView(viewer(player), new Nav(new Search(query), form, back));
        if (view == null) {
            view = this.templates.notice(this.lang.get(SettingsMessages.SEARCH_TITLE),
                List.of(this.lang.get(SettingsMessages.SEARCH_NONE, Arg.text("query", query))), this.lang.get(CoreMessages.UI_BACK), form);
        }
        show(player, view);
    }

    /** The settings among the groups matching a query, best first. */
    List<Registry.Entry<?>> searchResults(List<Shown> groups, String query) {
        Map<String, Registry.Entry<?>> byId = new HashMap<>();
        List<SettingsSearch.Doc> docs = new ArrayList<>();
        for (Shown group : groups) {
            for (Registry.Entry<?> entry : group.entries()) {
                byId.put(entry.id(), entry);
                docs.add(doc(entry));
            }
        }
        List<Registry.Entry<?>> results = new ArrayList<>();
        for (String id : SettingsSearch.match(docs, query)) {
            results.add(byId.get(id));
        }
        return results;
    }

    /** Everything a setting can be found by. */
    SettingsSearch.Doc doc(Registry.Entry<?> entry) {
        PlayerSetting<?> setting = entry.setting();
        List<String> texts = new ArrayList<>();
        texts.add(entry.id());
        texts.add(entry.shortName());
        texts.add(this.lang.plain(setting.description()));
        texts.add(this.lang.plain(entry.category().label()));
        if (setting instanceof Choice<?> choice) {
            for (Choice.Option<?> option : choice.options()) {
                texts.add(option.text(this.lang));
            }
        }
        if (setting instanceof NumberSetting number && number.unit() != null) {
            texts.add(this.lang.plain(number.unit()));
        }
        if (entry.options().keywords() != null) {
            texts.add(this.lang.plain(entry.options().keywords()));
        }
        return new SettingsSearch.Doc(entry.id(), this.lang.plain(setting.label()), texts);
    }
}
