package net.siftvanilla.siftcore.feature.crates;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The opening animation in a chest window: the middle row is a reel of rewards rolling past the pointer, slowing
 * down on the reward that was won, while the glass above and below flashes; then everything turns the colour of the
 * reward's rarity and the reward glows under the pointer.
 * <p>
 * It only shows: the reward was decided and stored before it starts. {@code reveal} runs exactly once, at the end of
 * the roll, or at once when the player clicks the window or closes it; it hands the reward over. {@code finished}
 * runs once the window is gone (closed by the player or after the reveal time). {@code gone} runs instead when the
 * player leaves before the animation ended. Everything runs on the player's thread (an entity timer that follows them
 * across regions and stops when they leave).
 */
final class CrateAnimation extends Menu {

    /** Where the animation is. */
    enum Phase {
        ROLLING,
        SHOWING,
        DONE
    }

    private static final int ROW = 9;
    private static final int POINTER_TOP = 4;
    private static final int POINTER_BOTTOM = 22;

    private final AnimationPlan plan;
    private final List<ItemStack> icons;
    private final ItemStack won;
    private final Material crateGlass;
    private final Material rarityGlass;
    private final Sounds sounds;
    private final CratesSettings.Animation config;
    private final boolean big;
    private final Runnable reveal;
    private final Runnable finished;
    private final Runnable gone;
    private Phase phase = Phase.ROLLING;
    private int step;
    private int wait;
    private int showing;
    private boolean revealed;
    private Task timer = Task.NONE;

    /**
     * @param icons the icons of the rewards that can be won, by the plan's indexes
     * @param won   the won reward's icon, as it shows under the pointer at the end
     * @param big   the win is announced: the big fanfare plays
     */
    CrateAnimation(MenuContext ctx, Player viewer, Component title, AnimationPlan plan, List<ItemStack> icons, ItemStack won,
                   Crate crate, Rarity rarity, Sounds sounds, CratesSettings.Animation config, boolean big, Runnable reveal,
                   Runnable finished, Runnable gone) {
        super(ctx, viewer, title, 3);
        this.plan = plan;
        this.icons = List.copyOf(icons);
        this.won = won;
        this.crateGlass = Panes.nearest(crate.color());
        this.rarityGlass = Panes.of(rarity);
        this.sounds = sounds;
        this.config = config;
        this.big = big;
        this.reveal = reveal;
        this.finished = finished;
        this.gone = gone;
        this.wait = plan.steps() == 0 ? 0 : plan.delays()[0];
    }

    /** Opens the window and starts the roll. Player's thread. */
    void start() {
        open();
        this.timer = this.ctx.scheduler().entityTimer(this.viewer, this::tick, this::left, 1L, 1L);
    }

    Phase phase() {
        return this.phase;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw() {
        if (this.phase == Phase.ROLLING) {
            drawRoll();
        } else {
            drawReveal();
        }
    }

    private void drawRoll() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int slot = 0; slot < ROW; slot++) {
            Material flash = Panes.BRIGHT.get(random.nextInt(Panes.BRIGHT.size()));
            set(slot, glass(slot == POINTER_TOP ? this.crateGlass : flash), this::clicked);
            set(2 * ROW + slot, glass(2 * ROW + slot == POINTER_BOTTOM ? this.crateGlass : flash), this::clicked);
            set(ROW + slot, this.icons.get(this.plan.shown(this.step, slot)), this::clicked);
        }
    }

    private void drawReveal() {
        for (int slot = 0; slot < 3 * ROW; slot++) {
            set(slot, glass(this.rarityGlass), this::clicked);
        }
        set(ROW + AnimationPlan.POINTER, this.won, this::clicked);
    }

    private static ItemStack glass(Material material) {
        ItemStack pane = ItemStack.of(material);
        pane.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return pane;
    }

    // ------------------------------------------------------------------ the timer

    private void tick() {
        switch (this.phase) {
            case ROLLING -> {
                if (--this.wait > 0) {
                    return;
                }
                this.step++;
                if (this.step >= this.plan.steps()) {
                    this.step = this.plan.steps();
                    showReveal();
                    return;
                }
                this.wait = this.plan.delays()[this.step];
                redraw();
                Sound tick = this.config.tick();
                if (tick != null) {
                    float pitch = (float) Math.clamp(tick.pitch() * (0.8 + 0.8 * this.plan.progress(this.step)), 0.5, 2.0);
                    this.sounds.play(this.viewer, Sound.sound(tick.name(), tick.source(), tick.volume(), pitch), Feedback.CLICK);
                }
            }
            case SHOWING -> {
                if (--this.showing <= 0) {
                    end(true);
                }
            }
            case DONE -> this.timer.cancel();
        }
    }

    /** The roll ends (or is skipped): the reward shows in its rarity's colour and is handed over. */
    private void showReveal() {
        if (this.phase != Phase.ROLLING) {
            return;
        }
        this.phase = Phase.SHOWING;
        this.step = this.plan.steps();
        this.showing = (int) Math.max(1, this.config.reveal().toMillis() / 50);
        redraw();
        this.sounds.play(this.viewer, this.big ? this.config.bigReveal() : this.config.revealed(), Feedback.SUCCESS);
        runReveal();
    }

    private void runReveal() {
        if (!this.revealed) {
            this.revealed = true;
            this.reveal.run();
        }
    }

    /** A click in the window: during the roll it skips to the reward, while the reward shows it closes the window. */
    private void clicked(ClickContext click) {
        if (this.phase == Phase.ROLLING) {
            showReveal();
        } else if (this.phase == Phase.SHOWING) {
            end(true);
        }
    }

    /** Ends the animation: the reward was handed over; the window closes (unless it already did) and finished runs. */
    private void end(boolean closeWindow) {
        if (this.phase == Phase.DONE) {
            return;
        }
        runReveal();
        this.phase = Phase.DONE;
        this.timer.cancel();
        if (closeWindow && this.viewer.getOpenInventory().getTopInventory().getHolder(false) == this) {
            this.viewer.closeInventory();
        }
        this.finished.run();
    }

    /** The player closed the window (or something replaced it, or they died): skip to the end. */
    @Override
    protected void closed() {
        end(false);
    }

    /** The player left before the end: the timer is gone with them; what was not handed over waits in the claim box. */
    private void left() {
        if (this.phase != Phase.DONE) {
            this.phase = Phase.DONE;
            this.gone.run();
        }
    }
}
