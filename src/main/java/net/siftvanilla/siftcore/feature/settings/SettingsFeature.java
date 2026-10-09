package net.siftvanilla.siftcore.feature.settings;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.entity.Player;

/**
 * The settings dialog ({@code /settings [group]}, and Settings in the main menu and the pause screen): every
 * per-player setting on the server, whichever feature registered it, grouped by the shared
 * {@link SettingCategory categories} and paged (see {@link SettingsDialogs}). Nothing here lists settings: a feature
 * that registers one (with {@code services.settings().register(category, setting, options)}) appears automatically.
 * <p>
 * It also applies the server's overrides from {@code features/settings.yml}: defaults for players who never changed
 * a setting, locked values players can't change, and hidden settings.
 */
public final class SettingsFeature implements Feature {

    public static final String COMMAND = "siftcore.command.settings";

    private final Services services;
    private final Setting<SettingsConfig> config;
    private final SettingsDialogs dialogs;
    /**
     * The {@code /sift} command. The staff tools for other players' settings ({@code /sift settings}, node
     * {@code siftcore.admin.settings}) attach to it in the settings dialog's next version; the composition root passes
     * it already so that change needs no new wiring.
     */
    private final AdminFeature admin;

    /**
     * @param admin the {@code /sift} command, where staff tools for players' settings belong
     */
    public SettingsFeature(Services services, List<ConfigProblem> problems, AdminFeature admin) {
        this.services = services;
        this.admin = admin;
        this.config = services.configs().register("features/settings.yml", SettingsConfig::parse, problems);
        services.lang().register(SettingsMessages.class);
        services.permissions().declare(COMMAND, "Open the settings with /settings", true);
        this.dialogs = new SettingsDialogs(services, this.config);
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
        // Features built after this one register their settings later: check the overrides once every feature is up.
        this.services.scheduler().globalLater(this::reportOverrideProblems, 20);
    }

    private void reportOverrideProblems() {
        for (String problem : SettingsOverrides.problems(this.services.settings().registry(), this.services.settings().overrides())) {
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
        return List.of(new SimpleCommand("settings", List.of("options", "preferences"), "Opens your settings", COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(COMMAND))
                .executes(ctx -> {
                    Player player = this.services.commands().player(ctx);
                    if (player != null) {
                        open(player, null);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.argument("group", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        if (context.getSource().getSender() instanceof Player player) {
                            String remaining = builder.getRemainingLowerCase();
                            for (SettingsDialogs.Shown group : this.dialogs.groups(player)) {
                                if (group.category().id().startsWith(remaining)) {
                                    builder.suggest(group.category().id());
                                }
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        Player player = this.services.commands().player(ctx);
                        String group = StringArgumentType.getString(ctx, "group");
                        if (player != null && !openGroup(player, group, null)) {
                            this.services.messenger().send(player, SettingsMessages.UNKNOWN_GROUP,
                                Arg.text("name", group.toLowerCase(Locale.ROOT)));
                        }
                        return CommandSupport.OK;
                    }))));
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
        test.check(id(), "features/settings.yml overrides name real settings and values", () -> {
            List<String> problems = SettingsOverrides.problems(this.services.settings().registry(), this.services.settings().overrides());
            return problems.isEmpty() ? null : String.join("; ", problems);
        });
        test.check(id(), "hub entry is registered", () -> this.services.hub().get("settings") != null ? null : "missing");
    }
}
