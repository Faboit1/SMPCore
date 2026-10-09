package net.siftvanilla.siftcore.feature.spawners;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.SpawnerBreakEvent;
import net.siftvanilla.siftcore.api.event.SpawnerPlaceEvent;
import net.siftvanilla.siftcore.api.event.SpawnerSellEvent;
import net.siftvanilla.siftcore.api.event.SpawnerStackEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.economy.ClaimHandouts;
import net.siftvanilla.siftcore.economy.Handoffs;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Everything players do with spawners, each change as one transaction on the economy lock: placing, stacking, taking
 * items out, selling the storage, collecting XP and picking spawners up, plus repairing records whose block is gone.
 * <p>
 * Rules followed throughout: items leave their source before anything is granted (the hand before stacking, the
 * storage before items or money are handed out); granted items go into the claim box inside the transaction or
 * are handed out only after the transaction is committed, through tracked hand-overs that end in the claim box when
 * the player can't receive them (left, server stopping); stored XP moves into the player's {@link XpBox} in the
 * transaction that takes it out of a spawner and is paid out from there; every check is repeated inside the
 * transaction; and whenever a transaction changes a storage it writes that storage's complete snapshot in the same
 * database unit.
 */
final class SpawnerService {

    static final String BYPASS = "siftcore.spawners.bypass";
    static final String STACK_BONUS = "siftcore.spawners.stack";
    static final String SELL_KIND = "spawner_sell";
    private static final int GIVE_XP_CHUNK = 1_000_000;
    /** The sell rate for an owner who is offline: no rank bonus and no booster (both are only known for online players). */
    private static final WorthLookup.SellRate NO_RATE = new WorthLookup.SellRate(1.0, 1.0, 0);

    /** How a storage action ended. */
    enum Outcome {
        DONE,
        NOTHING,
        FAILED
    }

    private final Services services;
    private final Setting<SpawnersSettings> settings;
    private final SpawnerRegistry registry;
    private final SpawnerItemFactory items;
    private final Handout handout;
    private final WorthLookup worth;
    private final TeamLookup teams;
    private final CombatStatus combat;
    private final VanishStatus vanish;
    private final Logger logger;
    private final ClaimHandouts handouts;
    private final XpBox xpBox;
    private final Handoffs<Long> xpPayouts = new Handoffs<>();
    private final Map<String, Material> materials = new ConcurrentHashMap<>();
    private volatile IdSequence ids;

    SpawnerService(Services services, Setting<SpawnersSettings> settings, SpawnerRegistry registry, SpawnerItemFactory items,
                   Handout handout, WorthLookup worth, TeamLookup teams, CombatStatus combat, VanishStatus vanish) {
        this.services = services;
        this.settings = settings;
        this.registry = registry;
        this.items = items;
        this.handout = handout;
        this.worth = worth;
        this.teams = teams;
        this.combat = combat;
        this.vanish = vanish;
        this.logger = services.plugin().getLogger();
        this.handouts = new ClaimHandouts(services.deliveries(), services.scheduler(), this.logger,
            () -> services.core().get().savePlayerAfterTrade());
        this.xpBox = new XpBox(services.ledger(), services.database(), this.logger);
    }

    void ids(IdSequence ids) {
        this.ids = ids;
    }

    /** XP waiting for players (loaded at startup). */
    XpBox xpBox() {
        return this.xpBox;
    }

    /**
     * Shutdown, after the database flushed: stops taking XP out of XP boxes (what is waiting stays there for the next
     * join), waits for claims, hand-overs and XP payouts whose storage answer is still on its way (storage completes
     * commits on more than one thread, so a flush can return before their callbacks ran), then puts every item that was
     * not handed over into the claim box and every bit of XP that was not paid out back into the XP box. Storage must
     * still be open; flush it afterwards.
     */
    void drainHandovers(Duration timeout) {
        this.xpPayouts.close();
        boolean idle = this.handouts.awaitIdle(timeout);
        idle &= this.xpPayouts.awaitIdle(timeout);
        if (!idle) {
            this.logger.warning("Some spawner items or XP were still waiting for storage at shutdown");
        }
        this.handouts.drain();
        this.xpPayouts.drain();
    }

    SpawnerRegistry registry() {
        return this.registry;
    }

    SpawnerItemFactory items() {
        return this.items;
    }

