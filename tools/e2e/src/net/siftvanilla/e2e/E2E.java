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
import org.bukkit.Bukkit;
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

    /** Connects a bot and waits until it is in the world. */
    public Bot bot(String name) {
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
        eventually(() -> bot.dialog() != null && bot.dialog().title().toLowerCase().contains(titleContains.toLowerCase()),
            bot.name + " sees a dialog titled '" + titleContains + "' (last: " + (bot.dialog() == null ? "none" : bot.dialog().title()) + ")");
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
