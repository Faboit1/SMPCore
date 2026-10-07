package net.siftvanilla.siftcore.core.command;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers every feature's commands with Paper's Brigadier registrar. Registration happens in the commands
 * lifecycle event, which Paper also re-runs on a datapack reload, so commands survive {@code /minecraft:reload}.
 * {@code commands.yml} can disable a command or replace its aliases.
 */
public final class CommandService {

    private final JavaPlugin plugin;
    private final Setting<CommandSettings> settings;
    private final Logger logger;
    private final Map<String, SiftCommand> commands = new LinkedHashMap<>();
    private final List<String> registeredLabels = new ArrayList<>();

    public CommandService(JavaPlugin plugin, Setting<CommandSettings> settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = plugin.getLogger();
    }

    public void add(SiftCommand command) {
        if (this.commands.putIfAbsent(command.name(), command) != null) {
            throw new IllegalStateException("Command " + command.name() + " is defined twice");
        }
    }

    public List<SiftCommand> all() {
        return List.copyOf(this.commands.values());
    }

    /** Labels (names and aliases) registered in the last registration pass. */
    public synchronized List<String> registeredLabels() {
        return List.copyOf(this.registeredLabels);
    }

    /** Hooks registration into the lifecycle; call once from onEnable. */
    public void install() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> register(event.registrar()));
    }

    private synchronized void register(Commands registrar) {
        this.registeredLabels.clear();
        CommandSettings config = this.settings.get();
        for (SiftCommand command : this.commands.values()) {
            CommandSettings.Entry entry = config.get(command.name());
            if (!entry.enabled()) {
                continue;
            }
            List<String> aliases = entry.aliases() == null ? command.aliases() : entry.aliases();
            var node = command.build(command.name()).build();
            var registered = registrar.register(node, command.description(), aliases);
            this.registeredLabels.addAll(registered);
            for (String alias : aliases) {
                if (!registered.contains(alias)) {
                    this.logger.fine("Alias /" + alias + " of /" + command.name() + " is taken by another plugin");
                }
            }
        }
    }
}
