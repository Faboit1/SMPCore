package net.siftvanilla.siftcore.core.command;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.Setting;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers every feature's commands with Paper's Brigadier registrar. Registration happens in the commands
 * lifecycle event, which Paper also re-runs on a datapack reload, so commands survive {@code /minecraft:reload}.
 * {@code commands.yml} can disable a command, replace its aliases, or leave it to another plugin while that plugin
 * runs ({@code yield-to}, checked at registration, which Paper runs after every plugin was enabled). Its
 * {@code cooldown} is applied here to every player who runs the command or any of its subcommands.
 */
public final class CommandService {

    private final JavaPlugin plugin;
    private final Setting<CommandSettings> settings;
    private final CommandSupport support;
    private final Logger logger;
    private final Map<String, SiftCommand> commands = new LinkedHashMap<>();
    private final List<String> registeredLabels = new ArrayList<>();

    public CommandService(JavaPlugin plugin, Setting<CommandSettings> settings) {
        this(plugin, settings, null);
    }

    /**
     * @param support applies each command's commands.yml cooldown to players who run it (null: no cooldowns)
     */
    public CommandService(JavaPlugin plugin, Setting<CommandSettings> settings, CommandSupport support) {
        this.plugin = plugin;
        this.settings = settings;
        this.support = support;
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

    private static boolean running(String plugin) {
        Plugin other = Bukkit.getPluginManager().getPlugin(plugin);
        return other != null && other.isEnabled();
    }

    private synchronized void register(Commands registrar) {
        this.registeredLabels.clear();
        CommandSettings config = this.settings.get();
        for (SiftCommand command : this.commands.values()) {
            CommandSettings.Entry entry = config.get(command.name());
            if (!entry.enabled()) {
                continue;
            }
            if (entry.yieldTo() != null && running(entry.yieldTo())) {
                this.logger.info("/" + command.name() + " is left to " + entry.yieldTo() + " (commands.yml yield-to)");
                continue;
            }
            List<String> aliases = entry.aliases() == null ? command.aliases() : entry.aliases();
            var node = command.build(command.name()).build();
            if (this.support != null) {
                // Every command gets the gate, also one without a cooldown now: commands.yml cooldowns change with
                // /sift reload, but the trees are registered once.
                String name = command.name();
                node = CommandTrees.wrapCommands(node, body -> this.support.withCooldown(name, body));
            }
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
