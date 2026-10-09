package net.siftvanilla.siftcore.feature.crates;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Crates and virtual keys. Players open crates with keys from the keyall, the store, the shard shop or staff; each
 * opening spends one key and pays one weighted reward (items, money, shards, keys of another crate, spawners or a
 * command) in a single economy transaction. Crates can be blocks in the world. Implements {@link CrateKeys} for the
 * features that hand out keys.
 */
public final class CratesFeature implements Feature, Listener {

    public static final String PERMISSION_USE = "siftcore.command.crates";
    public static final String PERMISSION_KEYALL = "siftcore.command.keyall";
    public static final String PERMISSION_ADMIN = "siftcore.admin.crates";

    /** Whether a player sees other players' announced crate wins (in the settings dialog). */
    public static final Toggle WIN_ANNOUNCEMENTS = new Toggle("crate-wins", true, CratesMessages.SETTING_WINS,
        CratesMessages.SETTING_WINS_DESCRIPTION, null);

    private static final Duration PRUNE_EVERY = Duration.ofHours(6);

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final SpawnerItems spawners;
    private final KeyService keys;
    private final RewardItems items;
    private final Handouts handouts;
    private final RewardCommands rewardCommands;
    private final CrateOpener opener;
    private final CrateText text;
    private final Keyall keyall;
    private final CrateDialogs dialogs;
    private final CrateBlocks blocks;
    private final CrateCommands commands;
    private volatile Task pruneTask = Task.NONE;

