package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lidded;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * What makes crate blocks look like crates: a floating name above each one (a text display: the crate's name in its
 * colour and how to use it), particles circling it in the crate's colour while a player is near, and the reward
 * spinning up out of the block while someone opens it there.
 * <p>
 * <b>Threads.</b> One global timer runs every few ticks and, for each crate block whose chunk is loaded, hops to the
 * block's region thread; everything that touches an entity or the world happens there. Nothing scans the world:
 * the blocks are the crates feature's own list, and particles are only sent while a player is within range.
 * <p>
 * <b>No duplicates.</b> Every entity is non-persistent (never saved, gone when its chunk unloads or the server stops)
 * and tagged; whenever a chunk's entities load, tagged ones that are not the current ones are removed, and the
 * timer puts a missing name back. The spin is only visual: the reward was stored before it started.
 */
final class CrateDecor implements Listener {

    /** Ticks between two rounds of the timer (holograms kept, particles puffed). */
    static final long PULSE_TICKS = 5L;
    private static final String HOLOGRAM = "hologram";
    private static final String SPIN = "spin";
    private static final long STALE_SPIN_MILLIS = 30_000;
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    /** What a spin above a crate block does, from any thread: the opening animation skips to the reward. */
    interface SpinHandle {
        SpinHandle NONE = () -> {
        };

        void skip();
    }

    private record Shown(TextDisplay display, Component text, Location location) {
    }

    private final Scheduler scheduler;
    private final Setting<CratesSettings> settings;
    private final Supplier<Map<BlockKey, CrateBlocks.Entry>> blocks;
    private final Lang lang;
    private final Logger logger;
    private final NamespacedKey key;
    private final Map<BlockKey, Shown> holograms = new ConcurrentHashMap<>();
    private final Map<BlockKey, Long> spinning = new ConcurrentHashMap<>();
    private final AtomicLong pulses = new AtomicLong();
    private volatile boolean running;
    private volatile Task timer = Task.NONE;

    CrateDecor(Plugin plugin, Scheduler scheduler, Setting<CratesSettings> settings, Supplier<Map<BlockKey, CrateBlocks.Entry>> blocks,
               Lang lang) {
        this.scheduler = scheduler;
        this.settings = settings;
        this.blocks = blocks;
        this.lang = lang;
        this.logger = plugin.getLogger();
        this.key = new NamespacedKey(plugin, "crate_decor");
    }

    void start() {
        this.running = true;
        this.timer = this.scheduler.globalTimer(this::pulse, 20L, PULSE_TICKS);
    }

    /** Stops the timer. The entities are not saved, so they are gone with their chunks or the server. */
    void stop() {
        this.running = false;
        this.timer.cancel();
    }

    /** Crate blocks showing a name right now (for the self-test). */
    int holograms() {
        return this.holograms.size();
    }

    // ------------------------------------------------------------------ the timer (global thread)

    private void pulse() {
        if (!this.running) {
            return;
        }
        long pulse = this.pulses.incrementAndGet();
        Map<BlockKey, CrateBlocks.Entry> current = this.blocks.get();
        for (Map.Entry<BlockKey, CrateBlocks.Entry> entry : current.entrySet()) {
            BlockKey block = entry.getKey();
            String crate = entry.getValue().crate();
            onRegion(block, () -> tend(block, crate, pulse));
        }
        // Names above blocks that stopped being crates.
        for (BlockKey block : this.holograms.keySet()) {
            if (!current.containsKey(block)) {
                onRegion(block, () -> removeHologram(block));
            }
        }
    }

