package net.siftvanilla.siftcore.feature.settings;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.entity.Player;

/**
 * The settings dialog ({@code /settings [group]}, and Settings in the main menu and the pause screen): every
 * per-player switch on the server, whichever feature registered it, grouped by the feature's
 * {@link SettingCategory} and paged (see {@link SettingsDialogs}). Nothing here lists switches: a feature that
 * registers a toggle (with {@code services.settings().register(category, toggle)}) appears automatically.
 */
public final class SettingsFeature implements Feature {

    public static final String COMMAND = "siftcore.command.settings";

    private final Services services;
    private final SettingsDialogs dialogs;

    public SettingsFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        Setting<SettingsConfig> config = services.configs().register("features/settings.yml", SettingsConfig::parse, problems);
        services.lang().register(SettingsMessages.class);
        services.permissions().declare(COMMAND, "Open the settings with /settings", true);
        this.dialogs = new SettingsDialogs(services, config);
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public void enable() {
        this.services.hub().register(new HubEntry("settings", 90, SettingsMessages.HUB_LABEL, SettingsMessages.HUB_DESCRIPTION,
            COMMAND, player -> open(player, submission -> openMenu(submission.player()))));
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
     * Opens one group of settings directly, for features that link to their own switches. Returns false (and shows
     * nothing) when the player sees no switch in a group with that id.
     */
    public boolean openGroup(Player player, String group, Button.Handler back) {
        return this.dialogs.openGroup(player, group, back);
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "every switch has a label and a description", () -> {
            List<String> missing = new ArrayList<>();
            for (Toggle toggle : this.services.settings().toggles()) {
                missing.addAll(missingText(toggle.id(), toggle.label(), toggle.description()));
            }
            return missing.isEmpty() ? null : "no text for " + String.join(", ", missing);
        });
        test.check(id(), "every group has a label and a description", () -> {
            List<String> missing = new ArrayList<>();
            List<SettingCategory> categories = new ArrayList<>(this.services.settings().categories());
            categories.add(this.dialogs.general());
            for (SettingCategory category : categories) {
                missing.addAll(missingText(category.id(), category.label(), category.description()));
            }
            return missing.isEmpty() ? null : "no text for " + String.join(", ", missing);
        });
        test.check(id(), "dialog input keys are valid and unique", () -> {
            List<Map.Entry<String, Boolean>> all = new ArrayList<>();
            for (Toggle toggle : this.services.settings().toggles()) {
                all.add(Map.entry(toggle.id(), toggle.defaultOn()));
            }
            Set<String> keys = new HashSet<>();
            for (SettingsForm.Field field : SettingsForm.fields(all)) {
                if (!field.key().matches("[A-Za-z0-9_]+") || !keys.add(field.key())) {
                    return "bad key " + field.key() + " for " + field.toggle();
                }
            }
            return null;
        });
        test.check(id(), "hub entry is registered", () -> this.services.hub().get("settings") != null ? null : "missing");
    }

    private List<String> missingText(String owner, MessageKey... keys) {
        Lang lang = this.services.lang();
        List<String> missing = new ArrayList<>();
        for (MessageKey key : keys) {
            String plain = lang.plain(key);
            if (plain.isBlank() || plain.equals(key.path())) {
                missing.add(owner + " (" + key.path() + ")");
            }
        }
        return missing;
    }
}
