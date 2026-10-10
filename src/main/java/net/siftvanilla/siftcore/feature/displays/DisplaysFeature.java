package net.siftvanilla.siftcore.feature.displays;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;

/**
 * Leaderboards and info boards at spawn, made of text displays: no hologram plugin. Templates and looks come
 * from {@code features/displays.yml}, positions set in-game from the {@code displays} table, and the text from
 * SiftCore placeholders that are the same for everyone. Right-clicking a leaderboard opens the full list. Players
 * who turn {@link #HOLOGRAMS} off don't receive the display entities at all.
 */
public final class DisplaysFeature implements Feature {

    public static final String PERMISSION = "siftcore.admin.displays";

    /**
     * Spawn holograms: off hides every display (and its click box) from the player with {@code Player#hideEntity},
     * for screenshots and slow PCs. Display group of the settings.
     */
    public static final Toggle HOLOGRAMS = new Toggle("show-spawn-holograms", true, DisplaysMessages.SETTING_LABEL,
        DisplaysMessages.SETTING_DESCRIPTION, null);
    /** Its place in the Display group (the catalog's fifth display setting). */
    static final int HOLOGRAMS_ORDER = 5;

    private final Services services;
    private final Logger logger;
    private final DisplayChecks checks;
    private final Setting<DisplaysSettings> settings;
    private final Placements placements;
    private final DisplayEntities entities;
    private final DisplaysCommands commands;
    private final Object rebuildLock = new Object();

    public DisplaysFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.checks = new DisplayChecks(services.placeholders(), services.lang().style());
        this.settings = services.configs().register("features/displays.yml",
            reader -> DisplaysSettings.parse(reader, this.checks), problems);
        services.lang().register(DisplaysMessages.class);
        services.permissions().declare(PERMISSION, "Place, move and delete leaderboards and info boards with /displays", false);
        this.placements = new Placements(new PlacementStore(services.database()), this.logger, this::rebuild);
        PlayerSettings playerSettings = services.settings();
        this.entities = new DisplayEntities(services.scheduler(), services.lang().style(), services.placeholders(),
            services.messenger(), services.cooldowns(), this.settings, this.logger, new NamespacedKey(services.plugin(), "display"),
            services.plugin(), player -> playerSettings.get(player, HOLOGRAMS));
        registerSettings(playerSettings, this::anyShown, (player, before, now) -> this.entities.viewerChanged(player, now));
        this.commands = new DisplaysCommands(services, this);
    }

    @Override
    public String id() {
        return "displays";
    }

    @Override
    public void enable() throws Exception {
        this.placements.load();
        synchronized (this.rebuildLock) {
            this.checks.placedTemplates(placedTemplates());
            this.entities.start(DisplayCatalog.merge(this.settings.get(), this.placements.snapshot()));
        }
        Bukkit.getPluginManager().registerEvents(this.entities, this.services.plugin());
        this.settings.onReload(ignored -> rebuild());
        this.checks.arm();
        reportBrokenPlacements();
        this.services.scheduler().globalLater(this::reportMissingPlaceholders, 1L);
    }

    /**
     * Registers the hologram switch in the Display group: applied at once on every display ({@code changed} runs on
     * the player's thread), and offered only while a display stands in the world to hide ({@code anyShown}).
     */
    static void registerSettings(PlayerSettings settings, BooleanSupplier anyShown, SettingOptions.ChangeHook<Boolean> changed) {
        settings.register(SettingCategories.DISPLAY, HOLOGRAMS, SettingOptions.<Boolean>builder().order(HOLOGRAMS_ORDER)
            .onChange(changed).availableWhen(anyShown).build());
    }

    /** Whether any display is placed with a template (otherwise the hologram switch would do nothing). */
    private boolean anyShown() {
        for (DisplayDef def : this.entities.displays().values()) {
            if (def.showable()) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the displays from the current file and positions (after a reload or an in-game change). */
    void rebuild() {
        synchronized (this.rebuildLock) {
            this.checks.placedTemplates(placedTemplates());
            this.entities.apply(DisplayCatalog.merge(this.settings.get(), this.placements.snapshot()));
        }
    }

    /** Templates of displays that only exist in-game, which displays.yml must keep. */
    private Map<String, String> placedTemplates() {
        Map<String, String> templates = new HashMap<>();
        for (Placement placement : this.placements.snapshot().values()) {
            if (placement.template() != null) {
                templates.put(placement.id(), placement.template());
            }
        }
        return templates;
    }

    private void reportBrokenPlacements() {
        for (DisplayDef def : this.entities.displays().values()) {
            if (def.position() != null && def.template() == null) {
                this.logger.warning("Display " + def.id() + " uses the template '" + def.templateId()
                    + "', which is not in displays.yml. It stays hidden until the template is added, or delete it with /displays delete "
                    + def.id() + ".");
            } else if (def.position() != null && Bukkit.getWorld(def.position().world()) == null) {
                this.logger.warning("Display " + def.id() + " is in the world '" + def.position().world()
                    + "', which is not loaded. It shows up once that world is loaded.");
            }
        }
    }

    /**
     * Once every feature has registered its placeholders, says which ones the templates use that nothing provides
     * yet (they show "-"). Informational: /sift reload reports them as problems.
     */
    private void reportMissingPlaceholders() {
        Set<String> templates = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (DisplayTemplate template : this.settings.get().templates().values()) {
            for (String name : template.placeholders()) {
                if (DisplayChecks.status(this.services.placeholders(), name) != DisplaysSettings.PlaceholderStatus.KNOWN) {
                    templates.add(template.id());
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            this.logger.info("Display templates " + String.join(", ", templates) + " use placeholders that no feature provides ({"
                + missing.getFirst() + "}" + (missing.size() > 1 ? " and " + (missing.size() - 1) + " more" : "")
                + "); they show - until one does.");
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this.entities);
        this.entities.shutdown();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "every display has a template and a world", () -> {
            List<String> problems = new ArrayList<>();
            for (DisplayDef def : this.entities.displays().values()) {
                if (def.position() == null) {
                    continue;
                }
                if (def.template() == null) {
                    problems.add(def.id() + " uses the missing template " + def.templateId());
                }
                if (Bukkit.getWorld(def.position().world()) == null) {
                    problems.add(def.id() + " is in the unloaded world " + def.position().world());
                }
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        });
        test.check(id(), "templates render", () -> {
            for (DisplayTemplate template : this.settings.get().templates().values()) {
                String text = TextStyle.plain(template.render(this.services.lang().style(), name -> null));
                boolean hasText = template.source().stream().anyMatch(line -> !line.isBlank());
                if (hasText && text.isBlank()) {
                    return template.id() + " renders as empty text";
                }
            }
            return null;
        });
        test.checkAsync(id(), "spawned displays match the loaded chunks", this.entities::verify);
    }

    // ------------------------------------------------------------------ used by the commands

    DisplaysSettings settings() {
        return this.settings.get();
    }

    Map<String, DisplayDef> displays() {
        return this.entities.displays();
    }

    Placements placements() {
        return this.placements;
    }

    DisplayEntities.State state(DisplayDef def) {
        return this.entities.state(def);
    }

    int refreshAll() {
        return this.entities.refreshAll();
    }
}
