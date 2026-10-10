package net.siftvanilla.e2e;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.SiftCore;
import net.siftvanilla.siftcore.SiftCorePlugin;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.feature.spawn.SpawnFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** What a scenario can do: spawn bots, run console commands, read SiftCore state, assert. */
public final class E2E {

    /** A failed expectation. */
    public static final class Failure extends RuntimeException {
        public Failure(String message) {
            super(message, null, false, false);
        }
    }

    private final Plugin plugin;
    private final Logger log;
    private final List<Bot> bots = new ArrayList<>();
    private final String run = Long.toString(System.nanoTime() % 46_656L, 36);
    private String step = "start";

    public E2E(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public Services services() {
        SiftCorePlugin core = (SiftCorePlugin) Bukkit.getPluginManager().getPlugin("SiftCore");
        if (core == null || core.core() == null) {
            throw new Failure("SiftCore is not running");
        }
        return core.core().services();
    }

    /**
     * The running instance of a SiftCore feature, for scenarios that drive a feature's service directly (for example
     * the stats recorder that the combat feature reports kills to). Test-only access to the composition root.
     */
    public <T> T feature(Class<T> type) {
        SiftCorePlugin core = (SiftCorePlugin) Bukkit.getPluginManager().getPlugin("SiftCore");
        if (core == null || core.core() == null) {
            throw new Failure("SiftCore is not running");
        }
        try {
            Field field = SiftCore.class.getDeclaredField("features");
            field.setAccessible(true);
            for (Object feature : (List<?>) field.get(core.core())) {
                if (type.isInstance(feature)) {
                    return type.cast(feature);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new Failure("cannot read SiftCore's features: " + e);
        }
        throw new Failure("SiftCore has no " + type.getSimpleName());
    }

    /** A player name unique to this run (base name up to 10 characters plus a run suffix). */
    public String name(String base) {
        String trimmed = base.length() > 10 ? base.substring(0, 10) : base;
        return trimmed + "_" + this.run;
    }

    public void step(String description) {
        this.step = description;
        this.log.info("  step: " + description);
    }

    public String currentStep() {
        return this.step;
    }

    /**
     * Connects a bot, waits until it is in the world and moves it just outside SiftCore's protected spawn area, onto
     * the ground a few blocks past its edge. New players arrive at the spawn point, where nobody can build, fight or
     * be hurt; scenarios that mine, place or hit happen in the open world like they would on the live server.
     */
    public Bot bot(String name) {
        Bot bot = botAtSpawn(name);
        leaveSpawn(bot);
        return bot;
    }

    /** The protected spawn area, or null when SiftCore runs without the spawn feature. */
    public SpawnArea spawnArea() {
        try {
            return feature(SpawnFeature.class).area();
        } catch (Failure absent) {
            return null;
        }
    }

    /**
     * Moves a bot that stands inside the protected spawn area to the ground just past its edge (diagonally, so the
     * spot stays within 64 blocks of the spawn on both axes), and waits until the bot's client is there too.
     */
    public void leaveSpawn(Bot bot) {
        SpawnArea area = spawnArea();
        if (area == null) {
            return;
        }
        Location here = onPlayer(bot.name, () -> player(bot.name).getLocation());
        if (!area.contains(here)) {
            return;
        }
        Location column = null;
        for (int distance = 4; distance <= 4_096 && column == null; distance += 2) {
            Location candidate = here.clone().add(distance / Math.sqrt(2), 0, distance / Math.sqrt(2));
            if (!area.contains(candidate) && !area.contains(candidate.clone().add(2, 0, 2))) {
                column = candidate;
            }
        }
        if (column == null) {
            throw new Failure("no open ground near the spawn of " + here.getWorld().getName());
        }
        Location target = ground(here.getWorld(), column.getBlockX(), column.getBlockZ(), here.getYaw());
        player(bot.name).teleportAsync(target);
        eventually(() -> Math.abs(bot.x() - target.getX()) < 0.5 && Math.abs(bot.z() - target.getZ()) < 0.5
            && onPlayer(bot.name, () -> player(bot.name).getLocation().distanceSquared(target)) < 0.25,
            bot.name + " stands outside the protected spawn at " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
        sleep(250);
    }

    /**
     * Where a player stands on the top block of a column (loads, or generates, its chunk; read on the region thread
     * that owns it). Used to move bots without leaving them inside terrain.
     */
    public Location ground(World world, int x, int z, float yaw) {
        CompletableFuture<Location> ground = new CompletableFuture<>();
        world.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                ground.completeExceptionally(error);
                return;
            }
            Bukkit.getRegionScheduler().execute(this.plugin, world, x >> 4, z >> 4, () ->
                ground.complete(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5, yaw, 0f)));
        });
        try {
            return ground.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure("could not find the ground at " + x + " " + z + ": " + e);
        }
    }

