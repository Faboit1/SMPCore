package net.siftvanilla.siftcore.core;

import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.config.Configs;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.StatusBars;
import net.siftvanilla.siftcore.economy.Deliveries;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.hub.HubRegistry;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The core services handed to feature constructors. A plain value object built once by the composition root; it
 * holds no logic and no mutable state of its own, it only groups the shared building blocks so feature constructors
 * stay readable. Features take what they use from it in their constructor and keep those references.
 * <p>
 * {@code relations} answers how two players are related (friends, teammates, ignored) for "who can" settings; its
 * lookups are bound once every feature is built. {@code statusBars} is the per-player boss bar for lasting status
 * lines.
 */
public record Services(
    JavaPlugin plugin,
    Scheduler scheduler,
    Configs configs,
    Setting<CoreSettings> core,
    Database database,
    Ledger ledger,
    Deliveries deliveries,
    PlayerDirectory directory,
    PlayerSettings settings,
    AuditLog audit,
    Cooldowns cooldowns,
    Lang lang,
    Messenger messenger,
    Dialogs dialogs,
    Templates templates,
    MenuContext menus,
    HubRegistry hub,
    CommandSupport commands,
    Placeholders placeholders,
    Permissions permissions,
    Teleports teleports,
    Relations relations,
    StatusBars statusBars) {

    /** The current money format (follows reloads). */
    public Supplier<MoneyFormat> money() {
        return () -> this.core.get().money();
    }
}
