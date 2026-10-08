package net.siftvanilla.siftcore.feature.displays;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The display entities in the world, kept in step with the current set of displays.
 * <p>
 * <b>Threads.</b> Entities are created, changed and removed only on the region thread that owns the display's
 * position ({@code scheduler.region}). Chunk events arrive on that same thread. Text is rendered off-thread by one
 * async timer per refresh interval (placeholders are thread-safe caches); a region hop happens only for a display
 * whose text changed and that is currently in the world.
 * <p>
 * <b>No duplicates.</b> Every entity is non-persistent ({@code Entity#setPersistent(false)}), so it is never written
 * to a chunk file and vanishes when its chunk unloads or the server stops. Each one carries the display name in its
 * persistent data, and the entities this class spawned are tracked by UUID. Whenever a chunk's entities load, and
 * right before a display is spawned into its chunk, any tagged entity there that is not the tracked one of a current
 * display is removed (leftovers from crashes, old versions or a plugin disable without shutdown), and the displays
 * in that chunk are spawned if missing. A display that moves gets a new generation; the entities of an older
 * generation are removed on their own region thread.
 */
final class DisplayEntities implements Listener {

    /** What a display is doing right now, for /displays list. */
    enum State {
        SHOWN,
        /** Its chunk is loaded but it has no live entity yet (spawning, removed by something else, or refused). */
        MISSING,
        NOT_LOADED,
        NOT_PLACED,
        NO_TEMPLATE,
        NO_WORLD
    }

    /** A chunk of a world, for the chunk to displays index. */
    private record ChunkRef(String world, int x, int z) {
    }

    /**
     * The entities of one display. The entity fields are only touched on the region thread that owns
     * {@link #location}; the volatile fields are read from other threads.
     */
    private static final class Spawned {
        final String id;
        final int generation;
        final Location location;
        final TextDisplay text;
        final UUID textId;
        volatile Interaction click;
        volatile UUID clickId;
        volatile Component applied;
        volatile boolean active;

        Spawned(String id, int generation, Location location, TextDisplay text) {
            this.id = id;
            this.generation = generation;
            this.location = location;
            this.text = text;
            this.textId = text.getUniqueId();
        }
    }

    private static final String CLICK_COOLDOWN = "displays:click";
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);
    private static final Color NO_BACKGROUND = Color.fromARGB(0);
    private static final long VERIFY_RETRY_TICKS = 20L;
    private static final long VERIFY_TIMEOUT_SECONDS = 15L;

    private final Scheduler scheduler;
    private final TextStyle style;
    private final Placeholders placeholders;
    private final Messenger messenger;
    private final Cooldowns cooldowns;
    private final Setting<DisplaysSettings> settings;
    private final Logger logger;
    private final NamespacedKey key;
    private final Object lock = new Object();
    /** Last generation handed out per display name; guarded by {@link #lock}. */
    private final Map<String, Integer> generations = new HashMap<>();
    /** One async timer per refresh interval; guarded by {@link #lock}. */
    private final Map<Duration, Task> timers = new HashMap<>();
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    private final ChangeTracker<String, Component> rendered = new ChangeTracker<>();
    private final Map<String, Spawned> spawned = new ConcurrentHashMap<>();
    private volatile Map<String, DisplayDef> defs = Map.of();
    private volatile Map<ChunkRef, List<String>> index = Map.of();
    private volatile boolean running;

    DisplayEntities(Scheduler scheduler, TextStyle style, Placeholders placeholders, Messenger messenger, Cooldowns cooldowns,
                    Setting<DisplaysSettings> settings, Logger logger, NamespacedKey key) {
        this.scheduler = scheduler;
        this.style = style;
        this.placeholders = placeholders;
        this.messenger = messenger;
        this.cooldowns = cooldowns;
        this.settings = settings;
        this.logger = logger;
        this.key = key;
    }

    /** The current displays by name, sorted. */
    Map<String, DisplayDef> displays() {
        return this.defs;
    }

    // ------------------------------------------------------------------ definitions

    /** Starts with the first set of displays (spawned once their chunks are loaded). */
    void start(Map<String, DisplayDef> merged) {
        this.running = true;
        apply(merged);
    }

    /**
     * Switches to a new set of displays: removed and moved displays lose their entities, new and changed ones are
     * rendered and spawned or updated in place, and the refresh timers follow the intervals in use. Any thread.
     */
    void apply(Map<String, DisplayDef> merged) {
        synchronized (this.lock) {
            if (!this.running) {
                return;
            }
            Map<String, DisplayDef> previous = this.defs;
            Map<String, DisplayDef> next = new LinkedHashMap<>();
            for (DisplayDef def : merged.values()) {
                DisplayDef old = previous.get(def.id());
                boolean samePlace = old != null && def.position() != null && def.position().samePlace(old.position());
                int generation = samePlace ? old.generation() : this.generations.merge(def.id(), 1, Integer::sum);
                next.put(def.id(), def.withGeneration(generation));
            }
            this.defs = Collections.unmodifiableMap(next);
            this.index = index(next);
            for (DisplayDef old : previous.values()) {
                DisplayDef now = next.get(old.id());
                if (now == null || now.generation() != old.generation() || !now.showable()) {
                    detach(old.id());
                }
                if (now == null) {
                    this.rendered.forget(old.id());
                    this.refused.remove(old.id());
                }
            }
            for (DisplayDef def : next.values()) {
                if (def.equals(previous.get(def.id())) || !def.showable()) {
                    continue;
                }
                render(def);
                schedule(def);
            }
            restartTimers();
        }
    }

    private static Map<ChunkRef, List<String>> index(Map<String, DisplayDef> defs) {
        Map<ChunkRef, List<String>> index = new HashMap<>();
        for (DisplayDef def : defs.values()) {
            if (def.showable()) {
                DisplayPosition p = def.position();
                index.computeIfAbsent(new ChunkRef(p.world(), p.chunkX(), p.chunkZ()), k -> new ArrayList<>()).add(def.id());
            }
        }
        Map<ChunkRef, List<String>> frozen = new HashMap<>();
        index.forEach((chunk, ids) -> frozen.put(chunk, List.copyOf(ids)));
        return Map.copyOf(frozen);
    }

    private void restartTimers() {
        Set<Duration> wanted = new HashSet<>();
        for (DisplayDef def : this.defs.values()) {
            if (def.showable()) {
                wanted.add(def.options().refresh());
            }
        }
        this.timers.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().cancel();
            return true;
        });
        for (Duration interval : wanted) {
            this.timers.computeIfAbsent(interval, i -> this.scheduler.asyncTimer(() -> refresh(i), i, i));
        }
    }

    // ------------------------------------------------------------------ text

    /** Renders a display's text into the tracker; true when it differs from the last render. */
    private boolean render(DisplayDef def) {
        Component text;
        try {
            text = def.template().render(this.style, this::value);
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "The text of display " + def.id() + " could not be built; it keeps its old text", e);
            return false;
        }
        return this.rendered.update(def.id(), text);
    }

    private String value(String placeholder) {
        return this.placeholders.resolve(null, placeholder);
    }

    /**
     * One refresh interval's timer: re-render, and hop to the region only where the text changed or where a live
     * display lost an entity to something else (a command or another plugin removed it), so it comes back.
     */
    private void refresh(Duration interval) {
        if (!this.running) {
            return;
        }
        for (DisplayDef def : this.defs.values()) {
            if (def.showable() && def.options().refresh().equals(interval)) {
                push(def);
                heal(def);
            }
        }
    }

    /** Respawns the entities of a display whose chunk is loaded but whose entity was removed by something else. */
    private void heal(DisplayDef def) {
        Spawned current = this.spawned.get(def.id());
        if (current == null || !current.active || current.generation != def.generation()) {
            return;
        }
        Interaction click = current.click;
        boolean clickLost = def.clickCommand() != null && (click == null || !click.isValid());
        if (!current.text.isValid() || clickLost) {
            this.scheduler.region(current.location, () -> ensure(def.id(), def.generation()));
        }
    }

    /** Re-renders every display now, sends changed text and brings back lost entities. Returns how many were checked. */
    int refreshAll() {
        int count = 0;
        for (DisplayDef def : this.defs.values()) {
            if (def.showable()) {
                push(def);
                heal(def);
                count++;
            }
        }
        return count;
    }

    private void push(DisplayDef def) {
        if (!render(def)) {
            return;
        }
        Spawned current = this.spawned.get(def.id());
        if (current == null || !current.active || current.generation != def.generation()) {
            return;
        }
        this.scheduler.region(current.location, () -> applyText(current));
    }

    /** Region thread of the display: sets the newest text if it is still the live entity. */
    private void applyText(Spawned expected) {
        if (this.spawned.get(expected.id) != expected || !expected.active || !expected.text.isValid()) {
            return;
        }
        DisplayDef def = this.defs.get(expected.id);
        if (def == null || def.generation() != expected.generation) {
            return;
        }
        try {
            showText(expected, def);
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "Display " + expected.id + " could not be updated", e);
        }
    }

    private void showText(Spawned spawned, DisplayDef def) {
        Component text = this.rendered.get(spawned.id);
        if (text != null && !text.equals(spawned.applied)) {
            spawned.text.text(text);
            spawned.applied = text;
        }
        syncClick(spawned, def);
    }

    // ------------------------------------------------------------------ entities (region threads)

    private static Location location(DisplayPosition position) {
        World world = Bukkit.getWorld(position.world());
        return world == null ? null : new Location(world, position.x(), position.y(), position.z(), position.yaw(), 0f);
    }

    private void schedule(DisplayDef def) {
        Location location = location(def.position());
        if (location != null) {
            this.scheduler.region(location, () -> ensure(def.id(), def.generation()));
        }
    }

    /**
     * Region thread of the display's position: spawns the display if its chunk is loaded and it is missing, or
     * brings the existing entities up to date. Safe to call any number of times.
     */
    private void ensure(String id, int generation) {
        DisplayDef def = this.defs.get(id);
        if (!this.running || def == null || def.generation() != generation || !def.showable()) {
            return;
        }
        Location location = location(def.position());
        if (location == null || !location.getWorld().isChunkLoaded(def.position().chunkX(), def.position().chunkZ())) {
            return;
        }
        Spawned current = this.spawned.get(id);
        if (current != null) {
            if (current.generation > generation) {
                return;
            }
            if (current.generation == generation && current.text.isValid()) {
                try {
                    current.active = true;
                    style(current.text, def);
                    showText(current, def);
                } catch (RuntimeException e) {
                    this.logger.log(Level.WARNING, "Display " + id + " could not be updated", e);
                }
                return;
            }
            if (this.spawned.remove(id, current)) {
                discard(current);
            }
        }
        removeLeftovers(location.getWorld(), def.position().chunkX(), def.position().chunkZ());
        Spawned fresh = spawn(location, def);
        if (fresh == null) {
            return;
        }
        Spawned[] displaced = new Spawned[1];
        Spawned winner = this.spawned.compute(id, (name, existing) -> {
            if (existing != null && existing.generation >= fresh.generation && existing.text.isValid()) {
                return existing;
            }
            displaced[0] = existing;
            return fresh;
        });
        if (winner != fresh) {
            removeEntities(fresh);
            return;
        }
        if (displaced[0] != null) {
            discard(displaced[0]);
        }
        DisplayDef now = this.defs.get(id);
        if ((now == null || now.generation() != generation || !now.showable()) && this.spawned.remove(id, fresh)) {
            removeEntities(fresh);
        }
    }

    private Spawned spawn(Location location, DisplayDef def) {
        Component text = this.rendered.get(def.id());
        if (text == null) {
            render(def);
            text = this.rendered.get(def.id());
        }
        if (text == null) {
            return null;
        }
        Component shown = text;
        TextDisplay display;
        try {
            display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, def.id());
                style(entity, def);
                entity.text(shown);
            });
        } catch (RuntimeException e) {
            refuse(def, "could not be spawned: " + e.getMessage());
            return null;
        }
        if (!display.isValid()) {
            refuse(def, "was not spawned because another plugin cancelled it");
            return null;
        }
        this.refused.remove(def.id());
        Spawned result = new Spawned(def.id(), def.generation(), location, display);
        result.applied = shown;
        result.active = true;
        try {
            syncClick(result, def);
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "The click box of display " + def.id() + " could not be spawned", e);
        }
        return result;
    }

    private void refuse(DisplayDef def, String why) {
        if (this.refused.add(def.id())) {
            DisplayPosition p = def.position();
            this.logger.warning("Display " + def.id() + " at " + p.world() + " " + DisplayPosition.format(p.x()) + " "
                + DisplayPosition.format(p.y()) + " " + DisplayPosition.format(p.z()) + " " + why);
        }
    }

    /** Applies looks and facing. Setters only send packets when a value actually changes. */
    private static void style(TextDisplay entity, DisplayDef def) {
        DisplayOptions options = def.options();
        entity.setBillboard(options.billboard());
        entity.setAlignment(options.alignment());
        entity.setLineWidth(options.lineWidth());
        entity.setSeeThrough(options.seeThrough());
        entity.setShadowed(options.textShadow());
        switch (options.background().mode()) {
            case NONE -> {
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(NO_BACKGROUND);
            }
            case DEFAULT -> entity.setDefaultBackground(true);
            case COLOR -> {
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(options.background().argb()));
            }
        }
        entity.setViewRange(options.viewRangeMultiplier());
        entity.setBrightness(options.fullBright() ? FULL_BRIGHT : null);
        float scale = options.scale();
        entity.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        if (Float.compare(entity.getYaw(), def.position().yaw()) != 0) {
            entity.setRotation(def.position().yaw(), 0f);
        }
    }

    /** Puts, resizes or removes the click box so it matches the display's text and template. */
    private void syncClick(Spawned spawned, DisplayDef def) {
        Interaction click = spawned.click;
        if (def.clickCommand() == null) {
            if (click != null) {
                remove(click);
                spawned.click = null;
                spawned.clickId = null;
            }
            return;
        }
        Component text = spawned.applied == null ? Component.empty() : spawned.applied;
        TextMetrics.Box box = TextMetrics.clickBox(text, def.options().lineWidth(), def.options().scale());
        if (click != null && click.isValid()) {
            if (Math.abs(click.getInteractionWidth() - box.width()) > 0.01f || Math.abs(click.getInteractionHeight() - box.height()) > 0.01f) {
                click.setInteractionWidth(box.width());
                click.setInteractionHeight(box.height());
            }
            return;
        }
        Interaction created = spawned.location.getWorld().spawn(spawned.location, Interaction.class, entity -> {
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, def.id());
            entity.setInteractionWidth(box.width());
            entity.setInteractionHeight(box.height());
            entity.setResponsive(true);
        });
        if (created.isValid()) {
            spawned.click = created;
            spawned.clickId = created.getUniqueId();
        }
    }

    /** Stops tracking a display's entities and removes them on their own region thread. */
    private void detach(String id) {
        Spawned current = this.spawned.remove(id);
        if (current != null) {
            discard(current);
        }
    }

    private void discard(Spawned spawned) {
        spawned.active = false;
        if (!spawned.text.isValid() && (spawned.click == null || !spawned.click.isValid())) {
            return;
        }
        try {
            if (this.scheduler.owns(spawned.location)) {
                removeEntities(spawned);
            } else {
                this.scheduler.region(spawned.location, () -> removeEntities(spawned));
            }
        } catch (RuntimeException e) {
            // Only when its world was unloaded meanwhile, which took the entities with it.
            this.logger.log(Level.FINE, "Display " + spawned.id + " could not be cleaned up in its world", e);
        }
    }

    private void removeEntities(Spawned spawned) {
        spawned.active = false;
        remove(spawned.text);
        remove(spawned.click);
        spawned.click = null;
        spawned.clickId = null;
    }

    private void remove(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            if (entity.isValid()) {
                entity.remove();
            }
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "A display entity could not be removed; it vanishes when its chunk unloads", e);
        }
    }

    private static boolean ours(Entity entity) {
        return entity instanceof TextDisplay || entity instanceof Interaction;
    }

    /**
     * Region thread of a loaded chunk, right before a display there is spawned: removes tagged entities in that chunk
     * that are not the tracked ones of a current display. One chunk, only when spawning, so it never becomes a scan.
     */
    private void removeLeftovers(World world, int chunkX, int chunkZ) {
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (!ours(entity)) {
                continue;
            }
            String id = entity.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
            if (id != null && tracked(id, entity.getUniqueId()) == null) {
                remove(entity);
            }
        }
    }

    /** The tracked entities of a current display if {@code uuid} is one of them, otherwise null. */
    private Spawned tracked(String id, UUID uuid) {
        Spawned current = this.spawned.get(id);
        if (current == null) {
            return null;
        }
        DisplayDef def = this.defs.get(id);
        if (def == null || def.generation() != current.generation) {
            return null;
        }
        return uuid.equals(current.textId) || uuid.equals(current.clickId) ? current : null;
    }

    // ------------------------------------------------------------------ events

    /**
     * A chunk's entities are loaded (fired right after its chunk load, on the chunk's region thread): leftovers
     * are removed and the displays of this chunk spawned or refreshed, one tick later so nothing changes while the
     * chunk is still being set up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!this.running) {
            return;
        }
        List<Entity> strays = null;
        for (Entity entity : event.getEntities()) {
            if (!ours(entity)) {
                continue;
            }
            String id = entity.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
            if (id != null && tracked(id, entity.getUniqueId()) == null) {
                if (strays == null) {
                    strays = new ArrayList<>();
                }
                strays.add(entity);
            }
        }
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        List<String> here = this.index.get(new ChunkRef(world.getName(), chunk.getX(), chunk.getZ()));
        if (strays == null && here == null) {
            return;
        }
        List<Entity> leftovers = strays == null ? List.of() : strays;
        List<String> ids = here == null ? List.of() : here;
        this.scheduler.region(world, chunk.getX(), chunk.getZ(), () -> {
            for (Entity leftover : leftovers) {
                remove(leftover);
            }
            for (String id : ids) {
                DisplayDef def = this.defs.get(id);
                if (def != null) {
                    ensure(id, def.generation());
                }
            }
        });
    }

    /** A chunk's entities are about to be hidden or unloaded: its displays stop receiving text until it is back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        if (this.spawned.isEmpty()) {
            return;
        }
        Chunk chunk = event.getChunk();
        List<String> here = this.index.get(new ChunkRef(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (here == null) {
            return;
        }
        for (String id : here) {
            Spawned current = this.spawned.get(id);
            if (current != null) {
                current.active = false;
            }
        }
    }

    /** Right-clicking a leaderboard's click box runs its command for the player (player's region thread). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction clicked)) {
            return;
        }
        String id = clicked.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        DisplayDef def = this.defs.get(id);
        if (def == null || def.clickCommand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!this.cooldowns.tryUse(player.getUniqueId(), CLICK_COOLDOWN, this.settings.get().clickCooldown()).isZero()) {
            return;
        }
        boolean ran;
        try {
            ran = player.performCommand(def.clickCommand());
        } catch (RuntimeException e) {
            this.logger.log(Level.FINE, "/" + def.clickCommand() + " from display " + id + " failed for " + player.getName(), e);
            ran = false;
        }
        if (!ran) {
            this.messenger.send(player, DisplaysMessages.CLICK_UNAVAILABLE);
        }
    }

    // ------------------------------------------------------------------ status, self-test, shutdown

    State state(DisplayDef def) {
        if (def.position() == null) {
            return State.NOT_PLACED;
        }
        if (def.template() == null) {
            return State.NO_TEMPLATE;
        }
        World world = Bukkit.getWorld(def.position().world());
        if (world == null) {
            return State.NO_WORLD;
        }
        Spawned current = this.spawned.get(def.id());
        if (current != null && current.active && current.generation == def.generation() && current.text.isValid()) {
            return State.SHOWN;
        }
        return world.isChunkLoaded(def.position().chunkX(), def.position().chunkZ()) ? State.MISSING : State.NOT_LOADED;
    }

    /**
     * Counts the tagged entities in the chunk of every display whose chunk is loaded, on that chunk's thread:
     * each must have exactly one text display, one click box when it has a command, and no leftovers. A mismatch is
     * counted again a second later (a display spawns one tick after its chunk loads). Completes on an async thread
     * with null when everything matches.
     */
    CompletableFuture<String> verify() {
        List<CompletableFuture<String>> checks = new ArrayList<>();
        for (DisplayDef def : this.defs.values()) {
            if (!def.showable()) {
                continue;
            }
            Location location = location(def.position());
            if (location == null || !location.getWorld().isChunkLoaded(def.position().chunkX(), def.position().chunkZ())) {
                continue;
            }
            checks.add(countOn(def, location, 0L)
                .thenCompose(problem -> problem == null ? CompletableFuture.completedFuture(null) : countOn(def, location, VERIFY_RETRY_TICKS)));
        }
        if (checks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture<?>[0]))
            .orTimeout(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .handleAsync((ignored, error) -> {
                if (error != null) {
                    return "the region threads did not answer within " + VERIFY_TIMEOUT_SECONDS + "s";
                }
                List<String> problems = checks.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
                return problems.isEmpty() ? null : String.join("; ", problems);
            }, this.scheduler.asyncExecutor());
    }

    private CompletableFuture<String> countOn(DisplayDef def, Location location, long delayTicks) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Runnable count = () -> {
            try {
                result.complete(count(def, location));
            } catch (RuntimeException e) {
                result.complete(def.id() + " could not be checked: " + e);
            }
        };
        if (delayTicks <= 0) {
            this.scheduler.region(location, count);
        } else {
            this.scheduler.regionLater(location, count, delayTicks);
        }
        return result;
    }

    private String count(DisplayDef def, Location location) {
        World world = location.getWorld();
        int chunkX = def.position().chunkX();
        int chunkZ = def.position().chunkZ();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        int texts = 0;
        int clicks = 0;
        int leftovers = 0;
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (!ours(entity)) {
                continue;
            }
            String id = entity.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
            if (id == null) {
                continue;
            }
            if (tracked(id, entity.getUniqueId()) == null) {
                leftovers++;
            } else if (id.equals(def.id())) {
                if (entity instanceof TextDisplay) {
                    texts++;
                } else {
                    clicks++;
                }
            }
        }
        int expectedClicks = def.clickCommand() == null ? 0 : 1;
        if (texts == 1 && clicks == expectedClicks && leftovers == 0) {
            return null;
        }
        return def.id() + " has " + texts + " text display(s) and " + clicks + " click box(es), expected 1 and " + expectedClicks
            + (leftovers > 0 ? ", plus " + leftovers + " leftover display entities in its chunk" : "");
    }

    /**
     * Stops everything. When the server keeps running (the plugin was disabled on its own), the entities this thread
     * owns are removed now. When the server is stopping they are left alone on purpose: Canvas runs {@code onDisable}
     * on the region shutdown thread, which passes the ownership check but has no region world data, so
     * {@code Entity#remove} throws half way through (verified). They are non-persistent, so they are never saved and
     * end with the process; anything left by a disable without shutdown is cleared when its chunk loads again or
     * when the display is spawned next (see {@link #removeLeftovers}).
     */
    void shutdown() {
        synchronized (this.lock) {
            this.running = false;
            for (Task timer : this.timers.values()) {
                timer.cancel();
            }
            this.timers.clear();
        }
        int left = 0;
        if (!Bukkit.isStopping()) {
            for (Spawned current : this.spawned.values()) {
                left += removeIfOwned(current.text) + removeIfOwned(current.click);
            }
        }
        this.spawned.clear();
        this.rendered.clear();
        if (left > 0) {
            this.logger.info(left + " display entities were left in place (this thread may not touch them); they are not saved "
                + "and are cleared when their chunks load again.");
        }
    }

    /** Removes the entity when this thread may touch it; returns 1 when a live entity had to be left alone. */
    private int removeIfOwned(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return 0;
        }
        if (this.scheduler.owns(entity)) {
            remove(entity);
            return 0;
        }
        return 1;
    }
}