    /**
     * @param worth    sell values, for showing what item rewards are worth (the sell feature)
     * @param spawners makes spawner items for spawner rewards (the spawners feature)
     * @param vanish   vanished staff are left out of the keyall and win announcements (the staff feature)
     * @param combat   players in combat can't open crates (the combat tags)
     * @param afk      AFK players can be left out of the keyall (the AFK feature)
     */
    public CratesFeature(Services services, List<ConfigProblem> problems, WorthLookup worth, SpawnerItems spawners,
                         VanishStatus vanish, CombatStatus combat, AfkStatus afk) {
        this.services = services;
        this.spawners = spawners;
        this.settings = services.configs().register("features/crates.yml",
            reader -> CratesSettings.parse(reader, RewardItems.catalog(), services.core().get().money()), problems);
        services.lang().register(CratesMessages.class);
        services.settings().register(WIN_ANNOUNCEMENTS);
        var perms = services.permissions();
        perms.declare(PERMISSION_USE, "Use /crates, open crates and preview them", true);
        perms.declare(PERMISSION_KEYALL, "See when the next keyall is with /keyall", true);
        perms.declare(PERMISSION_ADMIN, "Give and take keys, start a keyall and manage crate blocks", false);
        this.keys = new KeyService(services.ledger(), services.database(), () -> this.settings.get().crateIds(),
            System::currentTimeMillis);
        this.items = new RewardItems(spawners, () -> services.lang().style().palette());
        this.handouts = new Handouts(services);
        this.rewardCommands = new RewardCommands(services.database(), task -> services.scheduler().global(task),
            command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command), services.plugin().getLogger());
        this.text = new CrateText(services.lang());
        CrateLog log = new CrateLog(services.database());
        this.opener = new CrateOpener(services, this.settings, this.keys, this.items, this.handouts, this.rewardCommands, log,
            this.text, vanish, combat, WIN_ANNOUNCEMENTS);
        this.keyall = new Keyall(services, this.settings, this.keys, this.text, vanish, afk);
        this.dialogs = new CrateDialogs(services, this.settings, this.keys, this.items, this.opener, this.text, worth, this.keyall);
        this.blocks = new CrateBlocks(services, this.settings, new CrateBlocks.Actions() {
            @Override
            public void view(Player player, String crate) {
                CratesFeature.this.dialogs.crate(player, crate, null);
            }

            @Override
            public void preview(Player player, String crate) {
                CratesFeature.this.dialogs.preview(player, crate, null);
            }

            @Override
            public void quickOpen(Player player, String crate) {
                CratesFeature.this.opener.open(player, crate, true, result -> {
                    if (result instanceof CrateOpener.Refused refused) {
                        CratesFeature.this.opener.report(player, refused);
                    }
                });
            }
        });
        this.commands = new CrateCommands(services, this.settings, this.keys, this.dialogs, this.opener, this.blocks, this.keyall,
            log, this.items, this.text, worth);
    }

    @Override
    public String id() {
        return "crates";
    }

    /** Virtual keys, for the shard shop, store delivery and anything else that hands out keys. */
    public CrateKeys keys() {
        return this.keys;
    }

    /** The current settings (read-only). */
    public CratesSettings settings() {
        return this.settings.get();
    }

    @Override
    public void enable() throws Exception {
        CratesSettings settings = this.settings.get();
        this.keys.load(settings.rememberGrants());
        this.blocks.load();
        // Command rewards whose openings were stored but that had not run when the server stopped run once it is up.
        this.rewardCommands.resume(this.rewardCommands.leftovers());
        Bukkit.getPluginManager().registerEvents(this.blocks, this.services.plugin());
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.keyall.start();
        this.settings.onReload(next -> {
            this.blocks.rebuild();
            this.keyall.reloaded(next);
        });
        this.pruneTask = this.services.scheduler().asyncTimer(() -> this.keys.prune(this.settings.get().rememberGrants()),
            PRUNE_EVERY, PRUNE_EVERY);
        this.services.hub().register(new HubEntry("crates", 45, CratesMessages.HUB_LABEL, CratesMessages.HUB_DESCRIPTION,
            PERMISSION_USE, player -> this.dialogs.list(player, () -> openMenu(player))));
        registerPlaceholders();
        logSummary(settings);
    }

    private void logSummary(CratesSettings settings) {
        int rewards = 0;
        int leftOut = 0;
        for (Crate crate : settings.crates()) {
            rewards += crate.rewards().size();
            leftOut += crate.rewards().size() - this.items.available(crate).size();
        }
        String spawnerNote = leftOut == 0 ? "" : "; " + leftOut + " spawner rewards are left out until spawner items are available";
        String keyallNote = settings.keyall().enabled()
            ? "keyall every " + Durations.format(settings.keyall().interval()) + ", next in " + Durations.format(this.keyall.remaining())
            : "keyall off";
        this.services.plugin().getLogger().info("Crates: " + settings.crates().size() + " crates with " + rewards + " rewards"
            + spawnerNote + ", " + this.blocks.all().size() + " crate blocks, " + keyallNote + ".");
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
        placeholders.registerPrefix("keys_", "keys_<crate>", "Your keys of a crate (keys_basic)",
            (player, crate) -> Integer.toString(this.keys.keys(player.getUniqueId(), crate)));
        placeholders.register("keys_total", "Your keys of every crate together", player -> {
            long total = 0;
            for (int count : this.keys.keysOf(player.getUniqueId()).values()) {
                total += count;
            }
            return Long.toString(total);
        });
        placeholders.register("keyall_countdown", "Time until the next keyall (1h 5m), - when it is off",
            player -> this.keyall.enabled() ? Durations.format(this.keyall.remaining()) : "-");
        placeholders.register("keyall_reward", "What the next keyall gives (1 Basic key), - when it is off", player -> {
            var reward = this.keyall.reward();
            return this.keyall.enabled() && reward != null ? TextStyle.plain(reward) : "-";
        });
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!this.settings.get().joinReminder()) {
            return;
        }
        this.services.scheduler().entityLater(player, () -> {
            long total = 0;
            for (int count : this.keys.keysOf(player.getUniqueId()).values()) {
                total += count;
            }
            if (total > 0 && player.isOnline() && player.hasPermission(PERMISSION_USE)) {
                this.services.messenger().send(player, CratesMessages.REMINDER, Arg.component("keys", this.text.count(total)));
            }
        }, null, 80L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.opener.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void disable() {
        this.keyall.stop();
        this.pruneTask.cancel();
        try {
            // Let every queued opening commit and register its hand-over (players can no longer receive items: the
            // region threads have stopped), then put every undelivered item back into the claim box, all before
            // storage closes.
            this.services.database().flush();
            if (!this.handouts.awaitIdle(Duration.ofSeconds(10))) {
                this.services.plugin().getLogger().warning("Some crate rewards were still waiting for storage at shutdown");
            }
            this.handouts.drain();
            this.services.database().flush();
        } catch (RuntimeException e) {
            this.services.plugin().getLogger().log(Level.SEVERE, "Returning pending crate rewards on shutdown failed", e);
        }
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "every crate has rewards to give", () -> {
            List<String> empty = new ArrayList<>();
            for (Crate crate : this.settings.get().crates()) {
                if (this.items.available(crate).isEmpty()) {
                    empty.add(crate.id());
                }
            }
            if (this.settings.get().crates().isEmpty()) {
                return "no crates are configured";
            }
            return empty.isEmpty() ? null : "nothing can be won from " + String.join(", ", empty);
        });
        test.check(id(), "weighted draw follows the weights", () -> {
            WeightedTable<String> table = WeightedTable.of(List.of("a", "b", "c"), s -> switch (s) {
                case "a" -> 1.0;
                case "b" -> 2.0;
                default -> 7.0;
            });
            int[] counts = new int[3];
            for (int i = 0; i < 1000; i++) {
                counts[table.index((i + 0.5) / 1000.0)]++;
            }
            return counts[0] == 100 && counts[1] == 200 && counts[2] == 700 ? null
                : "evenly spread draws gave " + counts[0] + "/" + counts[1] + "/" + counts[2] + " instead of 100/200/700";
        });
        test.check(id(), "shown chances add up to 100%", () -> {
            for (Crate crate : this.settings.get().crates()) {
                List<Reward> available = this.items.available(crate);
                if (available.isEmpty()) {
                    continue;
                }
                double[] weights = new double[available.size()];
                for (int i = 0; i < weights.length; i++) {
                    weights[i] = available.get(i).weight();
                }
                long sum = 0;
                for (long chance : Chances.hundredths(weights)) {
                    sum += chance;
                }
                if (sum != Chances.WHOLE) {
                    return crate.id() + " shows " + Chances.format(sum) + " in total";
                }
            }
            return null;
        });
        test.check(id(), "reward items can be made and stored", () -> {
            for (Crate crate : this.settings.get().crates()) {
                for (Reward reward : this.items.available(crate)) {
                    if (!(reward.kind() instanceof Reward.Item || reward.kind() instanceof Reward.Spawner)) {
                        continue;
                    }
                    ItemStack item = this.items.build(reward).orElse(null);
                    if (item == null || item.isEmpty()) {
                        return crate.id() + "/" + reward.id() + " makes no item";
                    }
                    ItemStack one = item.asQuantity(Math.min(item.getAmount(), item.getMaxStackSize()));
                    if (!ItemStack.deserializeBytes(one.serializeAsBytes()).isSimilar(one)) {
                        return crate.id() + "/" + reward.id() + " does not survive storage";
                    }
                }
            }
            return null;
        });
        test.checkAsync(id(), "keys in memory match storage", this.keys::verify);
        test.check(id(), "crate blocks are live", () -> {
            List<BlockKey> inactive = this.blocks.inactive();
            return inactive.isEmpty() ? null : inactive.size() + " placed crate block(s) belong to a crate that no longer exists or an"
                + " unloaded world (first: " + inactive.getFirst() + "); remove them with /crates block remove";
        });
        test.check(id(), "keyall timer runs", () -> {
            if (!this.keyall.enabled()) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - this.keyall.lastTick() > 10_000) {
                return "the keyall timer has not ticked for " + (now - this.keyall.lastTick()) / 1000 + "s";
            }
            long interval = this.settings.get().keyall().interval().toMillis();
            long ahead = this.keyall.nextRun() - now;
            return ahead > -10_000 && ahead <= interval + 10_000 ? null : "the next keyall is " + ahead / 1000 + "s away";
        });
    }
}
