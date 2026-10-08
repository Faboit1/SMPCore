package net.siftvanilla.siftcore.feature.spawners;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Spawners: stackable spawners that never spawn a mob. While a player is near, each one makes its mobs' loot and XP
 * virtually into a storage players open, take from, sell from ({@code spawner_sell}, at their sell bonus) and
 * collect XP from. Spawners are picked up with silk touch (stack back as items, storage to the owner's claim box or
 * sold), protected from explosions and pistons, usable by the owner's team, and listed by {@code /spawners}.
 * Offers {@link SpawnerItems} to the shop and crates.
 */
public final class SpawnersFeature implements Feature {

    private final Services services;
    private final Logger logger;
    private final Setting<SpawnersSettings> settings;
    private final SpawnerRegistry registry = new SpawnerRegistry();
    private final SpawnerStore store;
    private final SpawnerItemFactory items;
    private final SpawnerService service;
    private final WriteBehind writeBehind;
    private final LootCycle cycle;
    private final StorageMenus menus;
    private final SpawnerDialogs dialogs;
    private final SpawnersCommands commands;
    private final WorthLookup worth;
    private Task flushTimer = Task.NONE;
    private Task cycleTimer = Task.NONE;

    /**
     * @param worth  item prices and sell bonuses (the sell feature), for selling storages
     * @param teams  who is in whose team (the teams feature), for access
     * @param vanish vanished staff (the staff feature), who don't keep spawners going unless configured and stay
     *               unnamed when they pick up someone's spawner
     * @param afk    AFK players (the AFK feature), who keep spawners going unless configured otherwise
     * @param combat combat tags (the combat feature), which keep tagged players out of storages
     */
    public SpawnersFeature(Services services, List<ConfigProblem> problems, WorthLookup worth, TeamLookup teams,
                           VanishStatus vanish, AfkStatus afk, CombatStatus combat) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.worth = worth;
        this.settings = services.configs().register("features/spawners.yml",
            reader -> SpawnersSettings.parse(reader, catalog()), problems);
        services.lang().register(SpawnersMessages.class);
        var perms = services.permissions();
        perms.declare(SpawnersCommands.COMMAND, "List your spawners with /spawners", true);
        perms.declare(SpawnersCommands.ADMIN, "Give spawners and inspect them with /spawners give, list, cycle and info", false);
        perms.declare(SpawnerService.BYPASS, "Use, open and pick up anyone's spawners, without silk touch", false);
        perms.declare(SpawnerService.STACK_BONUS + ".unlimited", "Stack spawners up to the hard limit of "
            + StorageMath.HARD_STACK_CAP, PermissionDefault.FALSE);
        this.store = new SpawnerStore(services.database(), this.logger);
        this.items = new SpawnerItemFactory(new NamespacedKey(services.plugin(), "spawner_mob"), this.settings::get, services.lang());
        Handout handout = new Handout(services.deliveries(), this.logger);
        this.service = new SpawnerService(services, this.settings, this.registry, this.items, handout, worth, teams, combat, vanish);
        this.writeBehind = new WriteBehind(services.ledger(), this.store, this.logger);
        this.cycle = new LootCycle(services.scheduler(), services.ledger(), this.settings, this.registry, this.service, afk, vanish);
        this.menus = new StorageMenus(services.menus(), this.service, worth);
        this.dialogs = new SpawnerDialogs(services, this.service, this.menus, worth);
        this.commands = new SpawnersCommands(services, this.service, this.dialogs, this.cycle, this.writeBehind);
    }

    /** Item and mob keys of this Minecraft version, for validating the config. */
    private static SpawnersSettings.Catalog catalog() {
        return new SpawnersSettings.Catalog(
            key -> {
                Material material = Material.matchMaterial(key);
                return material != null && material.isItem() && !material.isAir();
            },
            key -> {
                NamespacedKey namespaced = NamespacedKey.fromString(key);
                EntityType type = namespaced == null ? null : Registry.ENTITY_TYPE.get(namespaced);
                return type != null && type.isAlive() && type.isSpawnable();
            });
    }

    @Override
    public String id() {
        return "spawners";
    }

    /** Spawner items for the shop and crates. */
    public SpawnerItems items() {
        return this.items;
    }

    @Override
    public void enable() throws Exception {
        int orphanRows = this.store.removeOrphanItems();
        if (orphanRows > 0) {
            this.logger.warning("Removed " + orphanRows + " stored spawner items that belonged to no spawner.");
        }
        List<SpawnerStore.Row> rows = this.store.loadAll();
        this.service.ids(IdSequence.forTable(this.services.database(), "spawners"));
        long stacked = 0;
        Set<String> missingWorlds = new HashSet<>();
        int unknownMobs = 0;
        for (SpawnerStore.Row row : rows) {
            ManagedSpawner spawner = new ManagedSpawner(row.id(), row.pos(), row.owner(), row.mob(), Math.max(1, row.stack()),
                Math.max(0, row.xp()), row.created());
            spawner.storage.replace(row.items());
            this.registry.add(spawner);
            stacked += spawner.stack();
            if (Bukkit.getWorld(row.pos().world()) == null) {
                missingWorlds.add(row.pos().world());
            }
            if (this.settings.get().mob(row.mob()) == null) {
                unknownMobs++;
            }
        }
        markLoadedChunks();
        Bukkit.getPluginManager().registerEvents(new SpawnerListener(this.service, this.registry, this.writeBehind, this.menus,
            this.settings), this.services.plugin());
        this.cycleTimer = this.services.scheduler().globalTimer(this.cycle::tick, 20L, 20L);
        startFlushTimer(this.settings.get());
        this.settings.onReload(this::startFlushTimer);
        this.services.hub().register(new HubEntry("spawners", 40, SpawnersMessages.HUB_LABEL, SpawnersMessages.HUB_DESCRIPTION,
            SpawnersCommands.COMMAND, player -> this.dialogs.openList(player, 1, this::backToMenu)));
        registerPlaceholders();
        this.logger.info("Spawners: " + rows.size() + " placed spawners (" + stacked + " stacked) of " + this.registry.owners()
            + " players, " + this.settings.get().enabledMobs().size() + " spawner types."
            + (missingWorlds.isEmpty() ? "" : " Spawners in worlds that are not loaded wait for them: " + String.join(", ", missingWorlds) + ".")
            + (unknownMobs == 0 ? "" : " " + unknownMobs + " spawners are of mobs no longer configured and make no loot."));
    }

    /** Finds which chunks with spawners are loaded right now, each on its own region thread. */
    private void markLoadedChunks() {
        long first = System.currentTimeMillis() + this.settings.get().interval().toMillis();
        for (SpawnerPos.ChunkKey chunk : Set.copyOf(this.registry.chunks())) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null) {
                continue;
            }
            this.services.scheduler().region(world, chunk.x(), chunk.z(), () -> {
                if (world.isChunkLoaded(chunk.x(), chunk.z())) {
                    this.registry.loaded(chunk, first);
                }
            });
        }
    }

    private void startFlushTimer(SpawnersSettings settings) {
        this.flushTimer.cancel();
        this.flushTimer = this.services.scheduler().asyncTimer(() -> this.writeBehind.flush(this.registry.all()),
            settings.flushInterval(), settings.flushInterval());
    }

    private void backToMenu(Submission submission) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(submission.player());
        } else {
            submission.close();
        }
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("spawners_count", "How many spawner blocks you own",
            player -> Integer.toString(this.registry.countBy(player.getUniqueId())));
        placeholders.register("spawners_stacked", "How many spawners you own in all, counting stacks",
            player -> Long.toString(this.registry.stackedBy(player.getUniqueId())));
        placeholders.register("spawners_stored", "Items waiting in your spawners' storage",
            player -> Long.toString(this.registry.sumBy(player.getUniqueId(), spawner -> spawner.storage.used())));
        placeholders.register("spawners_xp", "XP waiting in your spawners",
            player -> Long.toString(this.registry.sumBy(player.getUniqueId(), ManagedSpawner::xp)));
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        this.cycleTimer.cancel();
        this.flushTimer.cancel();
        try {
            int written = this.writeBehind.flush(this.registry.all());
            this.services.database().flush();
            if (written > 0) {
                this.logger.info("Spawners: wrote the loot of " + written + " spawners.");
            }
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Writing spawner loot at shutdown failed", e);
        }
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "loot math", () -> {
            DropEntry entry = new DropEntry("minecraft:rotten_flesh", 0, 2, 1.0);
            SplittableRandom random = new SplittableRandom(7);
            long total = 0;
            for (int i = 0; i < 50; i++) {
                total += LootMath.roll(List.of(entry), 10_000, random).getOrDefault("minecraft:rotten_flesh", 0L);
            }
            double mean = total / 50.0;
            return Math.abs(mean - 10_000) < 100 ? null : "10,000 kills dropped " + mean + " on average instead of about 10,000";
        });
        test.check(id(), "storage fills to capacity", () -> {
            Map<String, Long> fitted = StorageMath.fit(Map.of("a", 300L, "b", 100L), 200);
            long sum = fitted.values().stream().mapToLong(Long::longValue).sum();
            return StorageMath.capacity(2, 9) == 1152 && sum == 200 && fitted.get("a") == 150 ? null
                : "capacity or proportional fill is wrong: " + fitted;
        });
        test.check(id(), "spawner items", () -> {
            Set<String> mobs = this.items.mobs();
            if (mobs.isEmpty()) {
                return "no spawner type is enabled";
            }
            String mob = mobs.iterator().next();
            ItemStack item = this.items.create(mob, 3).orElse(null);
            if (item == null || item.getAmount() != 3 || !mob.equals(this.items.mobOf(item))) {
                return "a " + mob + " spawner item does not read back as " + mob;
            }
            if (this.items.mobOf(ItemStack.of(Material.SPAWNER)) != null) {
                return "a plain spawner reads as a SiftCore spawner";
            }
            if (this.worth.unitPrice(item) != 0) {
                return "spawner items can be sold to the server";
            }
            return this.items.create("not_a_mob", 1).isPresent() ? "an unknown mob makes a spawner item" : null;
        });
        test.check(id(), "every drop is an item", () -> {
            for (MobDef mob : this.settings.get().mobs().values()) {
                for (DropEntry drop : mob.drops()) {
                    if (this.service.material(drop.item()) == Material.AIR) {
                        return mob.id() + " drops " + drop.item() + ", which is not an item";
                    }
                }
            }
            return null;
        });
        test.check(id(), "indexes agree", () -> this.services.ledger().locked(this.registry::verify));
        test.checkAsync(id(), "stored spawners match memory", () -> this.store.database().write(c -> null)
            .thenCompose(ignored -> this.store.count())
            .thenApply(count -> count == this.registry.size() ? null
                : "the database has " + count + " spawners, memory has " + this.registry.size()));
    }
}
