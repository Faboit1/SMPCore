package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
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
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.settings.SettingsGroups.Shown;
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
 *   <li>the group list: one button per {@link SettingCategory} that holds a setting the player may see (with how many
 *       settings it holds and how many the player changed), then Search settings and Changed settings;</li>
 *   <li>a page of settings ({@link Scope}: one group, or the results of a search): a form with one input per setting
 *       (its label and description above): a switch for a toggle, a cycling button for a choice (only the options the
 *       player may pick) and a slider for a number (its unit in the label). A setting the server locked shows its value
 *       as text. Pages hold {@code page-size} settings; changes made on one page are carried to the others and saved
 *       together, Back leaves without saving. A group page also offers Reset this category when the player changed
 *       something in it;</li>
 *   <li>the search form, the changed-settings summary (with Reset everything) and the reset confirmations.</li>
 * </ul>
 * Saving stores only the settings the player changed (never the stale value a dialog showed), each re-checked by the
 * registry (still offered, still allowed, not locked, not cancelled by a listener). Buttons show the next screen in
 * place (the dialog stays until it arrives); Save closes the dialog when there is nothing to return to, and Search
 * shows the client's waiting screen while the results are built. Runs on the player's thread.
 */
final class SettingsDialogs {

    private static final int BUTTON_WIDTH = 150;
    /** A reset confirmation names at most this many settings (and says how many more). */
    private static final int RESET_LINES = 12;

    /** Which settings a page shows. */
    sealed interface Scope permits Group, Search {
    }

    /** One group's settings. */
    record Group(String id) implements Scope {
    }

    /** The settings matching a search, best first. */
    record Search(String query) implements Scope {
    }

    private final Services services;
    private final Lang lang;
    private final Setting<SettingsConfig> config;

    SettingsDialogs(Services services, Setting<SettingsConfig> config) {
        this.services = services;
        this.lang = services.lang();
        this.config = config;
    }

    private PlayerSettings settings() {
        return this.services.settings();
    }

    // ------------------------------------------------------------------ what a player sees

    Viewer viewer(Player player) {
        return Viewer.of(settings(), player);
    }

    /** Whether the viewer sees a setting (offered, not hidden by the server, permitted). Safe from any thread. */
    boolean visible(Registry.Entry<?> entry, Viewer viewer) {
        return settings().visible(entry, viewer::has);
    }

    /** Whether the viewer may change a setting now: they see it and the server did not lock it. */
    boolean changeable(Registry.Entry<?> entry, Viewer viewer) {
        return visible(entry, viewer) && !settings().locked(entry.setting());
    }

    /** The groups the viewer sees, in order, each with at least one setting. Safe from any thread. */
    List<Shown> groups(Viewer viewer) {
        return SettingsGroups.groups(settings().registry(), entry -> visible(entry, viewer), this.config.get().categories());
    }

    List<Shown> groups(Player player) {
        return groups(viewer(player));
    }