    /** Connects a bot and waits until it is in the world, where it joined (new players arrive at the spawn point). */
    public Bot botAtSpawn(String name) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            Bot bot = new Bot(name);
            this.bots.add(bot);
            long start = System.currentTimeMillis();
            bot.connect(Bukkit.getPort());
            if (Bot.await(() -> bot.loaded() && Bukkit.getPlayerExact(name) != null, 30_000)) {
                log(name + " joined in " + (System.currentTimeMillis() - start) + " ms");
                sleep(500);
                return bot;
            }
            log(name + " did not join on attempt " + attempt + " (" + bot.disconnectReason() + "), retrying");
            bot.quit();
            this.bots.remove(bot);
            sleep(1_000);
        }
        throw new Failure(name + " could not join");
    }

    public Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new Failure(name + " is not online");
        }
        return player;
    }

    public UUID uuid(String name) {
        return player(name).getUniqueId();
    }

    /** Runs a console command on the global thread and waits for it to finish. */
    public void console(String command) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure("console command '" + command + "' failed: " + e.getCause());
        }
        sleep(200);
    }

    /**
     * Runs a command on the global thread as a console-like sender (it has every permission) and returns what the
     * command told that sender, one plain-text entry per message.
     */
    public List<String> consoleOutput(String command) {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message -> lines.add(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure("command '" + command + "' failed: " + e.getCause());
        }
        sleep(200);
        return List.copyOf(lines);
    }

    /** Runs code on a player's thread and returns its result. */
    public <T> T onPlayer(String name, Supplier<T> action) {
        Player player = player(name);
        CompletableFuture<T> future = new CompletableFuture<>();
        player.getScheduler().run(this.plugin, task -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, () -> future.completeExceptionally(new Failure(name + " left")));
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure("action on " + name + " failed: " + e.getCause());
        }
    }

    public long money(String name) {
        return services().ledger().balance(uuid(name), Currency.MONEY);
    }

    /**
     * Holds SiftCore's database writer for {@code millis}, like a slow disk or a reconnect: transactions queued meanwhile
     * apply in memory at once but commit only afterwards, so a scenario can make a player leave (or anything else
     * happen) between a trade and its commit. Completes when the writer is free again.
     */
    public CompletableFuture<Object> stallStorage(long millis) {
        return services().database().write(c -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
    }

    /**
     * The copy of a menu grid SiftCore keeps in a player's own data ({@code siftcore:<key>}, e.g. {@code sell_grid}),
     * read on the player's thread; empty when there is none.
     */
    public List<org.bukkit.inventory.ItemStack> gridCopy(String name, String key) {
        return onPlayer(name, () -> {
            List<byte[]> encoded = player(name).getPersistentDataContainer().get(new org.bukkit.NamespacedKey("siftcore", key),
                org.bukkit.persistence.PersistentDataType.LIST.byteArrays());
            List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
            if (encoded != null) {
                for (byte[] bytes : encoded) {
                    items.add(org.bukkit.inventory.ItemStack.deserializeBytes(bytes));
                }
            }
            return items;
        });
    }

    /** Writes such a copy directly, as a crash with the menu open would have left it in the saved player data. */
    public void setGridCopy(String name, String key, List<org.bukkit.inventory.ItemStack> items) {
        onPlayer(name, () -> {
            List<byte[]> encoded = new ArrayList<>();
            for (org.bukkit.inventory.ItemStack item : items) {
                encoded.add(item.serializeAsBytes());
            }
            player(name).getPersistentDataContainer().set(new org.bukkit.NamespacedKey("siftcore", key),
                org.bukkit.persistence.PersistentDataType.LIST.byteArrays(), encoded);
            return null;
        });
    }

    /** Items of a material in the copy of a menu grid. */
    public int gridCopyCount(String name, String key, org.bukkit.Material material) {
        int total = 0;
        for (org.bukkit.inventory.ItemStack item : gridCopy(name, key)) {
            if (item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** Where the server keeps a player's file ({@code players/data/<uuid>.dat}). */
    private static java.nio.file.Path playerFile(UUID uuid) {
        return net.minecraft.server.MinecraftServer.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
            .resolve(uuid + ".dat");
    }

    /** Saves a player's file now, on their thread (like an autosave or a trade's save). */
    public void savePlayer(String name) {
        onPlayer(name, () -> {
            player(name).saveData();
            return null;
        });
    }

    /**
     * A player's file as it is on disk right now. The server writes it only when it saves the player, so this is exactly
     * what a crash at this moment would leave behind; {@link #crashTo} makes the player's next join start from it.
     */
    public byte[] savedPlayerFile(UUID uuid) {
        try {
            return java.nio.file.Files.readAllBytes(playerFile(uuid));
        } catch (java.io.IOException e) {
            throw new Failure("can't read the player file of " + uuid + ": " + e);
        }
    }

    /**
     * Simulates a crash for one player: kicks them (the server saves them as they leave) and then puts back a file read
     * earlier with {@link #savedPlayerFile}, so their next join loads what a server restarting after a crash at that
     * moment would load. Storage (claim box, balances) keeps everything committed meanwhile, as it would after a crash.
     */
    public void crashTo(Bot bot, UUID uuid, byte[] saved) {
        kick(bot, "e2e: the server crashed");
        sleep(300);
        try {
            java.nio.file.Files.write(playerFile(uuid), saved);
        } catch (java.io.IOException e) {
            throw new Failure("can't write the player file of " + uuid + ": " + e);
        }
    }

    /** Kicks a player (on their thread) and waits until they are gone and the bot noticed. */
    public void kick(Bot bot, String why) {
        onPlayer(bot.name, () -> {
            player(bot.name).kick(net.kyori.adventure.text.Component.text(why));
            return null;
        });
        eventually(() -> Bukkit.getPlayerExact(bot.name) == null && bot.disconnected(), bot.name + " left");
    }

    public long shards(String name) {
        return services().ledger().balance(uuid(name), Currency.SHARDS);
    }

    public void expect(boolean condition, String what) {
        if (!condition) {
            throw new Failure("expected " + what);
        }
    }

    public void eventually(BooleanSupplier condition, String what) {
        expect(Bot.await(condition, 10_000), what);
    }

    public void eventually(BooleanSupplier condition, long millis, String what) {
        expect(Bot.await(condition, millis), what);
    }

    /** Waits for a dialog whose title contains the text, returning it. */
    public Bot.SeenDialog dialog(Bot bot, String titleContains) {
        return dialog(bot, titleContains, 10_000);
    }

    /** Waits up to {@code millis} for a dialog whose title contains the text (longer for dialogs after a warmup). */
    public Bot.SeenDialog dialog(Bot bot, String titleContains, long millis) {
        String wanted = titleContains.toLowerCase();
        if (!Bot.await(() -> bot.dialog() != null && bot.dialog().title().toLowerCase().contains(wanted), millis)) {
            throw new Failure("expected " + bot.name + " sees a dialog titled '" + titleContains + "' (last: "
                + (bot.dialog() == null ? "none" : bot.dialog().title()) + "; action bar: " + bot.actionBar() + ")");
        }
        return bot.dialog();
    }

    /** Clicks a dialog button and waits until the dialog changes or closes. */
    public void click(Bot bot, String label, Map<String, Object> values) {
        Bot.SeenDialog before = bot.dialog();
        expect(bot.clickButton(label, values), bot.name + " can click '" + label + "' in " + (before == null ? "no dialog" : before.title()
            + " " + before.buttons()));
        eventually(() -> bot.dialog() != before, bot.name + " gets a response after clicking '" + label + "'");
    }

    public void click(Bot bot, String label) {
        click(bot, label, Map.of());
    }

    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void log(String message) {
        this.log.info("  " + message);
    }

    /** Leaves a bot connected after the scenario (for scenarios that continue across a server restart). */
    public void keep(Bot bot) {
        this.bots.remove(bot);
    }

    /** Disconnects every bot of this scenario. */
    public void cleanup() {
        for (Bot bot : this.bots) {
            bot.quit();
        }
        for (Bot bot : this.bots) {
            Bot.await(() -> Bukkit.getPlayerExact(bot.name) == null, 5_000);
        }
        this.bots.clear();
    }
}
