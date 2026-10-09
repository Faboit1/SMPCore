package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.feature.sell.WorthService;
import net.siftvanilla.siftcore.feature.sell.WorthTable;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * Kits and rank perks. Kits are claimed from the {@code /kits} dialog or with {@code /kit <name>}: each claim is one
 * economy transaction that checks and starts the kit's cooldown and puts its items into the claim box, from where
 * they move into the inventory (whatever doesn't fit waits for the player). Kits can also give crate keys. Perks are
 * permission-gated commands: the ender chest, workstations anywhere, a trash bin and a hat.
 * <p>
 * Player settings ({@link KitPlayerSettings}, in Crates &amp; kits): how and when kit reminders come, putting kit
 * armour on straight away, and what the trash protects and when it deletes.
 */
public final class KitsFeature implements Feature, Listener {

    public static final String PERMISSION_USE = "siftcore.command.kits";
    public static final String PERMISSION_ADMIN = "siftcore.admin.kits";

    /** How a player is told that kits are ready (was a switch; see {@link KitPlayerSettings}). */
    public static final Choice<AlertStyle> REMINDERS = KitPlayerSettings.REMINDERS;

    private final Services services;
    private final Setting<KitsSettings> settings;
    private final KitClaims claims;
    private final KitHandouts handouts;
    private final KitService kits;
    private final PerkService perks;
    private final KitDialogs dialogs;
    private final KitReminders reminders;
    private final KitCommands commands;
    private final CrateKeys crateKeys;
    private final WorthLookup worth;

    /**
     * @param combat    players in combat can't claim kits or use the blocked perks (the combat tags)
     * @param crateKeys gives the crate keys kits include (the crates feature)
     * @param worth     the server's sell prices, for telling valuable items apart in the trash bin
     */
    public KitsFeature(Services services, List<ConfigProblem> problems, CombatStatus combat, CrateKeys crateKeys, WorthLookup worth) {
        this.services = services;
        this.crateKeys = crateKeys;
        this.worth = worth;
        this.settings = services.configs().register("features/kits.yml",
            reader -> KitsSettings.parse(reader, KitItems.catalog(crateKeys.crates()), services.core().get().money()), problems);
        services.lang().register(KitsMessages.class);
        var perms = services.permissions();
        perms.declare(PERMISSION_USE, "Use /kits and claim the kits you have", true);
        perms.declare(PERMISSION_ADMIN, "Give kits, reset kit cooldowns and check players' kits with /kits", false);
        for (Perk perk : Perk.values()) {
            perms.declare(perk.node(), perk.description() + " (/" + perk.id() + ")", false);
        }
        perms.declare(Perk.EC_OTHERS, "Look into another online player's ender chest with /ec <player> (read only)", false);
        for (Kit kit : this.settings.get().kits()) {
            perms.declare(kit.permission(), "Claim the " + kit.name() + " kit", kit.everyone());
        }
        this.claims = new KitClaims(services.ledger(), services.database(), System::currentTimeMillis);
        this.handouts = new KitHandouts(services);
        KitText text = new KitText(services.lang());
        this.kits = new KitService(services, this.settings, this.claims, new KitItems(() -> services.lang().style().palette()),
            this.handouts, combat, crateKeys, text);
        this.perks = new PerkService(services, this.settings, combat, worth);
        this.dialogs = new KitDialogs(services, this.kits, this.perks);
        this.reminders = new KitReminders(services, this.kits);
        this.kits.reminders(this.reminders);
        KitPlayerSettings.register(services.settings(), this.settings::get, () -> pricesItems(worth), this.reminders::schedule);
        this.commands = new KitCommands(services, this.kits, this.dialogs, this.perks);
    }

    /**
     * Whether the server prices any item at /sell, which the trash bin's valuables protection needs: the sell
     * feature's worth table has at least one price. Another plugin's prices are assumed to price items; none never does.
     */
    static boolean pricesItems(WorthLookup worth) {
        if (worth == null || worth == WorthLookup.NONE) {
            return false;
        }
        return !(worth instanceof WorthService sell) || priced(sell.table());
    }

    /** Whether a worth table prices at least one item. */
    static boolean priced(WorthTable table) {
        return table != null && table.size() > 0;
    }

    @Override
    public String id() {
        return "kits";
    }

    /** The current settings (read-only). */
    public KitsSettings settings() {
        return this.settings.get();
    }

    @Override
    public void enable() throws Exception {
        this.claims.load();
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        Bukkit.getPluginManager().registerEvents(this.perks, this.services.plugin());
        this.settings.onReload(next -> {
            this.services.scheduler().global(() -> updatePermissions(next));
            this.reminders.rescheduleAll();
        });
        this.services.hub().register(new HubEntry("kits", 80, KitsMessages.HUB_LABEL, KitsMessages.HUB_DESCRIPTION,
            PERMISSION_USE, player -> this.dialogs.list(player, s -> openMenu(s.player()))));
        registerPlaceholders();
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.services.scheduler().entity(online, () -> this.reminders.schedule(online), null);
        }
        KitsSettings current = this.settings.get();
        long forEveryone = current.kits().stream().filter(Kit::everyone).count();
        this.services.plugin().getLogger().info("Kits: " + current.kits().size() + " kits (" + forEveryone + " for everyone), "
            + this.claims.book().size() + " claims stored, " + Perk.values().length + " perk commands.");
    }

    /** Registers the nodes of kits added by a reload and applies changed "everyone" defaults. Global thread. */
    private void updatePermissions(KitsSettings next) {
        var manager = Bukkit.getPluginManager();
        for (Kit kit : next.kits()) {
            PermissionDefault wanted = kit.everyone() ? PermissionDefault.TRUE : PermissionDefault.OP;
            Permission existing = manager.getPermission(kit.permission());
            try {
                if (existing == null) {
                    this.services.permissions().declare(kit.permission(), "Claim the " + kit.name() + " kit", wanted);
                    manager.addPermission(new Permission(kit.permission(), "Claim the " + kit.name() + " kit", wanted));
                } else if (existing.getDefault() != wanted) {
                    existing.setDefault(wanted);
                }
            } catch (RuntimeException e) {
                this.services.plugin().getLogger().log(Level.WARNING, "Could not update the permission " + kit.permission()
                    + "; it applies after a restart", e);
            }
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

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("kits_ready", "How many of your kits you can claim now", offline -> {
            Player player = offline.getPlayer();
            return player == null ? "0" : Integer.toString(this.kits.ready(player).size());
        });
        placeholders.registerPrefix("kit_", "kit_<kit>", "A kit's status for you: ready, in 3h 20m, claimed, locked (- for no such kit)",
            (offline, id) -> {
                Kit kit = this.settings.get().kit(id);
                if (kit == null) {
                    return "-";
                }
                // Permissions are only known for online players; offline players get their cooldown status.
                Player player = offline.getPlayer();
                boolean permitted = player == null || player.hasPermission(kit.permission());
                return this.kits.text().plainStatus(this.claims.status(offline.getUniqueId(), kit), permitted);
            });
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.reminders.joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.reminders.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void disable() {
        this.reminders.cancelAll();
        try {
            // Let every queued claim commit and register its hand-over (players can no longer receive items: the
            // region threads have stopped), then put every undelivered item back into the claim box, all before
            // storage closes.
            this.services.database().flush();
            if (!this.handouts.awaitIdle(Duration.ofSeconds(10))) {
                this.services.plugin().getLogger().warning("Some kit items were still waiting for storage at shutdown");
            }
            this.handouts.drain();
            this.services.database().flush();
        } catch (RuntimeException e) {
            this.services.plugin().getLogger().log(Level.SEVERE, "Returning pending kit items on shutdown failed", e);
        }
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "kits are configured and give something", () -> {
            List<Kit> all = this.settings.get().kits();
            if (all.isEmpty()) {
                return "no kits are configured";
            }
            for (Kit kit : all) {
                if (kit.items().isEmpty() && kit.keys().isEmpty()) {
                    return kit.id() + " gives nothing";
                }
            }
            return null;
        });
        test.check(id(), "kit items can be made and stored", () -> {
            KitItems items = this.kits.items();
            for (Kit kit : this.settings.get().kits()) {
                for (KitItem item : kit.items()) {
                    ItemStack stack = items.build(item);
                    if (stack.isEmpty() || !stack.getType().getKey().asString().equals(item.material())) {
                        return kit.id() + " makes the wrong item for " + item.material();
                    }
                    ItemStack one = stack.asQuantity(Math.min(stack.getAmount(), stack.getMaxStackSize()));
                    if (!ItemStack.deserializeBytes(one.serializeAsBytes()).isSimilar(one)) {
                        return kit.id() + "/" + item.material() + " does not survive storage";
                    }
                }
            }
            return null;
        });
        test.check(id(), "kit permissions are registered", () -> {
            List<String> missing = new ArrayList<>();
            for (Kit kit : this.settings.get().kits()) {
                Permission permission = Bukkit.getPluginManager().getPermission(kit.permission());
                PermissionDefault wanted = kit.everyone() ? PermissionDefault.TRUE : PermissionDefault.OP;
                if (permission == null || permission.getDefault() != wanted) {
                    missing.add(kit.permission());
                }
            }
            return missing.isEmpty() ? null : "not registered as configured: " + String.join(", ", missing);
        });
        test.check(id(), "crate keys in kits exist", () -> {
            List<String> unknown = new ArrayList<>();
            for (Kit kit : this.settings.get().kits()) {
                for (String crate : kit.keys().keySet()) {
                    if (!this.crateKeys.crates().contains(crate)) {
                        unknown.add(kit.id() + " -> " + crate);
                    }
                }
            }
            return unknown.isEmpty() ? null : "kits give keys of crates that no longer exist: " + String.join(", ", unknown);
        });
        test.check(id(), "cooldown math", () -> {
            Cooldown day = Cooldown.every(Duration.ofHours(24));
            long claimed = 1_000_000_000L;
            KitStatus waiting = day.status(claimed, claimed + Duration.ofHours(20).toMillis());
            if (!(waiting instanceof KitStatus.Waiting w) || !w.left().equals(Duration.ofHours(4))) {
                return "a 24h kit claimed 20h ago should wait 4h, got " + waiting;
            }
            if (!day.status(claimed, claimed + Duration.ofHours(24).toMillis()).ready()) {
                return "a 24h kit claimed 24h ago should be ready";
            }
            return Cooldown.ONCE.status(claimed, Long.MAX_VALUE) instanceof KitStatus.Claimed ? null : "a once kit stays claimed";
        });
        test.checkAsync(id(), "kit claims in memory match storage", this.claims::verify);
    }
}