    SpawnersSettings settings() {
        return this.settings.get();
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    /** Sends a message on its channel. */
    void tell(CommandSender to, MessageKey key, Arg... args) {
        messenger().send(to, key, args);
    }

    private Lang lang() {
        return this.services.lang();
    }

    // ------------------------------------------------------------------ helpers

    static SpawnerPos pos(Block block) {
        return new SpawnerPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    static SpawnerPos pos(Location location) {
        return new SpawnerPos(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** The block location of a spawner, or null when its world is not loaded. */
    static Location location(SpawnerPos pos) {
        World world = Bukkit.getWorld(pos.world());
        return world == null ? null : new Location(world, pos.x(), pos.y(), pos.z());
    }

    /** The managed spawner at a block, or null. */
    ManagedSpawner at(Block block) {
        return this.registry.at(pos(block));
    }

    Material material(String item) {
        return this.materials.computeIfAbsent(item, key -> {
            Material material = Material.matchMaterial(key);
            return material == null || !material.isItem() ? Material.AIR : material;
        });
    }

    /** The name of an item for text (translated by the client). */
    static Component itemName(Material material) {
        return Component.translatable(material.translationKey());
    }

    String name(String mob) {
        MobDef def = this.settings.get().mob(mob);
        return def == null ? MobDef.fallbackName(mob) : def.name();
    }

    String lowerName(String mob) {
        return name(mob).toLowerCase(Locale.ROOT);
    }

    String ownerName(UUID owner) {
        Player online = Bukkit.getPlayer(owner);
        return online != null ? online.getName() : this.services.directory().name(owner);
    }

    Access access(Player player, ManagedSpawner spawner) {
        return Access.of(spawner.owner, player.getUniqueId(), player.hasPermission(BYPASS), this.teams);
    }

    /** True when the player may use the spawner; otherwise tells them whose it is. */
    boolean allowed(Player player, ManagedSpawner spawner) {
        if (access(player, spawner).allowed()) {
            return true;
        }
        messenger().send(player, SpawnersMessages.NOT_YOURS, Arg.text("owner", ownerName(spawner.owner)));
        return false;
    }

    /** Whether the player is in a team (whose members' spawners they may use). */
    boolean inTeam(UUID player) {
        return this.teams.team(player).isPresent();
    }

    /** The spawners of the player's teammates (not their own), by owner name, then mob, then id. */
    List<ManagedSpawner> teamSpawners(UUID player) {
        Optional<Long> team = this.teams.team(player);
        if (team.isEmpty()) {
            return List.of();
        }
        List<ManagedSpawner> list = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (UUID member : this.teams.members(team.get())) {
            if (!member.equals(player)) {
                List<ManagedSpawner> owned = this.registry.ownedBy(member);
                if (!owned.isEmpty()) {
                    names.put(member, ownerName(member).toLowerCase(Locale.ROOT));
                    list.addAll(owned);
                }
            }
        }
        list.sort(Comparator.comparing((ManagedSpawner s) -> names.get(s.owner)).thenComparing(s -> s.mob)
            .thenComparingLong(s -> s.id));
        return list;
    }

    /**
     * True when the player may use a storage right now; otherwise tells them how long their combat timer still runs
     * ({@code interaction.block-in-combat}). Storages hold ender pearls and other loot that must not be fetched
     * mid-fight, like the shop and the auction house.
     */
    boolean outOfCombat(Player player) {
        UUID uuid = player.getUniqueId();
        if (!this.settings.get().blockInCombat() || !this.combat.tagged(uuid)) {
            return true;
        }
        messenger().send(player, SpawnersMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(uuid)));
        return false;
    }

    /** A consistent copy of a spawner's state. */
    ManagedSpawner.State state(ManagedSpawner spawner) {
        return this.services.ledger().locked(spawner::state);
    }

    long capacity(ManagedSpawner spawner, int stack) {
        return StorageMath.capacity(stack, this.settings.get().slots(spawner.mob));
    }

    long xpCapacity(int stack) {
        return StorageMath.xpCapacity(stack, this.settings.get().xpPerSpawner());
    }

    /** The stack cap for this player stacking this mob (mob cap plus their rank bonus). */
    int stackCap(Player player, String mob) {
        int bonus = Limits.highest(player, STACK_BONUS, 0);
        return StorageMath.stackCap(this.settings.get().cap(mob), bonus);
    }

    /** Under the lock: marks the storage as written and returns the snapshot to write. */
    private static SpawnerStore.Snapshot persistNow(ManagedSpawner spawner) {
        spawner.dirty(false);
        return new SpawnerStore.Snapshot(spawner.id, spawner.xp(), spawner.storage.snapshot());
    }

    private void saveIfConfigured(Player player) {
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    private static boolean silkTouch(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        return !tool.isEmpty() && tool.containsEnchantment(Enchantment.SILK_TOUCH);
    }

    /**
     * Sets a spawner block up on its region thread: the mob it shows, never spawning anything (a spawn count of 0
     * makes the vanilla spawner skip its tick entirely), and showing its spinning mob within the activation radius.
     */
    void setupBlock(Block block, String mob, int radius) {
        BlockState state = block.getState(false);
        if (!(state instanceof CreatureSpawner spawner)) {
            return;
        }
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(mob));
        if (type != null && type != spawner.getSpawnedType()) {
            spawner.setSpawnedType(type);
        }
        spawner.setSpawnCount(0);
        spawner.setRequiredPlayerRange(radius);
        spawner.update(true, false);
    }

    // ------------------------------------------------------------------ placing

    /** Checks a placement before it happens (HIGHEST); false means cancel it. Runs on the player's thread. */
    boolean checkPlace(Player player, Block block, String mob) {
        SpawnersSettings s = this.settings.get();
        MobDef def = s.mob(mob);
        if (def == null || !def.enabled()) {
            messenger().send(player, SpawnersMessages.PLACE_DISABLED, Arg.text("mob", lowerName(mob)));
            return false;
        }
        if (s.disabledWorlds().contains(block.getWorld().getName().toLowerCase(Locale.ROOT))) {
            messenger().send(player, SpawnersMessages.PLACE_WORLD);
            return false;
        }
        SpawnerPos pos = pos(block);
        if (s.maxPerChunk() > 0 && this.registry.at(pos) == null && this.registry.countInChunk(pos.chunk()) >= s.maxPerChunk()) {
            messenger().send(player, SpawnersMessages.PLACE_CHUNK_FULL, Arg.number("max", s.maxPerChunk()));
            return false;
        }
        if (!new SpawnerPlaceEvent(player, block.getLocation(), mob).callEvent()) {
            messenger().send(player, SpawnersMessages.PLACE_CANCELLED);
            return false;
        }
        return true;
    }

    /**
     * Records a placement that is going ahead (MONITOR); false means it could not be recorded and must be cancelled.
     * Runs on the player's thread, which owns the block.
     */
    boolean place(Player player, Block block, String mob) {
        SpawnerPos pos = pos(block);
        ManagedSpawner stale = this.registry.at(pos);
        if (stale != null) {
            // The block was not a spawner a moment ago, so that record had lost its block: refund it first.
            orphan(stale, "its block was replaced");
        }
        if (this.ids == null) {
            messenger().send(player, CoreMessages.ACTION_FAILED);
            return false;
        }
        UUID owner = player.getUniqueId();
        long now = System.currentTimeMillis();
        ManagedSpawner spawner = new ManagedSpawner(this.ids.next(), pos, owner, mob, 1, 0, now);
        LedgerTx tx = LedgerTx.builder().actor(owner).silent().note("place " + mob + " spawner")
            .check(() -> this.registry.at(pos) == null ? null : "occupied")
            .apply(() -> this.registry.add(spawner), () -> {
                spawner.removed(true);
                this.registry.remove(spawner);
            })
            .write(SpawnerStore.insert(spawner))
            .build();
        TransactionResult result = this.services.ledger().executeDomain(tx);
        if (!result.success()) {
            report(player, result);
            return false;
        }
        int radius = this.settings.get().radius();
        setupBlock(block, mob, radius);
        spawner.syncedRadius(radius);
        this.registry.loaded(pos.chunk(), now + this.settings.get().interval().toMillis());
        messenger().send(player, SpawnersMessages.PLACED, Arg.text("mob", lowerName(mob)));
        // The server uses the item up after this event. Saving the player once it is gone means a crash can't hand
        // the item back while the spawner stays recorded (or is refunded when the chunk rolled back). The player file
        // can't be saved without the item any earlier than the next tick (a MONITOR handler can't take it out of the
        // hand before the server does), so a hard crash within that one tick, after the insert was stored, could still
        // leave both: an accepted window of about 50 ms that players can neither widen nor time.
        this.services.scheduler().entity(player, () -> saveIfConfigured(player), null);
        Location location = block.getLocation();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.scheduler().region(location, () -> undoPlacement(location, mob, owner));
            }
        });
        return true;
    }

