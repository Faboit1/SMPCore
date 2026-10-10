package net.siftvanilla.siftcore.feature.crates;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Plays the opening animation ({@link CrateOpener.Animator}): the chest window with the rolling reel
 * ({@link CrateAnimation}) and, for an opening at a crate block, the reward spinning up out of the block
 * ({@link CrateDecor#spin}). Both follow one {@link AnimationPlan}; the reward was stored before either starts.
 */
final class CrateShows implements CrateOpener.Animator {

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final RewardItems items;
    private final CrateText text;
    private final CrateDecor decor;

    CrateShows(Services services, Setting<CratesSettings> settings, RewardItems items, CrateText text, CrateDecor decor) {
        this.services = services;
        this.settings = settings;
        this.items = items;
        this.text = text;
        this.decor = decor;
    }

    @Override
    public void play(Player player, Crate crate, Reward reward, Rarity rarity, BlockKey block, Runnable reveal, Runnable finished,
                     Runnable gone) {
        // Built as the player reads: a money reward on the reel is in their money format.
        this.services.lang().viewing(player, () -> start(player, crate, reward, rarity, block, reveal, finished, gone));
    }

    private void start(Player player, Crate crate, Reward reward, Rarity rarity, BlockKey block, Runnable reveal, Runnable finished,
                       Runnable gone) {
        CratesSettings.Animation config = this.settings.get().effects().animation();
        List<Reward> rewards = new ArrayList<>(this.items.available(crate));
        int won = rewards.indexOf(reward);
        if (won < 0) {
            rewards.add(reward);
            won = rewards.size() - 1;
        }
        double[] weights = new double[rewards.size()];
        List<ItemStack> icons = new ArrayList<>(rewards.size());
        for (int i = 0; i < rewards.size(); i++) {
            weights[i] = rewards.get(i).weight();
            icons.add(reelIcon(rewards.get(i)));
        }
        int ticks = (int) Math.max(20, config.length().toMillis() / 50);
        AnimationPlan plan = AnimationPlan.of(ticks, weights, won, ThreadLocalRandom.current());
        ItemStack wonIcon = wonIcon(reward, rarity);
        int revealTicks = (int) (config.reveal().toMillis() / 50);
        CrateDecor.SpinHandle spin = CrateDecor.SpinHandle.NONE;
        if (block != null) {
            List<ItemStack> frames = new ArrayList<>(plan.steps() + 1);
            for (int step = 0; step <= plan.steps(); step++) {
                frames.add(icons.get(plan.pointed(step)));
            }
            spin = this.decor.spin(block, plan, frames, this.items.build(reward).map(stack -> stack.asQuantity(1)).orElse(wonIcon),
                rarity, revealTicks);
        }
        CrateDecor.SpinHandle skipSpin = spin;
        Component title = Component.text(this.services.lang().plain(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())));
        new CrateAnimation(this.services.menus(), player, title, plan, icons, wonIcon, crate, rarity,
            this.services.messenger().sounds(), config, rarity.announce(), () -> {
                skipSpin.skip();
                reveal.run();
            }, finished, gone).start();
    }

    /** A reward on the reel: its icon named after it, in its rarity's colour. */
    private ItemStack reelIcon(Reward reward) {
        ItemStack icon = this.items.icon(reward);
        icon.setData(DataComponentTypes.CUSTOM_NAME, this.text.reward(reward).decorationIfAbsent(TextDecoration.ITALIC,
            TextDecoration.State.FALSE));
        icon.unsetData(DataComponentTypes.LORE);
        Items.hideDetails(icon);
        return icon;
    }

    /** The reward under the pointer at the end: glowing, with its rarity below its name. */
    private ItemStack wonIcon(Reward reward, Rarity rarity) {
        Lang lang = this.services.lang();
        ItemStack icon = reelIcon(reward);
        icon.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        icon.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(lang.get(CratesMessages.PREVIEW_RARITY,
            Arg.component("rarity", CrateText.rarity(rarity))).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))));
        return icon;
    }
}