    /** The settings among {@code entries} the viewer changed from the default. */
    List<Registry.Entry<?>> changed(Viewer viewer, List<Registry.Entry<?>> entries) {
        List<Registry.Entry<?>> changed = new ArrayList<>();
        for (Registry.Entry<?> entry : entries) {
            if (settings().changed(viewer.id(), entry.setting())) {
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
     * {@code back} (a feature's own menu linking to its settings) Back and Save return there; without one (a command)
     * they lead to the group list.
     */
    boolean openGroup(Player player, String id, Button.Handler back) {
        List<Shown> groups = groups(player);
        Shown group = SettingsGroups.find(groups, id);
        if (group == null) {
            return false;
        }
        showPage(player, new Group(group.id()), 1, Map.of(), back != null ? back : home(groups, null), back);
        return true;
    }

    /** The page of its group that holds a setting (for a chat line to open), or null when the player doesn't see it. */
    View settingPage(Viewer viewer, String settingId, Button.Handler back) {
        List<Shown> groups = groups(viewer);
        Shown group = SettingsGroups.holding(groups, settingId);
        if (group == null) {
            return null;
        }
        int page = SettingsGroups.pageOf(group.entries(), settingId, this.config.get().pageSize());
        return pageView(viewer, new Group(group.id()), page, Map.of(), home(groups, back), back);
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

    /** Opens the summary of the settings the player changed. */
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
            showResetAll(player, after, after, back);
            return true;
        }
        Shown group = SettingsGroups.find(groups, target);
        if (group == null || changed(viewer, group.entries()).isEmpty()) {
            return false;
        }
        Consumer<Player> page = p -> showPage(p, new Group(group.id()), 1, Map.of(), up, back);
        showResetGroup(player, group.id(), page, page);
        return true;
    }

    // ------------------------------------------------------------------ the group list

    private void showList(Player player, Button.Handler back) {
        Viewer viewer = viewer(player);
        List<Shown> groups = groups(viewer);
        if (groups.isEmpty()) {
            this.services.dialogs().show(player, this.services.templates().notice(this.lang.get(SettingsMessages.TITLE),
                List.of(this.lang.get(SettingsMessages.EMPTY)), back));
            return;
        }
        if (!listed(groups)) {
            showPage(player, new Group(groups.getFirst().id()), 1, Map.of(), back, back);
            return;
        }
        this.services.dialogs().show(player, listView(viewer, groups, back));
    }

    /** The group list for a viewer who sees more than one group. */
    View listView(Viewer viewer, List<Shown> groups, Button.Handler back) {
        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(SettingsMessages.GROUPS_INTRO));
        int total = 0;
        int changedTotal = 0;
        List<Button> buttons = new ArrayList<>(groups.size() + 2);
        for (Shown group : groups) {
            SettingCategory category = group.category();
            int count = group.entries().size();
            int changed = changed(viewer, group.entries()).size();
            total += count;
            changedTotal += changed;
            String label = this.lang.plain(category.label());
            String description = this.lang.plain(category.description());
            lines.add(this.lang.get(SettingsMessages.GROUP_LINE, Arg.component("sprite", icon(category)), Arg.text("label", label),
                Arg.text("description", description), Arg.number("count", count)));
            buttons.add(new Button(Component.text(label), this.lang.get(SettingsMessages.GROUP_TOOLTIP, Arg.text("description", description),
                Arg.number("count", count), Arg.number("changed", changed)), BUTTON_WIDTH,
                s -> showPage(s.player(), new Group(category.id()), 1, Map.of(), up(back), back)));
        }
        lines.add(1, this.lang.get(SettingsMessages.CHANGED_COUNT, Arg.number("changed", changedTotal), Arg.number("total", total)));
        buttons.add(Button.of(this.lang.get(SettingsMessages.SEARCH_BUTTON),
            s -> showSearchForm(s.player(), "", up(back), back)).width(BUTTON_WIDTH));
        if (changedTotal > 0) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.CHANGED_BUTTON, Arg.number("count", changedTotal)),
                s -> showChanged(s.player(), up(back), back)).width(BUTTON_WIDTH));
        }
        return this.services.templates().list(this.lang.get(SettingsMessages.TITLE), lines, buttons, 2, back);
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

    // ------------------------------------------------------------------ pages of settings

    /**
     * Shows one page of a scope. {@code pending} holds the settings changed on other pages and not saved yet (setting
     * id to the chosen value in stored form); {@code up} is where Back and Save lead (null: Back closes and Save
     * closes the dialog); {@code back} is what the group list's Back does.
     */
    private void showPage(Player player, Scope scope, int page, Map<String, String> pending, Button.Handler up, Button.Handler back) {
        View view = pageView(viewer(player), scope, page, pending, up, back);
        if (view == null) {
            // Nothing left to show (a permission went away, the server hid the settings): start over.
            showList(player, back);
            return;
        }
        this.services.dialogs().show(player, view);
    }

    /** The settings a scope shows, in order (empty when nothing matches or the group is gone). */
    List<Registry.Entry<?>> entries(List<Shown> groups, Scope scope) {
        return switch (scope) {
            case Group group -> {
                Shown shown = SettingsGroups.find(groups, group.id());
                yield shown == null ? List.of() : shown.entries();
            }
            case Search search -> searchResults(groups, search.query());
        };
    }

    /** One page of a scope, or null when the scope shows nothing. */
    View pageView(Viewer viewer, Scope scope, int page, Map<String, String> pending, Button.Handler up, Button.Handler back) {
        List<Shown> groups = groups(viewer);
        List<Registry.Entry<?>> entries = entries(groups, scope);
        if (entries.isEmpty()) {
            return null;
        }
        PlayerSettings settings = settings();
        SettingsConfig config = this.config.get();
        int pageSize = config.pageSize();
        int pages = SettingsForm.pages(entries.size(), pageSize);
        int current = SettingsForm.clampPage(page, entries.size(), pageSize);
        List<Registry.Entry<?>> onPage = SettingsForm.page(entries, current, pageSize);
        boolean search = scope instanceof Search;

        List<Component> lines = new ArrayList<>();
        if (scope instanceof Search(String query)) {
            lines.add(this.lang.get(SettingsMessages.SEARCH_FOUND, Arg.number("count", entries.size()), Arg.text("query", query)));
        } else {
            lines.add(this.lang.get(SettingsMessages.INTRO));
        }
        List<Input> inputs = new ArrayList<>(onPage.size());
        List<SettingsForm.Field> fields = new ArrayList<>(onPage.size());
        List<String> pageIds = new ArrayList<>(onPage.size());
        for (Registry.Entry<?> entry : onPage) {
            String label = this.lang.plain(entry.setting().label());
            String group = this.lang.plain(entry.category().label());
            if (settings.locked(entry.setting())) {
                lines.add(search
                    ? this.lang.get(SettingsMessages.SEARCH_LOCKED_LINE, Arg.text("group", group), Arg.text("label", label),
                        Arg.text("value", displayed(viewer, entry)))
                    : this.lang.get(SettingsMessages.LOCKED_LINE, Arg.text("label", label), Arg.text("value", displayed(viewer, entry))));
                continue;
            }
            Component line = line(entry, label, group, search, config.showDescriptions());
            if (line != null) {
                lines.add(line);
            }
            String shown = pending.getOrDefault(entry.id(), encoded(viewer, entry));
            inputs.add(input(viewer, entry, shown));
            fields.add(new SettingsForm.Field(entry.inputKey(), entry.id(), entry.setting().kind(), shown));
            pageIds.add(entry.id());
        }
        if (pages > 1) {
            lines.add(this.lang.get(SettingsMessages.PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        long elsewhere = SettingsForm.effective(pending, setting -> currentEncoded(viewer, setting)).keySet().stream()
            .filter(setting -> !pageIds.contains(setting)).count();
        if (elsewhere > 0) {
            lines.add(this.lang.get(SettingsMessages.PENDING, Arg.number("count", elsewhere)));
        }

        List<Button> buttons = new ArrayList<>(4);
        Button save = Button.of(this.lang.get(SettingsMessages.SAVE), s -> {
            save(s.player(), SettingsForm.merge(pending, fields, s.values().asMap()));
            if (up != null) {
                up.handle(s);
            }
        }).width(BUTTON_WIDTH);
        // With nothing to return to, saving finishes: the dialog closes at once.
        buttons.add(up == null ? save.closes() : save);
        if (current > 1) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.PREVIOUS), s -> showPage(s.player(), scope, current - 1,
                SettingsForm.merge(pending, fields, s.values().asMap()), up, back)).width(BUTTON_WIDTH));
        }
        if (current < pages) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.NEXT), s -> showPage(s.player(), scope, current + 1,
                SettingsForm.merge(pending, fields, s.values().asMap()), up, back)).width(BUTTON_WIDTH));
        }
        if (scope instanceof Group(String id) && !changed(viewer, entries).isEmpty()) {
            buttons.add(Button.of(this.lang.get(SettingsMessages.RESET_BUTTON), s -> {
                // Only Cancel keeps the unsaved changes: after a reset the page shows the defaults, nothing pending.
                Map<String, String> kept = SettingsForm.merge(pending, fields, s.values().asMap());
                showResetGroup(s.player(), id, p -> showPage(p, scope, 1, Map.of(), up, back),
                    p -> showPage(p, scope, current, kept, up, back));
            }).width(BUTTON_WIDTH));
        }
        Button exit = up == null
            ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null)
            : Button.of(this.lang.get(CoreMessages.UI_BACK), up);
        Component title = switch (scope) {
            // The only group, opened instead of a list of one, is just "Settings".
            case Group group -> listed(groups)
                ? this.lang.get(SettingsMessages.GROUP_TITLE, Arg.text("label", this.lang.plain(entries.getFirst().category().label())))
                : this.lang.get(SettingsMessages.TITLE);
            case Search(String query) -> this.lang.get(SettingsMessages.SEARCH_RESULTS_TITLE, Arg.text("query", query));
        };
        Body body = Body.text(Component.join(JoinConfiguration.newlines(), lines));
        return new View(View.Kind.FORM, title, List.of(body), inputs, buttons, exit.width(Templates.WIDE), 2, true);
    }

    /** The body line of one changeable setting, or null when the page is compact and the setting needs no note. */
    private Component line(Registry.Entry<?> entry, String label, String group, boolean search, boolean descriptions) {
        boolean rejoin = entry.options().apply() == SettingOptions.Apply.REJOIN;
        if (!descriptions) {
            if (search) {
                return this.lang.get(SettingsMessages.SEARCH_LINE_SHORT, Arg.text("group", group), Arg.text("label", label));
            }
            return rejoin ? this.lang.get(SettingsMessages.REJOIN_NOTE, Arg.text("label", label)) : null;
        }
        String description = this.lang.plain(entry.setting().description());
        if (search) {
            return this.lang.get(SettingsMessages.SEARCH_LINE, Arg.text("group", group), Arg.text("label", label),
                Arg.text("description", description));
        }
        return this.lang.get(rejoin ? SettingsMessages.LINE_REJOIN : SettingsMessages.LINE, Arg.text("label", label),
            Arg.text("description", description));
    }

    /** The input for one setting, showing {@code shown} (stored form). */
    private <T> Input input(Viewer viewer, Registry.Entry<T> entry, String shown) {
        String label = this.lang.plain(entry.setting().label());
        return switch (entry.setting()) {
            case Toggle toggle -> Templates.toggle(entry.inputKey(), Component.text(label), Boolean.parseBoolean(shown));
            case Choice<T> choice -> {
                List<Input.Option> options = new ArrayList<>();
                for (Choice.Option<T> option : settings().options(entry, viewer::has)) {
                    options.add(new Input.Option(option.id(), Component.text(option.text(this.lang))));
                }
                if (options.isEmpty()) {
                    Choice.Option<T> only = choice.option(shown);
                    options.add(new Input.Option(only == null ? choice.encode(choice.defaultValue()) : only.id(),
                        Component.text(only == null ? choice.display(this.lang, choice.defaultValue()) : only.text(this.lang))));
                }
                yield Templates.choice(entry.inputKey(), Component.text(label), options, shown);
            }
            case NumberSetting number -> Templates.range(entry.inputKey(), Component.text(numberLabel(number, label)), number.min(),
                number.max(), number.step(), number.decode(shown).orElse(number.defaultValue()));
        };
    }

    /** A slider's label with its unit, like "SiftCore volume (%)" (Bedrock forms show only the label). */
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
        return setting.display(this.lang, settings().defaultValue(setting));
    }

    /** A setting's value now in stored form, or null when no setting has that id any more. */
    private String currentEncoded(Viewer viewer, String id) {
        Registry.Entry<?> entry = settings().registry().entry(id);
        return entry == null ? null : encoded(viewer, entry);
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves the pending changes that still change something, each through the registry's checks; confirms on the
     * action bar (or wherever the player's feedback goes) and names a setting that couldn't be changed.
     */
    private void save(Player player, Map<String, String> pending) {
        PlayerSettings settings = settings();
        Viewer viewer = viewer(player);
        Map<String, String> changes = SettingsForm.effective(pending, id -> currentEncoded(viewer, id));
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
            confirm(player, last);
        } else if (saved > 1) {
            messenger.send(player, SettingsMessages.SAVED_MANY, Arg.number("count", saved));
        }
        if (refused != null) {
            messenger.send(player, SettingsMessages.REFUSED, Arg.text("label", refused));
        } else if (saved == 0) {
            messenger.send(player, SettingsMessages.UNCHANGED);
        }
    }

    /** Says what one setting is now: "Mention alerts turned off." for a switch, "SiftCore volume set to 60%." otherwise. */
    void confirm(Player player, Registry.Entry<?> entry) {
        String label = this.lang.plain(entry.setting().label());
        if (entry.setting() instanceof Toggle toggle) {
            this.services.messenger().send(player, SettingsMessages.SAVED_ONE, Arg.text("label", label),
                Arg.text("state", this.lang.plain(settings().get(player, toggle) ? SettingTexts.STATE_ON : SettingTexts.STATE_OFF)));
        } else {
            this.services.messenger().send(player, SettingsMessages.SAVED_VALUE, Arg.text("label", label),
                Arg.text("value", displayed(viewer(player), entry)));
        }
    }

    private <T> SetResult apply(Player player, Registry.Entry<T> entry, String encoded) {
        T value = entry.setting().decodeOrNull(encoded);
        if (value == null) {
            return SetResult.INVALID;
        }
        return settings().set(player, entry.setting(), value, Change.dialog(player.getName()));
    }

    // ------------------------------------------------------------------ resetting

    /**
     * Asks whether to put a group's changed settings back to their defaults. {@code done} runs after the reset (the
     * group's first page without unsaved changes), {@code cancel} on Cancel or when nothing is left to reset (the page
     * it came from, unsaved changes kept).
     */
    private void showResetGroup(Player player, String groupId, Consumer<Player> done, Consumer<Player> cancel) {
        Viewer viewer = viewer(player);
        Shown group = SettingsGroups.find(groups(viewer), groupId);
        List<Registry.Entry<?>> changed = group == null ? List.of() : changed(viewer, group.entries());
        if (changed.isEmpty()) {
            if (group == null) {
                this.services.messenger().send(player, SettingsMessages.RESET_NOTHING_ALL);
            } else {
                this.services.messenger().send(player, SettingsMessages.RESET_NOTHING, Arg.text("label", this.lang.plain(group.category().label())));
            }
            cancel.accept(player);
            return;
        }
        String label = this.lang.plain(group.category().label());
        this.services.dialogs().show(player, resetView(viewer, this.lang.get(SettingsMessages.RESET_TITLE, Arg.text("label", label)), changed,
            s -> {
                reset(s.player(), changed);
                done.accept(s.player());
            }, s -> cancel.accept(s.player())));
    }

    /** Asks whether to put every changed setting the player sees back to its default. */
    private void showResetAll(Player player, Consumer<Player> done, Consumer<Player> cancel, Button.Handler back) {
        Viewer viewer = viewer(player);
        List<Registry.Entry<?>> changed = changed(viewer, all(groups(viewer)));
        if (changed.isEmpty()) {
            this.services.messenger().send(player, SettingsMessages.RESET_NOTHING_ALL);
            cancel.accept(player);
            return;
        }
        this.services.dialogs().show(player, resetView(viewer, this.lang.get(SettingsMessages.RESET_ALL_TITLE), changed, s -> {
            reset(s.player(), changed);
            done.accept(s.player());
        }, s -> cancel.accept(s.player())));
    }

    private View resetView(Viewer viewer, Component title, List<Registry.Entry<?>> changed, Button.Handler yes, Button.Handler no) {
        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(SettingsMessages.RESET_INTRO));
        for (int i = 0; i < changed.size() && i < RESET_LINES; i++) {
            Registry.Entry<?> entry = changed.get(i);
            lines.add(this.lang.get(SettingsMessages.RESET_LINE, Arg.text("label", this.lang.plain(entry.setting().label())),
                Arg.text("value", displayed(viewer, entry)), Arg.text("default", defaultText(entry))));
        }
        if (changed.size() > RESET_LINES) {
            lines.add(this.lang.get(SettingsMessages.RESET_MORE, Arg.number("count", changed.size() - RESET_LINES)));
        }
        return this.services.templates().confirm(title, lines, this.lang.get(SettingsMessages.RESET_CONFIRM),
            this.lang.get(CoreMessages.UI_CANCEL), yes, no);
    }

    /** Puts settings back to their defaults (deletes their rows) and says so. */
    void reset(Player player, List<Registry.Entry<?>> entries) {
        List<PlayerSetting<?>> list = new ArrayList<>(entries.size());
        for (Registry.Entry<?> entry : entries) {
            list.add(entry.setting());
        }
        int count = settings().reset(player.getUniqueId(), list, Change.reset(player.getName()));
        if (count == 1 && entries.size() == 1) {
            this.services.messenger().send(player, SettingsMessages.RESET_ONE, Arg.text("label", this.lang.plain(entries.getFirst().setting().label())));
        } else {
            this.services.messenger().send(player, SettingsMessages.RESET_MANY, Arg.number("count", Math.max(count, 0)));
        }
    }

    // ------------------------------------------------------------------ changed settings

    private void showChanged(Player player, Button.Handler up, Button.Handler back) {
        this.services.dialogs().show(player, changedView(viewer(player), up, back));
    }

    /** Every setting the viewer changed, group by group, with a button per group and Reset everything. */
    View changedView(Viewer viewer, Button.Handler up, Button.Handler back) {
        List<Component> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        Map<Shown, List<Registry.Entry<?>>> byGroup = new LinkedHashMap<>();
        for (Shown group : groups(viewer)) {
            List<Registry.Entry<?>> changed = changed(viewer, group.entries());
            if (!changed.isEmpty()) {
                byGroup.put(group, changed);
            }
        }
        lines.add(this.lang.get(byGroup.isEmpty() ? SettingsMessages.CHANGED_NONE : SettingsMessages.CHANGED_INTRO));
        Button.Handler self = s -> showChanged(s.player(), up, back);
        Consumer<Player> summary = p -> showChanged(p, up, back);
        byGroup.forEach((group, changed) -> {
            String label = this.lang.plain(group.category().label());
            for (Registry.Entry<?> entry : changed) {
                lines.add(this.lang.get(SettingsMessages.CHANGED_LINE, Arg.text("group", label),
                    Arg.text("label", this.lang.plain(entry.setting().label())), Arg.text("value", displayed(viewer, entry)),
                    Arg.text("default", defaultText(entry))));
            }
            buttons.add(Button.of(this.lang.get(SettingsMessages.CHANGED_GROUP_BUTTON, Arg.text("label", label),
                Arg.number("count", changed.size())), s -> showPage(s.player(), new Group(group.id()), 1, Map.of(), self, back))
                .width(BUTTON_WIDTH));
        });
        if (!byGroup.isEmpty()) {
            // After resetting everything the summary would be empty: go up (to the group list) when there is one.
            Consumer<Player> after = up == null ? summary : p -> showList(p, back);
            buttons.add(Button.of(this.lang.get(SettingsMessages.RESET_ALL_BUTTON),
                s -> showResetAll(s.player(), after, summary, back)).width(BUTTON_WIDTH));
        }
        return this.services.templates().list(this.lang.get(SettingsMessages.CHANGED_TITLE), lines, buttons, 2, up);
    }

    // ------------------------------------------------------------------ search

    private void showSearchForm(Player player, String query, Button.Handler up, Button.Handler back) {
        this.services.dialogs().show(player, searchForm(query, up, back));
    }

    /** The search form: one text field; Search waits for the results. */
    View searchForm(String query, Button.Handler up, Button.Handler back) {
        Input field = Templates.text("query", this.lang.get(SettingsMessages.SEARCH_INPUT), query, SettingsSearch.MAX_QUERY);
        List<Button> buttons = List.of(
            Button.of(this.lang.get(SettingsMessages.SEARCH_SUBMIT), s -> {
                String typed = SettingsSearch.clean(s.values().text("query"));
                if (SettingsSearch.words(typed).isEmpty()) {
                    s.error(this.lang.get(SettingsMessages.SEARCH_EMPTY));
                    return;
                }
                showResults(s.player(), typed, up, back);
            }).width(BUTTON_WIDTH).waits(),
            (up == null ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null) : Button.of(this.lang.get(CoreMessages.UI_BACK), up))
                .width(BUTTON_WIDTH).waits());
        return new View(View.Kind.FORM, this.lang.get(SettingsMessages.SEARCH_TITLE),
            List.of(Body.text(this.lang.get(SettingsMessages.SEARCH_INTRO))), List.of(field), buttons, null, 2, true);
    }

    /** The first page of a search's results, or a notice that nothing matched (Back returns to the form). */
    private void showResults(Player player, String query, Button.Handler up, Button.Handler back) {
        Button.Handler form = s -> showSearchForm(s.player(), query, up, back);
        View view = pageView(viewer(player), new Search(query), 1, Map.of(), form, back);
        if (view == null) {
            view = this.services.templates().notice(this.lang.get(SettingsMessages.SEARCH_TITLE),
                List.of(this.lang.get(SettingsMessages.SEARCH_NONE, Arg.text("query", query))), this.lang.get(CoreMessages.UI_BACK), form);
        }
        this.services.dialogs().show(player, view);
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