    /** After a placement could not be stored: takes the block away again and gives the spawner back. */
    private void undoPlacement(Location location, String mob, UUID owner) {
        Block block = location.getBlock();
        if (block.getType() == Material.SPAWNER && this.registry.at(pos(block)) == null) {
            block.setType(Material.AIR, false);
        }
        this.handout.toClaimBox(owner, List.of(this.items.item(mob, 1)), "place-failed");
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            messenger().send(player, CoreMessages.ACTION_FAILED);
        }
    }

    // ------------------------------------------------------------------ stacking

    /** Adds spawner items from the main hand to a placed stack: one, or the whole held stack. Player's thread. */
    void stack(Player player, ManagedSpawner spawner, boolean whole) {
        if (spawner.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return;
        }
        if (!allowed(player, spawner)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack hand = inventory.getItem(slot);
        if (!spawner.mob.equals(this.items.mobOf(hand))) {
            messenger().send(player, SpawnersMessages.STACK_WRONG_MOB, Arg.text("mob", lowerName(spawner.mob)));
            return;
        }
        MobDef def = this.settings.get().mob(spawner.mob);
        if (def == null || !def.enabled()) {
            messenger().send(player, SpawnersMessages.STACK_DISABLED, Arg.text("mob", lowerName(spawner.mob)));
            return;
        }
        int cap = stackCap(player, spawner.mob);
        int adding = StorageMath.acceptable(spawner.stack(), cap, whole ? hand.getAmount() : 1);
        if (adding <= 0) {
            messenger().send(player, SpawnersMessages.STACK_FULL, Arg.number("cap", cap));
            return;
        }
        Location location = location(spawner.pos);
        if (location != null && !new SpawnerStackEvent(player, location, spawner.mob, spawner.owner, spawner.stack(), adding).callEvent()) {
            messenger().send(player, SpawnersMessages.STACK_CANCELLED);
            return;
        }
        // Remove before grant: the items leave the hand, and the saved player file, before the stack grows, so a crash
        // can never leave them both in the player file and in the stack.
        ItemStack taken = hand.asQuantity(adding);
        int left = hand.getAmount() - adding;
        inventory.setItem(slot, left <= 0 ? null : hand.asQuantity(left));
        saveIfConfigured(player);
        int[] after = new int[1];
        LedgerTx tx = LedgerTx.builder().actor(player.getUniqueId()).silent().note("stack " + adding + " " + spawner.mob + " spawners")
            .check(() -> spawner.removed() ? "gone" : spawner.stack() + adding > cap ? "full" : null)
            .apply(() -> {
                spawner.stack(spawner.stack() + adding);
                after[0] = spawner.stack();
            }, () -> spawner.stack(spawner.stack() - adding))
            .write(c -> SpawnerStore.updateStack(spawner.id, after[0]).run(c))
            .build();
        TransactionResult result = this.services.ledger().executeDomain(tx);
        if (!result.success()) {
            putBack(player, slot, taken);
            saveIfConfigured(player);
            switch (result.reason() == null ? "" : result.reason()) {
                case "gone" -> messenger().send(player, SpawnersMessages.GONE);
                case "full" -> messenger().send(player, SpawnersMessages.STACK_FULL, Arg.number("cap", cap));
                default -> report(player, result);
            }
            return;
        }
        if (spawner.owner.equals(player.getUniqueId())) {
            messenger().send(player, SpawnersMessages.STACKED, Arg.number("amount", adding), Arg.text("mob", lowerName(spawner.mob)),
                Arg.number("stack", after[0]), Arg.number("cap", cap));
        } else {
            // A teammate (or staff) adds to someone else's stack: the spawners now belong to its owner.
            messenger().send(player, SpawnersMessages.STACKED_OTHER, Arg.number("amount", adding), Arg.text("owner", ownerName(spawner.owner)),
                Arg.text("mob", lowerName(spawner.mob)), Arg.number("stack", after[0]), Arg.number("cap", cap));
        }
        // Shutdown waits for this commit's callback, so a hand-over it starts is never left until storage closed.
        this.handouts.begin();
        result.committed().whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    // The stack went back down in memory; the spawners go back to the player (or their claim box).
                    messenger().send(player, CoreMessages.ACTION_FAILED);
                    this.handouts.give(player, List.of(taken), Handout.SOURCE, "stack-failed").thenAccept(outcome -> {
                        if (outcome.left() > 0) {
                            messenger().send(player, SpawnersMessages.CLAIM_BOX, Arg.number("count", outcome.left()));
                        }
                    });
                }
            } finally {
                this.handouts.end();
            }
        });
    }

    /** Puts items back into the slot they came from, or anywhere, or the claim box. Player's thread. */
    private void putBack(Player player, int slot, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        ItemStack now = inventory.getItem(slot);
        if (now == null || now.isEmpty()) {
            inventory.setItem(slot, item);
            return;
        }
        if (now.isSimilar(item) && now.getAmount() + item.getAmount() <= now.getMaxStackSize()) {
            now.setAmount(now.getAmount() + item.getAmount());
            inventory.setItem(slot, now);
            return;
        }
        long claimed = this.handout.give(player, List.of(item), "stack-failed");
        if (claimed > 0) {
            messenger().send(player, SpawnersMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
    }

    // ------------------------------------------------------------------ storage

    /** Takes up to {@code wanted} of an item out of the storage into the player's inventory. Player's thread. */
    CompletableFuture<Outcome> take(Player player, ManagedSpawner spawner, String item, long wanted) {
        if (spawner.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        if (!allowed(player, spawner) || !outOfCombat(player)) {
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        Material material = material(item);
        if (material == Material.AIR) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        ItemStack unit = ItemStack.of(material);
        long amount = Math.min(wanted, Handout.room(player, unit));
        if (amount <= 0) {
            messenger().send(player, CoreMessages.INVENTORY_FULL);
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        long[] taken = new long[1];
        SpawnerStore.Snapshot[] snapshot = new SpawnerStore.Snapshot[1];
        LedgerTx tx = LedgerTx.builder().actor(player.getUniqueId()).silent().note("take " + item + " from spawner " + spawner.id)
            .check(() -> spawner.removed() ? "gone" : spawner.storage.amount(item) <= 0 ? "none" : null)
            .apply(() -> {
                taken[0] = spawner.storage.take(item, amount);
                spawner.touch();
                snapshot[0] = persistNow(spawner);
            }, () -> {
                spawner.storage.add(item, taken[0]);
                spawner.touch();
                spawner.dirty(true);
            })
            .write(c -> SpawnerStore.persist(snapshot[0]).run(c))
            .build();
        TransactionResult result = this.services.ledger().executeDomain(tx);
        if (!result.success()) {
            switch (result.reason() == null ? "" : result.reason()) {
                case "gone" -> messenger().send(player, SpawnersMessages.GONE);
                case "none" -> messenger().send(player, SpawnersMessages.NONE_LEFT);
                default -> report(player, result);
            }
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        String ref = "spawner:" + spawner.id;
        // Shutdown waits for this commit's callback, so the hand-over it starts is never left until storage closed.
        this.handouts.begin();
        result.committed().whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    messenger().send(player, CoreMessages.ACTION_FAILED);
                    done.complete(Outcome.FAILED);
                    return;
                }
                // The items left the storage with the commit: they are handed over on the player's thread, or go into
                // their claim box when that can't happen (they left, the server is stopping).
                List<ItemStack> stacks = Handout.stacks(unit, taken[0]);
                this.handouts.give(player, stacks, Handout.SOURCE, ref).whenComplete((outcome, failure) -> {
                    if (failure == null && outcome.handed() > 0) {
                        messenger().send(player, SpawnersMessages.TOOK, Arg.number("amount", taken[0]),
                            Arg.component("item", itemName(material)));
                    }
                    if (failure == null && outcome.left() > 0) {
                        messenger().send(player, SpawnersMessages.CLAIM_BOX, Arg.number("count", outcome.left()));
                    }
                    done.complete(Outcome.DONE);
                });
            } finally {
                this.handouts.end();
            }
        });
        return done;
    }

    /** Sells everything sellable in the storage for the player, at their sell bonus. Player's thread. */
    CompletableFuture<Outcome> sellAll(Player player, ManagedSpawner spawner) {
        if (spawner.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        if (!allowed(player, spawner) || !outOfCombat(player)) {
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        WorthLookup.SellRate rate = this.worth.rate(player);
        double multiplier = rate.multiplier();
        Map<String, Long> contents = this.services.ledger().locked(spawner.storage::snapshot);
        Sale sale = price(contents, rate);
        if (sale == null) {
            messenger().send(player, SpawnersMessages.SELL_TOO_MUCH);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        if (sale.sold().isEmpty() || sale.total() <= 0) {
            messenger().send(player, SpawnersMessages.NOTHING_TO_SELL);
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        if (!this.services.ledger().available()) {
            messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        Location location = location(spawner.pos);
        if (location != null && !new SpawnerSellEvent(player, location, spawner.mob, spawner.owner, sale.byMaterial(this), sale.total(),
            multiplier).callEvent()) {
            messenger().send(player, SpawnersMessages.SELL_CANCELLED);
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        UUID uuid = player.getUniqueId();
        SpawnerStore.Snapshot[] snapshot = new SpawnerStore.Snapshot[1];
        LedgerTx tx = LedgerTx.builder().actor(uuid)
            .note("spawner " + spawner.mob + ": " + sale.count() + " items")
            .source(uuid, Currency.MONEY, sale.total(), SELL_KIND, "spawner:" + spawner.id)
            .check(() -> {
                if (spawner.removed()) {
                    return "gone";
                }
                for (Map.Entry<String, Long> line : sale.sold().entrySet()) {
                    if (spawner.storage.amount(line.getKey()) < line.getValue()) {
                        return "changed";
                    }
                }
                // Prices change only with /sift reload; a sale priced before one is not paid at the old prices.
                return samePrices(sale) ? null : "changed";
            })
            .apply(() -> {
                sale.sold().forEach(spawner.storage::take);
                spawner.touch();
                snapshot[0] = persistNow(spawner);
            }, () -> {
                sale.sold().forEach(spawner.storage::add);
                spawner.touch();
                spawner.dirty(true);
            })
            .write(c -> SpawnerStore.persist(snapshot[0]).run(c))
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        if (!result.success()) {
            switch (result.status()) {
                case BALANCE_LIMIT -> messenger().send(player, SpawnersMessages.SELL_BALANCE_FULL);
                case UNAVAILABLE -> messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
                case CANCELLED -> messenger().send(player, SpawnersMessages.SELL_CANCELLED);
                default -> {
                    if ("gone".equals(result.reason())) {
                        messenger().send(player, SpawnersMessages.GONE);
                    } else if ("changed".equals(result.reason())) {
                        messenger().send(player, SpawnersMessages.CHANGED);
                    } else {
                        messenger().send(player, CoreMessages.ACTION_FAILED);
                    }
                }
            }
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        receipt(player, spawner.mob, sale, rate.rank());
        return result.committed().handle((ignored, error) -> {
            if (error != null) {
                // The money and the items went back together; tell the player nothing was sold.
                messenger().send(player, CoreMessages.ACTION_FAILED);
                return Outcome.FAILED;
            }
            return Outcome.DONE;
        });
    }

    /**
     * A priced sale: item key to amount sold, total paid, the base value of each line, and the server sell booster it
     * was priced with (percent, 0 = none).
     */
    record Sale(Map<String, Long> sold, Map<String, Long> values, long total, long count, int boost) {

        Map<Material, Long> byMaterial(SpawnerService service) {
            Map<Material, Long> map = new EnumMap<>(Material.class);
            this.sold.forEach((item, amount) -> map.merge(service.material(item), amount, Long::sum));
            return map;
        }
    }

    /**
     * Prices storage contents at a player's sell rate (their rank multiplier with the running booster on top); null
     * when the total is too large for one payment.
     */
    Sale price(Map<String, Long> contents, WorthLookup.SellRate rate) {
        double multiplier = rate.multiplier();
        Map<String, Long> sold = new TreeMap<>();
        Map<String, Long> values = new TreeMap<>();
        BigInteger base = BigInteger.ZERO;
        long count = 0;
        for (Map.Entry<String, Long> line : contents.entrySet()) {
            Material material = material(line.getKey());
            if (material == Material.AIR || line.getValue() <= 0) {
                continue;
            }
            long unit = this.worth.unitPrice(ItemStack.of(material));
            if (unit <= 0) {
                continue;
            }
            BigInteger value = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(line.getValue()));
            sold.put(line.getKey(), line.getValue());
            values.put(line.getKey(), value.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
            base = base.add(value);
            count = StorageMath.saturatingAdd(count, line.getValue());
        }
        BigInteger total = new BigDecimal(base).multiply(BigDecimal.valueOf(Math.max(0, multiplier)))
            .setScale(0, RoundingMode.FLOOR).toBigInteger();
        long max = this.services.core().get().money().maxAmount();
        if (total.compareTo(BigInteger.valueOf(max)) > 0) {
            return null;
        }
        return new Sale(sold, values, total.longValue(), count, rate.boost());
    }

    /** Whether every line of a sale is still worth what it was priced at. */
    private boolean samePrices(Sale sale) {
        for (Map.Entry<String, Long> line : sale.sold().entrySet()) {
            Material material = material(line.getKey());
            long unit = material == Material.AIR ? 0 : this.worth.unitPrice(ItemStack.of(material));
            long value = unit <= 0 ? 0 : StorageMath.saturatingMultiply(unit, line.getValue());
            if (unit <= 0 || value != sale.values().getOrDefault(line.getKey(), -1L)) {
                return false;
            }
        }
        return true;
    }

    /** The receipt in chat; {@code multiplier} is the player's own bonus (the booster is named separately). */
    private void receipt(Player player, String mob, Sale sale, double multiplier) {
        Lang lang = lang();
        List<Component> card = new ArrayList<>();
        sale.sold().forEach((item, amount) -> card.add(lang.get(SpawnersMessages.RECEIPT_LINE, Arg.number("amount", amount),
            Arg.component("item", itemName(material(item))), Arg.money("value", sale.values().getOrDefault(item, 0L)))));
        Component count = Component.text(Lang.number(sale.count()))
            .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), card)));
        Arg booster = boosterNote(sale);
        if (multiplier > 1.0) {
            messenger().send(player, SpawnersMessages.SOLD_BONUS, Arg.component("count", count), Arg.text("mob", lowerName(mob)),
                Arg.money("total", sale.total()), Arg.text("multiplier", multiplier(multiplier)), booster);
        } else {
            messenger().send(player, SpawnersMessages.SOLD, Arg.component("count", count), Arg.text("mob", lowerName(mob)),
                Arg.money("total", sale.total()), booster);
        }
    }

    /** The receipts' {@code <booster>} part: " incl. +10% booster" when a server sell booster raised the sale. */
    Arg boosterNote(Sale sale) {
        return Arg.component("booster", sale.boost() > 0 && sale.total() > 0
            ? lang().get(SpawnersMessages.BOOSTER_NOTE, Arg.number("percent", sale.boost())) : Component.empty());
    }

    static String multiplier(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Gives the stored XP to the player. Player's thread. */
    CompletableFuture<Outcome> collectXp(Player player, ManagedSpawner spawner) {
        if (spawner.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        if (!allowed(player, spawner) || !outOfCombat(player)) {
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        long amount = spawner.xp();
        if (amount <= 0) {
            messenger().send(player, SpawnersMessages.XP_NONE);
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        SpawnerStore.Snapshot[] snapshot = new SpawnerStore.Snapshot[1];
        LedgerTx.Builder tx = LedgerTx.builder().actor(player.getUniqueId()).silent()
            .note("collect " + amount + " xp from spawner " + spawner.id)
            .check(() -> spawner.removed() ? "gone" : spawner.xp() < amount ? "changed" : null)
            .apply(() -> {
                spawner.xp(spawner.xp() - amount);
                snapshot[0] = persistNow(spawner);
            }, () -> {
                spawner.xp(spawner.xp() + amount);
                spawner.dirty(true);
            })
            .write(c -> SpawnerStore.persist(snapshot[0]).run(c));
        // The XP moves into the player's XP box with the same commit, and is paid out of it on their thread.
        this.xpBox.credit(tx, player.getUniqueId(), amount);
        TransactionResult result = this.services.ledger().executeDomain(tx.build());
        if (!result.success()) {
            if ("gone".equals(result.reason())) {
                messenger().send(player, SpawnersMessages.GONE);
            } else if ("changed".equals(result.reason())) {
                messenger().send(player, SpawnersMessages.CHANGED);
            } else {
                report(player, result);
            }
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                messenger().send(player, CoreMessages.ACTION_FAILED);
                done.complete(Outcome.FAILED);
                return;
            }
            payOutXp(player, SpawnersMessages.XP_COLLECTED)
                .whenComplete((given, failure) -> done.complete(failure == null && given > 0 ? Outcome.DONE : Outcome.FAILED));
        });
        return done;
    }

    /**
     * Pays out all XP waiting in the player's XP box: takes it out in a transaction, then gives it on the player's
     * thread once that is stored (with {@code message}, if not null). If it can't be given after all (they left, the
     * server is stopping), it goes back into the box for their next join. Completes with the XP given (0 when none
     * was). Safe from any thread.
     */
    CompletableFuture<Long> payOutXp(Player player, MessageKey message) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || this.xpBox.waiting(uuid) <= 0) {
            return CompletableFuture.completedFuture(0L);
        }
        // Once shutdown began the XP stays in the box (stored) rather than being taken out when it could no longer be
        // put back; a payout already under way is waited for (see drainHandovers).
        if (!this.xpPayouts.beginUnlessClosed()) {
            return CompletableFuture.completedFuture(0L);
        }
        XpBox.Taken taken;
        try {
            taken = this.xpBox.take(uuid);
        } catch (RuntimeException e) {
            this.xpPayouts.end();
            throw e;
        }
        if (!taken.result().success() || taken.xp() <= 0) {
            this.xpPayouts.end();
            return CompletableFuture.completedFuture(0L);
        }
        long xp = taken.xp();
        CompletableFuture<Long> done = new CompletableFuture<>();
        taken.result().committed().whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    // Rolled back: the XP is still waiting in the box.
                    done.complete(0L);
                    return;
                }
                this.xpPayouts.hand(this.services.scheduler(), player, xp, amount -> {
                    if (!player.isOnline()) {
                        this.xpBox.credit(uuid, amount, "its player left before it was paid out");
                        done.complete(0L);
                        return;
                    }
                    giveXp(player, amount);
                    if (message != null) {
                        messenger().send(player, message, Arg.number("xp", amount));
                    }
                    done.complete(amount);
                }, amount -> {
                    this.xpBox.credit(uuid, amount, "its player left before it was paid out");
                    done.complete(0L);
                });
            } finally {
                this.xpPayouts.end();
            }
        });
        return done;
    }

    /** Gives XP points on the player's thread, in chunks an int can hold. */
    void giveXp(Player player, long amount) {
        boolean mending = this.settings.get().applyMending();
        long left = amount;
        while (left > 0) {
            int part = (int) Math.min(GIVE_XP_CHUNK, left);
            player.giveExp(part, mending);
            left -= part;
        }
    }

    // ------------------------------------------------------------------ picking up

    /** Checks a pickup before it happens (HIGHEST); false means cancel it. Player's thread. */
    boolean checkBreak(Player player, ManagedSpawner spawner) {
        if (spawner.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return false;
        }
        if (!allowed(player, spawner)) {
            return false;
        }
        SpawnersSettings s = this.settings.get();
        if (s.requireSilkTouch() && !player.hasPermission(BYPASS) && !silkTouch(player)) {
            messenger().send(player, SpawnersMessages.NEED_SILK_TOUCH);
            return false;
        }
        ManagedSpawner.State state = state(spawner);
        long stacks = claimStacks(state.items(), s.breakStorage());
        if (stacks > s.maxClaimStacks()) {
            messenger().send(player, SpawnersMessages.TOO_MUCH_STORED, Arg.number("stacks", stacks), Arg.number("max", s.maxClaimStacks()));
            return false;
        }
        Location location = location(spawner.pos);
        if (location != null && !new SpawnerBreakEvent(player, location, spawner.mob, spawner.owner, state.stack(), state.used(),
            state.xp()).callEvent()) {
            messenger().send(player, SpawnersMessages.BREAK_CANCELLED);
            return false;
        }
        return true;
    }

    /** Claim box stacks the stored items would take when picked up in this mode. */
    long claimStacks(Map<String, Long> stored, SpawnersSettings.BreakStorage mode) {
        long stacks = 0;
        for (Map.Entry<String, Long> line : stored.entrySet()) {
            Material material = material(line.getKey());
            if (material == Material.AIR) {
                continue;
            }
            if (mode == SpawnersSettings.BreakStorage.SELL && this.worth.unitPrice(ItemStack.of(material)) > 0) {
                continue;
            }
            stacks += StorageMath.stacks(line.getValue(), material.getMaxStackSize());
        }
        return stacks;
    }

    /**
     * Picks a spawner up (MONITOR): one transaction removes it, sends its storage to the owner (claim box or sold),
     * puts all its spawner items into the breaker's claim box and its stored XP into the breaker's XP box. After the
     * commit the spawner items that fit are claimed into the inventory and the XP is paid out, both on the breaker's
     * thread; whatever can't be (the breaker left, the server stopped) waits for them. False means the pickup must be
     * cancelled.
     */
    boolean pickUp(Player player, ManagedSpawner spawner) {
        SpawnersSettings s = this.settings.get();
        ManagedSpawner.State state = state(spawner);
        if (state.removed()) {
            messenger().send(player, SpawnersMessages.GONE);
            return false;
        }
        UUID breaker = player.getUniqueId();
        UUID owner = spawner.owner;
        String ref = "spawner:" + spawner.id;
        // The spawner items that fit the inventory now get a reference of their own, to be claimed after the commit.
        String handRef = ref + ":pickup";
        ItemStack unit = this.items.item(spawner.mob, 1);
        long toInventory = Math.min(state.stack(), Handout.room(player, unit));
        long toClaimBox = state.stack() - toInventory;

        Map<String, Long> forClaimBox = new TreeMap<>(state.items());
        Sale sale = null;
        if (s.breakStorage() == SpawnersSettings.BreakStorage.SELL && !state.items().isEmpty()) {
            Player ownerOnline = Bukkit.getPlayer(owner);
            sale = price(state.items(), ownerOnline == null ? NO_RATE : this.worth.rate(ownerOnline));
            if (sale != null && sale.total() > 0) {
                sale.sold().keySet().forEach(forClaimBox::remove);
            } else {
                sale = null;
            }
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(breaker).note("pick up " + state.stack() + " " + spawner.mob + " spawners");
        if (sale != null) {
            tx.source(owner, Currency.MONEY, sale.total(), SELL_KIND, ref);
        } else {
            tx.silent();
        }
        long version = state.version();
        SpawnerPos.ChunkKey chunk = spawner.pos.chunk();
        Sale priced = sale;
        tx.check(() -> spawner.removed() ? "gone" : spawner.version() != version ? "changed" : null)
            .check(() -> priced == null || samePrices(priced) ? null : "changed")
            .apply(() -> {
                spawner.removed(true);
                this.registry.remove(spawner);
            }, () -> {
                spawner.removed(false);
                this.registry.add(spawner);
                this.registry.loaded(chunk, System.currentTimeMillis());
                spawner.dirty(true);
            })
            .write(SpawnerStore.delete(spawner.id));
        long claimItems = 0;
        for (Map.Entry<String, Long> line : forClaimBox.entrySet()) {
            Material material = material(line.getKey());
            if (material != Material.AIR && line.getValue() > 0) {
                this.handout.addTo(tx, owner, ItemStack.of(material), line.getValue(), ref);
                claimItems += line.getValue();
            }
        }
        if (toInventory > 0) {
            this.handout.addTo(tx, breaker, unit, toInventory, handRef);
        }
        if (toClaimBox > 0) {
            this.handout.addTo(tx, breaker, unit, toClaimBox, ref);
        }
        this.xpBox.credit(tx, breaker, state.xp());
        TransactionResult result = this.services.ledger().execute(tx.build());
        if (!result.success()) {
            switch (result.reason() == null ? "" : result.reason()) {
                case "gone" -> messenger().send(player, SpawnersMessages.GONE);
                case "changed" -> messenger().send(player, SpawnersMessages.CHANGED);
                default -> report(player, result);
            }
            return false;
        }
        Sale sold = sale;
        long stored = claimItems;
        long xp = state.xp();
        int stack = state.stack();
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                // Reverted in memory; the next loot cycle sees the block is gone and refunds the owner.
                messenger().send(player, CoreMessages.ACTION_FAILED);
                return;
            }
            // Everything is stored for the breaker already: what can't be handed over now simply waits for them.
            Handoffs.onEntity(this.services.scheduler(), player, () -> this.handouts.claim(player, Handout.SOURCE, handRef)
                .whenComplete((outcome, failure) -> {
                    long inClaimBox = toClaimBox + (failure != null || outcome.failed() ? toInventory : outcome.left());
                    pickedUp(player, spawner, stack, inClaimBox, stored, sold);
                }), () -> { });
            if (xp > 0) {
                payOutXp(player, SpawnersMessages.XP_COLLECTED);
            }
        });
        return true;
    }

    private void pickedUp(Player player, ManagedSpawner spawner, int stack, long spawnersInClaimBox, long stored, Sale sold) {
        String mob = lowerName(spawner.mob);
        if (stack == 1) {
            messenger().send(player, SpawnersMessages.PICKED_UP_ONE, Arg.text("mob", mob));
        } else {
            messenger().send(player, SpawnersMessages.PICKED_UP_MANY, Arg.number("amount", stack), Arg.text("mob", mob));
        }
        if (spawnersInClaimBox > 0) {
            messenger().send(player, SpawnersMessages.CLAIM_BOX, Arg.number("count", spawnersInClaimBox));
        }
        boolean own = spawner.owner.equals(player.getUniqueId());
        String owner = ownerName(spawner.owner);
        if (sold != null) {
            messenger().send(player, own ? SpawnersMessages.STORAGE_SOLD : SpawnersMessages.STORAGE_SOLD_FOR_OWNER,
                Arg.number("count", sold.count()), Arg.money("total", sold.total()), Arg.text("owner", owner), boosterNote(sold));
        }
        if (stored > 0) {
            if (own) {
                messenger().send(player, SpawnersMessages.STORAGE_TO_YOUR_CLAIM_BOX, Arg.number("count", stored));
            } else {
                messenger().send(player, SpawnersMessages.STORAGE_TO_OWNER_CLAIM_BOX, Arg.number("count", stored), Arg.text("owner", owner));
            }
        }
        if (!own) {
            Player ownerOnline = Bukkit.getPlayer(spawner.owner);
            if (ownerOnline != null) {
                if (this.vanish.vanished(player.getUniqueId())) {
                    // A vanished staff member stays unseen: the owner learns that staff did it, not who.
                    messenger().send(ownerOnline, SpawnersMessages.OWNER_PICKED_UP_STAFF, Arg.text("mob", mob), Arg.number("amount", stack));
                } else {
                    messenger().send(ownerOnline, SpawnersMessages.OWNER_PICKED_UP, Arg.text("name", player.getName()),
                        Arg.text("mob", mob), Arg.number("amount", stack));
                }
            }
        }
    }

    /** A natural spawner mined with silk touch: gives a spawner item of its mob when configured. Player's thread. */
    boolean pickUpNatural(Player player, Block block) {
        if (!this.settings.get().naturalPickup() || !silkTouch(player) || player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        // A spawn count of 0 marks a block SiftCore once set up: a SiftCore spawner whose record is gone (picked up,
        // then the chunk was rolled back by a crash). It was already given back once, so it never drops again.
        if (!(block.getState(false) instanceof CreatureSpawner spawner) || spawner.getSpawnedType() == null
            || spawner.getSpawnCount() <= 0) {
            return false;
        }
        String mob = spawner.getSpawnedType().getKey().getKey();
        if (!this.items.mobs().contains(mob)) {
            return false;
        }
        long claimed = this.handout.give(player, List.of(this.items.item(mob, 1)), "natural");
        messenger().send(player, SpawnersMessages.NATURAL_PICKED_UP, Arg.text("mob", lowerName(mob)));
        if (claimed > 0) {
            messenger().send(player, SpawnersMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
        return true;
    }

    // ------------------------------------------------------------------ repairs

    /**
     * Refunds a spawner whose block is gone (removed by an admin tool, a world edit, or a pickup whose storage write
     * failed): the record is deleted, its spawners and stored items go to the owner's claim box and its stored XP into
     * the owner's XP box, in one transaction. An online owner is told and gets the XP right away; an offline owner gets
     * it when they next join. Safe from any thread.
     */
    void orphan(ManagedSpawner spawner, String why) {
        ManagedSpawner.State state = state(spawner);
        if (state.removed()) {
            return;
        }
        String ref = "spawner:" + spawner.id;
        long version = state.version();
        SpawnerPos.ChunkKey chunk = spawner.pos.chunk();
        LedgerTx.Builder tx = LedgerTx.builder().actor("system").silent().note("refund spawner " + spawner.id + ": " + why)
            .check(() -> spawner.removed() ? "gone" : spawner.version() != version ? "changed" : null)
            .apply(() -> {
                spawner.removed(true);
                this.registry.remove(spawner);
            }, () -> {
                spawner.removed(false);
                this.registry.add(spawner);
                this.registry.loaded(chunk, System.currentTimeMillis());
                spawner.dirty(true);
            })
            .write(SpawnerStore.delete(spawner.id));
        this.handout.addTo(tx, spawner.owner, this.items.item(spawner.mob, 1), state.stack(), ref);
        long items = 0;
        for (Map.Entry<String, Long> line : state.items().entrySet()) {
            Material material = material(line.getKey());
            if (material != Material.AIR && line.getValue() > 0) {
                this.handout.addTo(tx, spawner.owner, ItemStack.of(material), line.getValue(), ref);
                items += line.getValue();
            }
        }
        this.xpBox.credit(tx, spawner.owner, state.xp());
        TransactionResult result = this.services.ledger().executeDomain(tx.build());
        if (!result.success()) {
            if (!"gone".equals(result.reason())) {
                this.logger.warning("Spawner " + spawner.id + " needs a refund because " + why + ", but it could not be made yet: "
                    + result.status() + " " + result.reason() + ". It will be tried again.");
            }
            return;
        }
        long stored = items;
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Refunding spawner " + spawner.id + " could not be stored; it will be tried again", error);
                return;
            }
            // The XP waits in the owner's XP box: an online owner gets it right away, otherwise when they next join.
            Player owner = Bukkit.getPlayer(spawner.owner);
            if (owner != null) {
                messenger().send(owner, SpawnersMessages.REFUNDED, Arg.text("mob", lowerName(spawner.mob)), Arg.number("amount", state.stack()),
                    Arg.text("location", spawner.pos.coordinates()), Arg.text("world", spawner.pos.world()), Arg.number("count", stored));
                if (state.xp() > 0) {
                    payOutXp(owner, SpawnersMessages.REFUNDED_XP);
                }
            }
            this.logger.warning("Refunded spawner " + spawner.id + " (" + state.stack() + " " + spawner.mob + " at " + spawner.pos.world()
                + " " + spawner.pos.coordinates() + ", owner " + ownerName(spawner.owner) + ") because " + why + ": its " + state.stack()
                + " spawners and " + stored + " stored items went to the owner's claim box"
                + (state.xp() <= 0 ? "" : " and its " + state.xp() + " stored XP to the owner's XP box"
                + (owner != null ? " (paid out now)" : " (paid out when they next join)")) + ".");
        });
    }

    // ------------------------------------------------------------------ admin

    /** Gives spawner items to a player: online into the inventory (rest to the claim box), offline to the claim box. */
    void give(CommandSender sender, UUID target, String mob, int amount) {
        String name = ownerName(target);
        List<ItemStack> stacks = Handout.stacks(this.items.item(mob, 1), amount);
        String actor = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        this.services.audit().record(actor, "spawners.give", target.toString(), amount + " " + mob + " spawners");
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            var task = this.services.scheduler().entity(online, () -> {
                long claimed = this.handout.give(online, stacks, "admin");
                messenger().send(online, SpawnersMessages.RECEIVED, Arg.number("amount", amount), Arg.text("mob", lowerName(mob)));
                if (claimed > 0) {
                    messenger().send(online, SpawnersMessages.CLAIM_BOX, Arg.number("count", claimed));
                }
                messenger().send(sender, SpawnersMessages.GIVEN, Arg.number("amount", amount), Arg.text("mob", lowerName(mob)),
                    Arg.text("name", name));
            }, () -> toClaimBoxForAdmin(sender, target, name, mob, amount, stacks));
            if (task != Task.NONE) {
                return;
            }
        }
        toClaimBoxForAdmin(sender, target, name, mob, amount, stacks);
    }

    private void toClaimBoxForAdmin(CommandSender sender, UUID target, String name, String mob, int amount,
                                    List<ItemStack> stacks) {
        long stored = this.handout.toClaimBox(target, stacks, "admin");
        if (stored < amount) {
            messenger().send(sender, SpawnersMessages.GIVE_FAILED);
            return;
        }
        messenger().send(sender, SpawnersMessages.GIVEN_CLAIM_BOX, Arg.number("amount", amount), Arg.text("mob", lowerName(mob)),
            Arg.text("name", name));
    }

    // ------------------------------------------------------------------ feedback

    /** Tells the player why a transaction did not happen. */
    private void report(Player player, TransactionResult result) {
        if (result.status() == TransactionStatus.UNAVAILABLE) {
            messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
        } else {
            messenger().send(player, CoreMessages.ACTION_FAILED);
        }
    }
}
