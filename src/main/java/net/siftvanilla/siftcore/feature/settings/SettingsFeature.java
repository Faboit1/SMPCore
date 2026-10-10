package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.api.SettingsView;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.settings.SettingsGroups.Shown;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * The settings dialog ({@code /settings}, and Settings in the main menu and the pause screen): every per-player
 * setting on the server, whichever feature registered it, grouped by the shared {@link SettingCategory categories}, as
 * buttons that change a setting at once, searchable, with the settings the player changed and resets (see
 * {@link SettingsDialogs}). Nothing here
 * lists settings: a feature that registers one (with {@code services.settings().register(category, setting, options)})
 * appears automatically.
 * <p>
 * Also: the {@code /settings} command words ({@link SettingsCommands}), the staff tools {@code /sift settings}
 * ({@link SettingsAdmin}), the {@code setting_<id>}, {@code settingtext_<id>} and {@code settings_changed}
 * placeholders ({@link SettingsPlaceholders}), the public {@link SettingsView} ({@link SettingsApi}), and the server's
 * overrides from {@code features/settings.yml}: defaults for players who never changed a setting, locked values
 * players can't change, hidden settings, and the groups' order, icons and colours.
 */
public final class SettingsFeature implements Feature {

    public static final String COMMAND = "siftcore.command.settings";

    private final Services services;
    private final Setting<SettingsConfig> config;
    private final SettingsDialogs dialogs;
    private final SettingsCommands commands;
    private final SettingsPlaceholders placeholders;
    private final SettingsApi api;

    /**
     * @param admin the {@code /sift} command, where the staff tools for players' settings attach
     */
    public SettingsFeature(Services services, List<ConfigProblem> problems, AdminFeature admin) {
        this.services = services;
        this.config = services.configs().register("features/settings.yml", SettingsConfig::parse, problems);
        services.lang().register(SettingsMessages.class);
        services.permissions().declare(COMMAND, "Open the settings with /settings", true);
        services.permissions().declare(SettingsAdmin.PERMISSION, "See and change other players' settings with /sift settings", false);
        this.dialogs = new SettingsDialogs(services, this.config::get);
        this.commands = new SettingsCommands(services, this.dialogs);
        this.placeholders = new SettingsPlaceholders(services.settings(), services.lang());
        this.api = new SettingsApi(services.settings(), services.lang(), services.audit(),
            () -> this.config.get().categories(), services.plugin().getLogger());
        admin.addPart(new SettingsAdmin(services).part());
        // Overrides apply to settings that register later too: the registry resolves them on every registration.
        services.settings().overrides(this.config.get().overrides());
        this.config.onReload(settings -> {
            this.services.settings().overrides(settings.overrides());
            reportOverrideProblems();
        });
        services.settings().screens((player, category, back) -> this.dialogs.openGroup(player, category,
            back == null ? null : submission -> back.accept(submission.player())));
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public void enable() {
        this.services.hub().register(new HubEntry("settings", 90, SettingsMessages.HUB_LABEL, SettingsMessages.HUB_DESCRIPTION,
            COMMAND, player -> open(player, submission -> openMenu(submission.player()))));
        this.placeholders.register(this.services.placeholders());
        Bukkit.getServicesManager().register(SettingsView.class, this.api, this.services.plugin(), ServicePriority.Normal);
        // Features built after this one register their settings later: check the overrides once every feature is up.
        this.services.scheduler().globalLater(this::reportOverrideProblems, 20);
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(SettingsView.class, this.api);
    }

    private List<String> overrideProblems() {
        List<String> problems = new ArrayList<>(SettingsOverrides.problems(this.services.settings().registry(),
            this.services.settings().overrides()));
        problems.addAll(SettingsOverrides.categoryProblems(this.services.settings().registry(), this.config.get().categories(),
            this.services.lang().style().icons()::has));
        return problems;
    }

    private void reportOverrideProblems() {
        for (String problem : overrideProblems()) {
            this.services.plugin().getLogger().warning("features/settings.yml: " + problem);
        }
    }

    private void openMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }

    @Override
    public List<SiftCommand> commands() {
        return List.of(this.commands.command());
    }

    /**
     * Opens the settings: the list of groups, or the only group the player sees. {@code back} runs on Back (from a
     * menu); null shows Close.
     */
    public void open(Player player, Button.Handler back) {
        this.dialogs.open(player, back);
    }

