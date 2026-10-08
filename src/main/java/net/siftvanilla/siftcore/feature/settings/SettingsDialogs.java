package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The settings dialogs, built from the core settings registry ({@link PlayerSettings}) every time they open, so any
 * feature's switches appear without changes here:
 * <ul>
 *   <li>the group list: one button per {@link SettingCategory} that holds a switch the player may see, in category
 *       order, with switches registered without a category in a General group at the end;</li>
 *   <li>a group's page: a form with one switch per toggle (its label and description above), paged when the group is
 *       longer than {@code page-size}. Switches flipped on one page are carried to the others and saved together;
 *       Back leaves without saving.</li>
 * </ul>
 * Saving stores only the switches the player flipped (never the stale value a dialog showed), re-checking that each
 * toggle still exists and is still allowed. Runs on the player's thread.
 */
final class SettingsDialogs {

    /** The id of the group for switches registered without a category. */
    static final String GENERAL_ID = "general";
    private static final int BUTTON_WIDTH = 150;

    /** A group as one player sees it: the category and its switches with their stored values. */
    record Shown(SettingCategory category, List<Map.Entry<Toggle, Boolean>> toggles) {
    }

    private final Services services;
    private final Lang lang;
    private final Setting<SettingsConfig> config;
    private final SettingCategory general = new SettingCategory(GENERAL_ID, Integer.MAX_VALUE, SettingsMessages.GENERAL,
        SettingsMessages.GENERAL_DESCRIPTION);

    SettingsDialogs(Services services, Setting<SettingsConfig> config) {
        this.services = services;
        this.lang = services.lang();
        this.config = config;
    }

    /** The group used for switches registered without a category. */
    SettingCategory general() {
        return this.general;
    }

    /** The groups the player sees, in order, each with at least one switch. Safe from any thread. */
    List<Shown> groups(Player player) {
        PlayerSettings settings = this.services.settings();
        Map<String, SettingCategory> categories = new LinkedHashMap<>();
        for (SettingCategory category : settings.categories()) {
            categories.put(category.id(), category);
        }
        categories.putIfAbsent(GENERAL_ID, this.general);
        List<Map.Entry<Toggle, Boolean>> view = settings.view(player.getUniqueId(), player::hasPermission);
        List<SettingsForm.Group<Map.Entry<Toggle, Boolean>>> groups = SettingsForm.group(view, entry -> {
            SettingCategory category = settings.category(entry.getKey());
            return category == null ? GENERAL_ID : category.id();
        }, List.copyOf(categories.keySet()));
        List<Shown> shown = new ArrayList<>(groups.size());
        for (SettingsForm.Group<Map.Entry<Toggle, Boolean>> group : groups) {
            shown.add(new Shown(categories.get(group.category()), group.items()));
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

    /** Opens one group by id (any case); returns false when the player sees no switch in a group of that id. */
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
            lines.add(this.lang.get(SettingsMessages.GROUP_LINE, Arg.text("label", this.lang.plain(category.label())),
                Arg.text("description", this.lang.plain(category.description()))));
            buttons.add(new Button(Component.text(this.lang.plain(category.label())), this.lang.get(category.description()),
                BUTTON_WIDTH, s -> showGroup(s.player(), category.id(), 1, Map.of(), back)));
        }
        this.services.dialogs().show(player, this.services.templates().list(this.lang.get(SettingsMessages.TITLE), lines,
            buttons, 2, back));
    }

    /**
     * One page of a group. {@code pending} holds the switches flipped on other pages and not saved yet (toggle id to
     * the chosen value).
     */
    private void showGroup(Player player, String id, int page, Map<String, Boolean> pending, Button.Handler back) {
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
        boolean listed = listed(groups);
        int pageSize = this.config.get().pageSize();
        int pages = SettingsForm.pages(group.toggles().size(), pageSize);
        int current = SettingsForm.clampPage(page, group.toggles().size(), pageSize);
        List<Map.Entry<Toggle, Boolean>> onPage = SettingsForm.page(group.toggles(), current, pageSize);
        List<Map.Entry<String, Boolean>> values = new ArrayList<>(onPage.size());
        List<String> pageIds = new ArrayList<>(onPage.size());
        for (Map.Entry<Toggle, Boolean> entry : onPage) {
            String toggle = entry.getKey().id();
            values.add(Map.entry(toggle, pending.getOrDefault(toggle, entry.getValue())));
            pageIds.add(toggle);
        }
        List<SettingsForm.Field> fields = SettingsForm.fields(values);

        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(SettingsMessages.INTRO));
        List<Input> inputs = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Toggle toggle = onPage.get(i).getKey();
            SettingsForm.Field field = fields.get(i);
            lines.add(this.lang.get(SettingsMessages.LINE, Arg.text("label", this.lang.plain(toggle.label())),
                Arg.text("description", this.lang.plain(toggle.description()))));
            inputs.add(Templates.toggle(field.key(), Component.text(this.lang.plain(toggle.label())), field.shown()));
        }
        if (pages > 1) {
            lines.add(this.lang.get(SettingsMessages.PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        Map<String, Boolean> stored = new LinkedHashMap<>();
        for (Map.Entry<Toggle, Boolean> entry : group.toggles()) {
            stored.put(entry.getKey().id(), entry.getValue());
        }
        long elsewhere = SettingsForm.effective(pending, toggle -> stored.getOrDefault(toggle, pending.get(toggle))).keySet()
            .stream().filter(toggle -> !pageIds.contains(toggle)).count();
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

    /** Saves the pending changes that still change something, re-checking each toggle; confirms on the action bar. */
    private void save(Player player, Map<String, Boolean> pending) {
        PlayerSettings settings = this.services.settings();
        UUID uuid = player.getUniqueId();
        Map<String, Boolean> changes = SettingsForm.effective(pending, toggle -> {
            Toggle known = settings.toggle(toggle);
            // An unknown toggle counts as unchanged, so it is skipped.
            return known == null ? pending.get(toggle) : settings.enabled(uuid, known);
        });
        Toggle last = null;
        boolean lastValue = false;
        int saved = 0;
        for (Map.Entry<String, Boolean> change : changes.entrySet()) {
            Toggle toggle = settings.toggle(change.getKey());
            if (toggle == null || (toggle.permission() != null && !player.hasPermission(toggle.permission()))) {
                continue;
            }
            settings.set(uuid, toggle, change.getValue());
            last = toggle;
            lastValue = change.getValue();
            saved++;
        }
        var messenger = this.services.messenger();
        if (saved == 0) {
            messenger.send(player, SettingsMessages.UNCHANGED);
        } else if (saved == 1) {
            messenger.send(player, SettingsMessages.SAVED_ONE, Arg.text("label", this.lang.plain(last.label())),
                Arg.text("state", this.lang.plain(lastValue ? SettingsMessages.STATE_ON : SettingsMessages.STATE_OFF)));
        } else {
            messenger.send(player, SettingsMessages.SAVED_MANY, Arg.number("count", saved));
        }
    }
}
