package net.siftvanilla.siftcore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import io.papermc.paper.tag.TagEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Runs before the server loads its registries. Registers the SiftVanilla menu dialog ({@code siftcore:hub}) and adds
 * it to the pause screen and the quick actions key, so players can open it without typing a command. The buttons
 * only send {@code siftcore:hub/<entry>}; the plugin's router opens the real, permission-checked screen at runtime.
 * Its labels are read once here from {@code lang/hub.yml} and {@code features/hub.yml}, so changing them needs a
 * restart.
 */
public final class SiftCoreBootstrap implements PluginBootstrap {

    public static final Key HUB_DIALOG = Key.key("siftcore", "hub");

    @Override
    public void bootstrap(BootstrapContext context) {
        PauseMenu menu = PauseMenu.load(context.getDataDirectory(), context);
        if (!menu.enabled()) {
            return;
        }
        TypedKey<Dialog> key = TypedKey.create(RegistryKey.DIALOG, HUB_DIALOG);
        LifecycleEventManager<BootstrapContext> manager = context.getLifecycleManager();
        manager.registerEventHandler(RegistryEvents.DIALOG.compose().newHandler(event ->
            event.registry().register(key, builder -> builder
                .base(DialogBase.builder(menu.title())
                    .externalTitle(menu.title())
                    .canCloseWithEscape(true)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(menu.body() == null ? List.of() : List.of(DialogBody.plainMessage(menu.body(), 250)))
                    .build())
                .type(DialogType.multiAction(menu.buttons()).columns(2).build()))));
        manager.registerEventHandler(LifecycleEvents.TAGS.preFlatten(RegistryKey.DIALOG).newHandler(event -> {
            event.registrar().addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, Set.of(TagEntry.valueEntry(key)));
            event.registrar().addToTag(DialogTagKeys.QUICK_ACTIONS, Set.of(TagEntry.valueEntry(key)));
        }));
    }

    /** What the pause-menu dialog shows, read from the data folder or the bundled defaults. */
    record PauseMenu(boolean enabled, Component title, Component body, List<ActionButton> buttons) {

        static PauseMenu load(Path dataDirectory, BootstrapContext context) {
            YamlConfiguration config = read(dataDirectory.resolve("features/hub.yml"), "features/hub.yml", context);
            YamlConfiguration lang = read(dataDirectory.resolve("lang/hub.yml"), "lang/hub.yml", context);
            boolean enabled = config.getBoolean("pause-menu.enabled", true);
            Component title = Component.text(lang.getString("hub.pause-menu.title", "SiftVanilla"));
            String bodyText = lang.getString("hub.pause-menu.body", "");
            TextColor gray = NamedTextColor.GRAY;
            Component body = bodyText.isBlank() ? null : Component.text(bodyText, gray);
            List<ActionButton> buttons = new ArrayList<>();
            for (String id : config.getStringList("pause-menu.entries")) {
                if (!id.matches("[a-z0-9_-]{1,32}")) {
                    context.getLogger().warn("Ignoring pause-menu entry '{}': ids are lowercase letters, digits, - or _", id);
                    continue;
                }
                String label = lang.getString("hub.entries." + id + ".label", id);
                String tooltip = lang.getString("hub.entries." + id + ".description", "");
                buttons.add(ActionButton.builder(Component.text(label))
                    .tooltip(tooltip.isBlank() ? null : Component.text(tooltip, gray))
                    .width(150)
                    .action(DialogAction.customClick(Key.key("siftcore", "hub/" + id), null))
                    .build());
            }
            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("Menu"))
                    .width(150)
                    .action(DialogAction.customClick(Key.key("siftcore", "hub/menu"), null))
                    .build());
            }
            return new PauseMenu(enabled, title, body, buttons);
        }

        private static YamlConfiguration read(Path file, String resource, BootstrapContext context) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                if (Files.isRegularFile(file)) {
                    yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                    return yaml;
                }
                try (InputStream in = SiftCoreBootstrap.class.getClassLoader().getResourceAsStream(resource)) {
                    if (in != null) {
                        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                            yaml.load(reader);
                        }
                    }
                }
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                context.getLogger().error("Could not read {} for the pause menu; using defaults ({})", resource, e.getMessage());
            }
            return yaml;
        }
    }
}