    /**
     * Opens one group of settings directly, for features that link to their own settings. Returns false (and shows
     * nothing) when the player sees no setting in a group with that id.
     */
    public boolean openGroup(Player player, String group, Button.Handler back) {
        return this.dialogs.openGroup(player, group, back);
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "dialog input keys are valid and unique", () -> {
            Set<String> keys = new HashSet<>();
            for (Registry.Entry<?> entry : this.services.settings().registry().byId().values()) {
                if (!entry.inputKey().matches("[A-Za-z0-9_]+") || !keys.add(entry.inputKey())) {
                    return "bad key " + entry.inputKey() + " for " + entry.id();
                }
            }
            return null;
        });
        test.check(id(), "features/settings.yml overrides name real settings, values, groups and icons", () -> {
            List<String> problems = overrideProblems();
            return problems.isEmpty() ? null : String.join("; ", problems);
        });
        test.check(id(), "every group page, the group list, the changed settings and the search form build, buttons with tooltips only",
            this::checkPages);
        test.check(id(), "every setting is found by searching its label", this::checkSearch);
        test.check(id(), "/settings words name each setting exactly once", this::checkWords);
        test.check(id(), "placeholders resolve", this::checkPlaceholders);
        test.check(id(), "the settings API is registered", () ->
            Bukkit.getServicesManager().getRegistration(SettingsView.class) != null ? null : "SettingsView is not in the services manager");
        test.check(id(), "hub entry is registered", () -> this.services.hub().get("settings") != null ? null : "missing");
    }

    /** Someone who sees every setting (every permission), reading the defaults. */
    private Viewer operator() {
        return Viewer.everything(this.services.settings(), new UUID(0L, 0L), "selftest");
    }

    private String checkPages() {
        Viewer viewer = operator();
        List<Shown> groups = this.dialogs.groups(viewer);
        for (Shown group : groups) {
            View view = this.dialogs.pageView(viewer, new SettingsDialogs.Group(group.id()), s -> { }, null);
            if (view == null) {
                return "the page of " + group.id() + " is empty";
            }
            if (!view.inputs().isEmpty() || !view.body().isEmpty()) {
                return "the page of " + group.id() + " shows more than buttons";
            }
            // One button per setting, plus Reset this group when the operator changed something.
            if (view.buttons().size() < group.entries().size()) {
                return "the page of " + group.id() + " has " + view.buttons().size() + " buttons for " + group.entries().size() + " settings";
            }
            for (Button button : view.buttons()) {
                if (button.tooltip() == null) {
                    return "a button of " + group.id() + " has no tooltip: " + Dialogs.plain(button.label());
                }
            }
        }
        if (groups.size() > 1) {
            this.dialogs.listView(viewer, groups, null);
        }
        this.dialogs.changedView(viewer, null, null);
        this.dialogs.searchForm("", null, null);
        return null;
    }

    private String checkSearch() {
        Viewer viewer = operator();
        List<Shown> groups = this.dialogs.groups(viewer);
        for (Registry.Entry<?> entry : this.dialogs.all(groups)) {
            String label = this.services.lang().plain(entry.setting().label());
            if (!this.dialogs.searchResults(groups, label).contains(entry)) {
                return entry.id() + " is not found by '" + label + "'";
            }
        }
        return null;
    }

    private String checkWords() {
        Registry registry = this.services.settings().registry();
        for (Registry.Entry<?> entry : registry.byId().values()) {
            if (!(SettingsArgs.inCategory(registry, e -> true, entry.category(), entry.shortName()) instanceof SettingsArgs.One one)
                || one.entry() != entry) {
                return "/settings " + entry.category().id() + " " + entry.shortName() + " does not name " + entry.id();
            }
            boolean categoryId = registry.category(entry.id()) != null || SettingsArgs.RESERVED.contains(entry.id());
            if (!categoryId && (!(SettingsArgs.first(registry, e -> true, entry.id()) instanceof SettingsArgs.One first)
                || first.entry() != entry)) {
                return "/settings " + entry.id() + " does not name it";
            }
        }
        return null;
    }

    private String checkPlaceholders() {
        Registry registry = this.services.settings().registry();
        for (Registry.Entry<?> entry : registry.byId().values()) {
            String value = this.services.placeholders().resolve(null, SettingsPlaceholders.VALUE_PREFIX + entry.id());
            String text = this.services.placeholders().resolve(null, SettingsPlaceholders.TEXT_PREFIX + entry.id());
            if (value == null || text == null) {
                return "no value for " + entry.id();
            }
            if (!entry.placeholder() && (!value.isEmpty() || !text.isEmpty())) {
                return entry.id() + " is private but its placeholder shows a value";
            }
        }
        if (this.services.placeholders().resolve(null, SettingsPlaceholders.VALUE_PREFIX + "no-such-setting") != null) {
            return "an unknown id resolves";
        }
        return this.services.placeholders().resolve(null, SettingsPlaceholders.CHANGED) == null ? "settings_changed is missing" : null;
    }
}
