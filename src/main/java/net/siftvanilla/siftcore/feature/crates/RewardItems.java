package net.siftvanilla.siftcore.feature.crates;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.text.Palette;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

/**
 * Turns rewards into items: the item a reward gives (built from the config, or a spawner item made by the spawners
 * feature) and the icon that shows it in the preview. A reward item's own name is in its rarity's colour (the
 * Celestial blade is cyan) and its lore gray, with italics off.
 */
final class RewardItems {

    private final SpawnerItems spawners;
    private final java.util.function.Supplier<Palette> palette;
    private final Function<String, TextColor> rarityColor;

    /** @param rarityColor the colour of a rarity id (a named reward item's name is in it) */
    RewardItems(SpawnerItems spawners, java.util.function.Supplier<Palette> palette, Function<String, TextColor> rarityColor) {
        this.spawners = spawners;
        this.palette = palette;
        this.rarityColor = rarityColor;
    }

    RewardItems(SpawnerItems spawners, java.util.function.Supplier<Palette> palette) {
        this(spawners, palette, rarity -> null);
    }

    /** What this server's Minecraft version and worlds offer, for validating {@code features/crates.yml}. */
    static CratesSettings.Catalog catalog() {
        Set<String> items = new java.util.HashSet<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && !material.isAir() && material.isItem()) {
                items.add(material.getKey().asString());
            }
        }
        Map<String, Integer> enchantments = new HashMap<>();
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Enchantment enchantment : registry) {
            enchantments.put(registry.getKeyOrThrow(enchantment).asString(), enchantment.getMaxLevel());
        }
        Set<String> mobs = new java.util.HashSet<>();
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.UNKNOWN && type.isAlive() && type.isSpawnable()) {
                mobs.add(type.getKey().asString());
            }
        }
        Set<String> worlds = new java.util.HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(world.getName());
        }
        return new CratesSettings.Catalog(items, enchantments, mobs, worlds);
    }

    /** Whether the reward can be given right now (spawner rewards need the spawners feature to know the mob). */
    boolean available(Reward reward) {
        return !(reward.kind() instanceof Reward.Spawner spawner) || this.spawners.mobs().contains(spawner.mobId());
    }

    /** The rewards of a crate that can be won right now, in file order. */
    List<Reward> available(Crate crate) {
        List<Reward> result = new ArrayList<>(crate.rewards().size());
        for (Reward reward : crate.rewards()) {
            if (available(reward)) {
                result.add(reward);
            }
        }
        return result;
    }

    /** The items a reward gives (empty for money, shards, keys and commands, or when it can't be made now). */
    Optional<ItemStack> build(Reward reward) {
        return switch (reward.kind()) {
            case Reward.Item item -> Optional.of(item(item, nameColor(reward)));
            case Reward.Spawner spawner -> this.spawners.mobs().contains(spawner.mobId())
                ? this.spawners.create(spawner.mobId(), spawner.amount()).filter(stack -> !stack.isEmpty())
                : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** The colour of a reward item's own name: its rarity's, else the primary text colour. */
    private TextColor nameColor(Reward reward) {
        TextColor color = this.rarityColor.apply(reward.rarity());
        return color != null ? color : this.palette.get().primary();
    }

    private ItemStack item(Reward.Item item, TextColor nameColor) {
        Material material = material(item.item(), Material.STONE);
        ItemStack stack = ItemStack.of(material, item.amount());
        Palette colors = this.palette.get();
        if (item.name() != null) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(item.name(), nameColor)
                .decoration(TextDecoration.ITALIC, false));
        }
        if (!item.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lines.add(Component.text(line, colors.secondary()).decoration(TextDecoration.ITALIC, false));
            }
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        if (!item.enchants().isEmpty()) {
            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            item.enchants().forEach((key, level) -> {
                Enchantment enchantment = registry.get(Key.key(key));
                if (enchantment != null) {
                    enchants.put(enchantment, level);
                }
            });
            if (!enchants.isEmpty()) {
                stack.setData(material == Material.ENCHANTED_BOOK ? DataComponentTypes.STORED_ENCHANTMENTS : DataComponentTypes.ENCHANTMENTS,
                    ItemEnchantments.itemEnchantments(enchants));
            }
        }
        return stack;
    }

    /**
     * The preview icon of a reward (without the chance lines): its own item when it gives one, otherwise the
     * configured icon or a fitting default. Shown with at most 99 in the stack.
     */
    ItemStack icon(Reward reward) {
        ItemStack icon;
        if (reward.icon() != null) {
            icon = ItemStack.of(material(reward.icon(), Material.PAPER));
        } else {
            icon = switch (reward.kind()) {
                case Reward.Item item -> item(item, nameColor(reward));
                case Reward.Spawner spawner -> build(reward).orElseGet(() -> ItemStack.of(Material.SPAWNER, spawner.amount()));
                case Reward.Money money -> ItemStack.of(Material.GOLD_INGOT);
                case Reward.Shards shards -> ItemStack.of(Material.AMETHYST_SHARD);
                case Reward.Keys keys -> ItemStack.of(Material.TRIPWIRE_HOOK, keys.amount());
                case Reward.Command command -> ItemStack.of(Material.PAPER);
            };
        }
        if (icon.getAmount() > 99) {
            icon.setAmount(99);
        }
        return icon;
    }

    /** The icon of a crate. */
    static ItemStack crateIcon(Crate crate) {
        return ItemStack.of(material(crate.icon(), Material.CHEST));
    }

    /** Whether the reward shows its own item in the preview (keeping the item's name and enchantments). */
    static boolean showsOwnItem(Reward reward) {
        return reward.icon() == null && (reward.kind() instanceof Reward.Item || reward.kind() instanceof Reward.Spawner);
    }

    static Material material(String key, Material fallback) {
        Material material = Material.matchMaterial(key);
        return material == null || !material.isItem() || material.isAir() ? fallback : material;
    }
}
