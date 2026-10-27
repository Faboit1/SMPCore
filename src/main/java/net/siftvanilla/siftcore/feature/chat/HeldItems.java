package net.siftvanilla.siftcore.feature.chat;

import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code [item]} replacement: the name of the item a player holds (main hand, else off hand), in brackets, in
 * the chat colour, showing the item (enchantments, lore, durability, contents) on hover through {@link ItemPreview}.
 * Names the player chose (anvil names, book titles) pass the link check and the word filter (in replace mode) unless
 * the player may skip them.
 * <p>
 * Inventories belong to the player's region thread. Public chat arrives on an async chat thread, so the item is
 * read by a short task on the player's thread while the chat thread waits for it (chat threads are a pooled
 * executor, waiting there holds up nothing else). If the player's region is too slow to answer, the tag stays as
 * typed instead of delaying the message.
 */
final class HeldItems {

    /** How long the chat thread waits for the player's thread. */
    static final long TIMEOUT_MILLIS = 400;

    private final Scheduler scheduler;
    private final Lang lang;
    private final Setting<ChatSettings> settings;

    HeldItems(Scheduler scheduler, Lang lang, Setting<ChatSettings> settings) {
        this.scheduler = scheduler;
        this.lang = lang;
        this.settings = settings;
    }

    /** The bracketed item name with its hover, or null when the hands are empty or the item can't be read now. */
    Component held(Player player) {
        if (this.scheduler.owns(player)) {
            return describe(player);
        }
        try {
            return this.scheduler.supplyOnEntity(player, () -> describe(player)).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Runs on the player's thread. */
    private Component describe(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            item = player.getInventory().getItemInOffHand();
        }
        if (item.isEmpty()) {
            return null;
        }
        ItemStack preview = ItemPreview.of(item, names(player));
        Component name = ItemTag.monochrome(preview.effectiveName());
        Component text = preview.getAmount() > 1
            ? this.lang.get(ChatMessages.ITEM_STACK, Arg.component("item", name), Arg.number("amount", preview.getAmount()))
            : this.lang.get(ChatMessages.ITEM, Arg.component("item", name));
        return text.hoverEvent(preview.asHoverEvent());
    }

    /** The link check and word filter for names the player chose, in replace mode (a name is cleaned, never refused). */
    private UnaryOperator<String> names(Player player) {
        ChatSettings settings = this.settings.get();
        boolean links = !player.hasPermission(ChatNodes.LINKS);
        boolean words = !player.hasPermission(ChatNodes.FILTER_BYPASS);
        return text -> {
            String result = text;
            if (links) {
                result = settings.links().apply(result, ChatFilter.Action.REPLACE, settings.filterReplacement()).text();
            }
            if (words) {
                result = settings.filter().apply(result, ChatFilter.Action.REPLACE, settings.filterReplacement()).text();
            }
            return result;
        };
    }
}
