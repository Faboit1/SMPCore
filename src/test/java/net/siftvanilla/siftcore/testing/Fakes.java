package net.siftvanilla.siftcore.testing;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Test doubles for code that talks to players and the scheduler, without a server: a recording {@link Player} (a
 * dynamic proxy) and a {@link Scheduler} that runs entity tasks at once and keeps timers for the test to tick.
 */
public final class Fakes {

    private Fakes() {
    }

    /** A lang with the plain text styles and no icons (messages without loaded text render as their path). */
    public static Lang lang() {
        return new Lang(new TextStyle(Palette.defaults(), new Icons(Set.of())), () -> null);
    }

    /**
     * A lang with the messages of {@code keys} classes loaded from bundled lang files ({@code lang/core.yml}, ...).
     * Fails when a file has a problem, like the server would report it.
     */
    public static Lang lang(List<String> files, Class<?>... keys) {
        Icons icons;
        try (InputStream in = Fakes.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
            icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the icons", e);
        }
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), () -> null);
        for (Class<?> type : keys) {
            lang.register(type);
        }
        for (String file : files) {
            YamlConfiguration yaml = yaml(file);
            List<?> problems = lang.load(yaml, yaml, file);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(file + ": " + problems);
            }
        }
        return lang;
    }

    /** A bundled resource file (a config or lang file) as YAML. */
    public static YamlConfiguration yaml(String resource) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = Fakes.class.getClassLoader().getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
        return yaml;
    }

    /** Worlds made so far, held strongly: a {@link Location} only keeps a weak reference to its world. */
    private static final Map<String, World> WORLDS = new ConcurrentHashMap<>();

    /** A world that only has a name (the same instance for the same name). */
    public static World world(String name) {
        return WORLDS.computeIfAbsent(name, Fakes::newWorld);
    }

    private static World newWorld(String name) {
        return (World) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {World.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getName" -> name;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "World[" + name + "]";
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    /** A recording player. Every call is logged by method name; messages and teleports are kept. */
    public static final class FakePlayer implements InvocationHandler {

        public final UUID id;
        public final String name;
        public final Player player;
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public final List<Component> actionBar = new CopyOnWriteArrayList<>();
        public final List<Component> chat = new CopyOnWriteArrayList<>();
        public final List<Location> teleports = new CopyOnWriteArrayList<>();
        public final Set<String> permissions = ConcurrentHashMap.newKeySet();
        public volatile Location location;
        public volatile boolean online = true;
        private final org.bukkit.inventory.Inventory crafting = (org.bukkit.inventory.Inventory) Proxy.newProxyInstance(
            Fakes.class.getClassLoader(), new Class<?>[] {org.bukkit.inventory.Inventory.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakeCraftingInventory";
                default -> defaultValue(method.getReturnType());
            });
        private final org.bukkit.inventory.InventoryView view = (org.bukkit.inventory.InventoryView) Proxy.newProxyInstance(
            Fakes.class.getClassLoader(), new Class<?>[] {org.bukkit.inventory.InventoryView.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTopInventory", "getBottomInventory" -> this.crafting;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakeInventoryView";
                default -> defaultValue(method.getReturnType());
            });

        public FakePlayer(String name) {
            this.id = UUID.nameUUIDFromBytes(("fake:" + name).getBytes(StandardCharsets.UTF_8));
            this.name = name;
            this.location = new Location(world("world"), 0.5, 64, 0.5);
            this.player = (Player) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {Player.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String method0 = method.getName();
            switch (method0) {
                case "getUniqueId":
                    return this.id;
                case "getName":
                    return this.name;
                case "isOnline":
                    return this.online;
                case "hasPermission":
                    return args[0] instanceof String node && this.permissions.contains(node);
                case "getLocation":
                    return this.location.clone();
                case "sendActionBar":
                    if (args.length == 1 && args[0] instanceof Component text) {
                        this.actionBar.add(text);
                    }
                    break;
                case "sendMessage":
                    if (args.length == 1 && args[0] instanceof Component text) {
                        this.chat.add(text);
                    }
                    break;
                case "getOpenInventory":
                    // The player's own crafting screen: what a player with nothing open has.
                    return this.view;
                case "teleportAsync":
                    this.calls.add(method0);
                    this.teleports.add((Location) args[0]);
                    return CompletableFuture.completedFuture(true);
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "FakePlayer[" + this.name + "]";
                default:
                    break;
            }
            this.calls.add(method0);
            return defaultValue(method.getReturnType());
        }

        /** Every action bar and chat line as plain text, in order. */
        public List<String> said() {
            List<String> lines = new ArrayList<>();
            for (Component text : this.actionBar) {
                lines.add(TextStyle.plain(text));
            }
            for (Component text : this.chat) {
                lines.add(TextStyle.plain(text));
            }
            return lines;
        }

        public boolean called(String method) {
            return this.calls.contains(method);
        }
    }

    /**
     * A scheduler where the current thread owns everything: entity, global and region tasks run at once, timers are
     * kept (and run with {@link #tick}), async tasks run at once. Retired callbacks never run.
     */
    public static final class ImmediateScheduler implements Scheduler {

        private final List<Runnable> timers = new CopyOnWriteArrayList<>();

        /** Runs every live timer once (a timer cancels itself by removing its runnable). */
        public void tick() {
            for (Runnable timer : List.copyOf(this.timers)) {
                timer.run();
            }
        }

        public int timers() {
            return this.timers.size();
        }

        private Task timer(Runnable task) {
            this.timers.add(task);
            return () -> this.timers.remove(task);
        }

        private static Task now(Runnable task) {
            task.run();
            return () -> { };
        }

        @Override
        public boolean regionized() {
            return false;
        }

        @Override
        public Task global(Runnable task) {
            return now(task);
        }

        @Override
        public Task globalLater(Runnable task, long delayTicks) {
            return now(task);
        }

        @Override
        public Task globalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
            return timer(task);
        }

        @Override
        public Task region(Location location, Runnable task) {
            return now(task);
        }

        @Override
        public Task region(World world, int chunkX, int chunkZ, Runnable task) {
            return now(task);
        }

        @Override
        public Task regionLater(Location location, Runnable task, long delayTicks) {
            return now(task);
        }

        @Override
        public Task regionTimer(Location location, Runnable task, long initialDelayTicks, long periodTicks) {
            return timer(task);
        }

        @Override
        public Task entity(Entity entity, Runnable task, Runnable retired) {
            return now(task);
        }

        @Override
        public Task entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
            return now(task);
        }

        @Override
        public Task entityTimer(Entity entity, Runnable task, Runnable retired, long initialDelayTicks, long periodTicks) {
            return timer(task);
        }

        @Override
        public Task async(Runnable task) {
            return now(task);
        }

        @Override
        public Task asyncLater(Runnable task, Duration delay) {
            return now(task);
        }

        @Override
        public Task asyncTimer(Runnable task, Duration initialDelay, Duration period) {
            return timer(task);
        }

        @Override
        public Executor entityExecutor(Entity entity) {
            return Runnable::run;
        }

        @Override
        public Executor asyncExecutor() {
            return Runnable::run;
        }

        @Override
        public <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> task) {
            return CompletableFuture.completedFuture(task.get());
        }

        @Override
        public boolean owns(Entity entity) {
            return true;
        }

        @Override
        public boolean owns(Location location) {
            return true;
        }

        @Override
        public boolean isGlobalThread() {
            return true;
        }

        @Override
        public boolean isTickThread() {
            return true;
        }

        @Override
        public void cancelAll() {
            this.timers.clear();
        }
    }

    /** The value a proxy method returns when nothing more specific is wanted. */
    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return (byte) 0;
    }
}