    /** Runs {@code task} on the block's region thread when its chunk is loaded (nothing is loaded for it). */
    private void onRegion(BlockKey block, Runnable task) {
        World world = Bukkit.getWorld(block.world());
        if (world == null || !world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
            return;
        }
        this.scheduler.region(world, block.x() >> 4, block.z() >> 4, () -> {
            if (this.running && world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    this.logger.log(Level.WARNING, "Crate block effects at " + block + " failed", e);
                }
            }
        });
    }

    /** Region thread: keeps the block's name and puffs its particles when a player is near. */
    private void tend(BlockKey block, String crateId, long pulse) {
        CratesSettings settings = this.settings.get();
        Crate crate = settings.crate(crateId);
        if (crate == null) {
            removeHologram(block);
            return;
        }
        CratesSettings.Effects effects = settings.effects();
        World world = Bukkit.getWorld(block.world());
        if (world == null) {
            return;
        }
        if (effects.holograms()) {
            ensureHologram(world, block, crate, effects);
        } else {
            removeHologram(block);
        }
        if (effects.particleRange() > 0 && !this.spinning.containsKey(block)) {
            Location center = new Location(world, block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
            if (!center.getNearbyPlayers(effects.particleRange()).isEmpty()) {
                particles(world, center, crate, pulse);
            }
        }
    }

    // ------------------------------------------------------------------ holograms (region thread)

    /** The text above a crate: its name in its colour (bold), then how to use it. */
    Component hologramText(Crate crate) {
        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(CratesMessages.HOLOGRAM_TITLE, Arg.text("name", crate.name())).color(crate.color())
            .decorate(TextDecoration.BOLD));
        lines.addAll(this.lang.lines(CratesMessages.HOLOGRAM_LINES));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private void ensureHologram(World world, BlockKey block, Crate crate, CratesSettings.Effects effects) {
        Location location = new Location(world, block.x() + 0.5, block.y() + 1.0 + effects.hologramHeight(), block.z() + 0.5);
        Component text = hologramText(crate);
        Shown shown = this.holograms.get(block);
        if (shown != null && shown.display().isValid() && shown.location().equals(location)) {
            if (!shown.text().equals(text)) {
                shown.display().text(text);
                this.holograms.put(block, new Shown(shown.display(), text, location));
            }
            return;
        }
        if (shown != null) {
            remove(shown.display());
        }
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, HOLOGRAM + ":" + block);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0));
            entity.setBrightness(FULL_BRIGHT);
            entity.setViewRange(0.5f);
            entity.text(text);
        });
        if (display.isValid()) {
            this.holograms.put(block, new Shown(display, text, location));
        } else {
            this.holograms.remove(block);
        }
    }

    private void removeHologram(BlockKey block) {
        Shown shown = this.holograms.remove(block);
        if (shown != null) {
            remove(shown.display());
        }
    }

    private void remove(Entity entity) {
        try {
            if (entity.isValid()) {
                entity.remove();
            }
        } catch (RuntimeException e) {
            this.logger.log(Level.FINE, "A crate display could not be removed; it goes with its chunk", e);
        }
    }

    // ------------------------------------------------------------------ particles (region thread)

    /** Two motes circling the block in the crate's colour, rising and falling; the top tiers sparkle too. */
    private static void particles(World world, Location center, Crate crate, long pulse) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(crate.color().value()), 1.0f);
        double turn = pulse * 0.45;
        double rise = 0.35 * Math.sin(pulse * 0.25);
        for (int i = 0; i < 2; i++) {
            double angle = turn + i * Math.PI;
            world.spawnParticle(Particle.DUST, center.getX() + 0.8 * Math.cos(angle), center.getY() + rise,
                center.getZ() + 0.8 * Math.sin(angle), 1, 0, 0, 0, 0, dust);
        }
        if (crate.tier() >= 5 && pulse % 4 == 0) {
            world.spawnParticle(Particle.END_ROD, center.getX(), center.getY() + 0.7, center.getZ(), 1, 0.3, 0.2, 0.3, 0.01);
        }
    }

    // ------------------------------------------------------------------ the spin above a block

    /**
     * The opening's reward spins up out of a crate block, changing with the reel's steps, then shows the reward with a
     * burst in its rarity's colour. Only one spin per block at a time (a second opening there shows only its window).
     * Any thread.
     *
     * @param frames the item shown at each step, then the reward
     */
    SpinHandle spin(BlockKey block, AnimationPlan plan, List<ItemStack> frames, ItemStack won, Rarity rarity, int revealTicks) {
        long now = System.currentTimeMillis();
        boolean[] mine = new boolean[1];
        this.spinning.compute(block, (k, since) -> {
            if (since == null || now - since > STALE_SPIN_MILLIS) {
                mine[0] = true;
                return now;
            }
            return since;
        });
        if (!mine[0]) {
            return SpinHandle.NONE;
        }
        AtomicBoolean skip = new AtomicBoolean();
        World world = Bukkit.getWorld(block.world());
        if (world == null || !world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
            this.spinning.remove(block, now);
            return SpinHandle.NONE;
        }
        this.scheduler.region(world, block.x() >> 4, block.z() >> 4, () -> {
            try {
                startSpin(world, block, plan, frames, won, rarity, revealTicks, skip, now);
            } catch (RuntimeException e) {
                this.spinning.remove(block, now);
                this.logger.log(Level.WARNING, "The crate spin at " + block + " failed", e);
            }
        });
        return () -> skip.set(true);
    }

    private void startSpin(World world, BlockKey block, AnimationPlan plan, List<ItemStack> frames, ItemStack won, Rarity rarity,
                           int revealTicks, AtomicBoolean skip, long started) {
        if (!this.running || !world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
            this.spinning.remove(block, started);
            return;
        }
        Location at = new Location(world, block.x() + 0.5, block.y() + 1.15, block.z() + 0.5);
        ItemDisplay display = world.spawn(at, ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, SPIN);
            entity.setItemStack(frames.isEmpty() ? won : frames.getFirst());
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setBrightness(FULL_BRIGHT);
            entity.setTransformation(transformation(0f, 0f, 0.6f));
        });
        if (!display.isValid()) {
            this.spinning.remove(block, started);
            return;
        }
        lid(world, block, true);
        int[] state = {0, plan.steps() == 0 ? 0 : plan.delays()[0], 0};
        Task[] task = new Task[1];
        task[0] = this.scheduler.regionTimer(at, () -> {
            try {
                if (!this.running || !display.isValid()) {
                    finishSpin(world, block, display, task[0], started);
                    return;
                }
                if (state[2] > 0) {
                    if (--state[2] <= 0) {
                        finishSpin(world, block, display, task[0], started);
                    }
                    return;
                }
                if (skip.get()) {
                    state[0] = plan.steps();
                } else if (--state[1] > 0) {
                    return;
                } else {
                    state[0]++;
                }
                if (state[0] >= plan.steps()) {
                    reveal(world, display, won, rarity);
                    state[2] = Math.max(30, revealTicks);
                    return;
                }
                int delay = plan.delays()[state[0]];
                state[1] = delay;
                display.setItemStack(frames.get(Math.min(state[0], frames.size() - 1)));
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(delay);
                float rise = (float) (0.35 * plan.progress(state[0]));
                display.setTransformation(transformation(rise, (float) (state[0] * Math.PI / 2), 0.6f));
            } catch (RuntimeException e) {
                finishSpin(world, block, display, task[0], started);
                this.logger.log(Level.WARNING, "The crate spin at " + block + " failed", e);
            }
        }, 1L, 1L);
    }

    /** The reward shows: bigger, glowing in its rarity's colour, with a burst of that colour. */
    private static void reveal(World world, ItemDisplay display, ItemStack won, Rarity rarity) {
        Color color = Color.fromRGB(rarity.color().value());
        display.setItemStack(won);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(6);
        display.setTransformation(transformation(0.45f, 0f, 0.85f));
        display.setGlowing(true);
        display.setGlowColorOverride(color);
        Location at = display.getLocation().add(0, 0.45, 0);
        world.spawnParticle(Particle.DUST, at, 40, 0.45, 0.45, 0.45, 0, new Particle.DustOptions(color, 1.3f));
        if (rarity.announce()) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.3, 0.3, 0.3, 0.35);
        } else {
            world.spawnParticle(Particle.HAPPY_VILLAGER, at, 12, 0.4, 0.3, 0.4, 0);
        }
    }

    private void finishSpin(World world, BlockKey block, ItemDisplay display, Task task, long started) {
        if (task != null) {
            task.cancel();
        }
        remove(display);
        lid(world, block, false);
        this.spinning.remove(block, started);
    }

    private static Transformation transformation(float rise, float angle, float scale) {
        return new Transformation(new Vector3f(0f, rise, 0f), new AxisAngle4f(angle, 0f, 1f, 0f), new Vector3f(scale, scale, scale),
            new AxisAngle4f());
    }

    /** Opens or closes a chest-like crate block's lid (chests, ender chests, barrels, shulker boxes). Region thread. */
    private void lid(World world, BlockKey block, boolean open) {
        try {
            BlockState state = world.getBlockAt(block.x(), block.y(), block.z()).getState(false);
            if (state instanceof Lidded lidded) {
                if (open) {
                    lidded.open();
                } else {
                    lidded.close();
                }
            }
        } catch (RuntimeException e) {
            this.logger.log(Level.FINE, "The lid of the crate block " + block + " could not move", e);
        }
    }

    // ------------------------------------------------------------------ leftovers

    /**
     * A chunk's entities are loaded (on its region thread): tagged crate displays that are not the current ones
     * (from a crash, an older version or a reload) are removed a tick later, once the chunk is set up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!this.running) {
            return;
        }
        List<Entity> strays = null;
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof TextDisplay || entity instanceof ItemDisplay)) {
                continue;
            }
            String tag = entity.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
            if (tag != null && !current(entity)) {
                if (strays == null) {
                    strays = new ArrayList<>();
                }
                strays.add(entity);
            }
        }
        if (strays == null) {
            return;
        }
        List<Entity> leftovers = strays;
        Chunk chunk = event.getChunk();
        this.scheduler.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
            for (Entity leftover : leftovers) {
                remove(leftover);
            }
        });
    }

    /** Whether an entity is one of the holograms shown now (spins never outlive their chunk). */
    private boolean current(Entity entity) {
        for (Shown shown : this.holograms.values()) {
            if (shown.display().getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }
}
