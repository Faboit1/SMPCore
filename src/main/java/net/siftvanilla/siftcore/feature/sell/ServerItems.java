package net.siftvanilla.siftcore.feature.sell;

import io.papermc.paper.registry.TypedKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;

/**
 * Reads the server's items, item tags and recipes into an {@link ItemCatalog}. Called once from the feature
 * constructor at startup (the server cannot reload data packs, so nothing here changes while it runs).
 * <p>
 * Recipes that cannot be priced are left out: special recipes (map cloning, firework stars, armor dye, trims),
 * recipes whose result carries extra data, and recipes with ingredients matched by custom predicates. Crafting
 * ingredients that leave something behind in the grid (a milk bucket leaves its bucket) are not counted as options,
 * because the crafter keeps part of their value; a slot left without options makes its recipe unusable for pricing.
 */
final class ServerItems {

    /** What the snapshot contains, for the startup log. */
    record Snapshot(ItemCatalog catalog, int recipes, int skippedRecipes) {
    }

    private ServerItems() {
    }

    static Snapshot snapshot() {
        Set<String> items = new HashSet<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && !material.isAir() && material.isItem()) {
                items.add(material.getKey().asString());
            }
        }
        Map<String, Set<String>> tags = new HashMap<>();
        for (Tag<Material> tag : Bukkit.getTags(Tag.REGISTRY_ITEMS, Material.class)) {
            Set<String> members = new HashSet<>();
            for (Material material : tag.getValues()) {
                members.add(material.getKey().asString());
            }
            tags.put(tag.getKey().asString(), members);
        }
        List<RecipeDef> recipes = new ArrayList<>();
        int seen = 0;
        int skipped = 0;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (true) {
            Recipe recipe;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                recipe = iterator.next();
            } catch (RuntimeException e) {
                // A recipe the server cannot convert to the API; the iterator has moved past it.
                seen++;
                skipped++;
                continue;
            }
            seen++;
            RecipeDef converted;
            try {
                converted = convert(recipe);
            } catch (RuntimeException e) {
                converted = null;
            }
            if (converted == null) {
                skipped++;
            } else {
                recipes.add(converted);
            }
        }
        return new Snapshot(new ItemCatalog(items, tags, recipes), seen, skipped);
    }

    /** True for a plain stack: exactly what a fresh stack of its type looks like (no name, damage, data). */
    static boolean pristine(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getType().isItem()
            && stack.isSimilar(ItemStack.of(stack.getType()));
    }

    private static RecipeDef convert(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return null;
        }
        ItemStack result = recipe.getResult();
        if (!pristine(result)) {
            return null;
        }
        String id = keyed.getKey().asString();
        String output = result.getType().getKey().asString();
        int count = result.getAmount();
        List<List<String>> slots = new ArrayList<>();
        RecipeDef.Kind kind;
        switch (recipe) {
            case ShapedRecipe shaped -> {
                kind = RecipeDef.Kind.CRAFTING;
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (char symbol : row.toCharArray()) {
                        if (!add(slots, choices.get(symbol), true)) {
                            return null;
                        }
                    }
                }
            }
            case ShapelessRecipe shapeless -> {
                kind = RecipeDef.Kind.CRAFTING;
                for (RecipeChoice choice : shapeless.getChoiceList()) {
                    if (!add(slots, choice, true)) {
                        return null;
                    }
                }
            }
            case TransmuteRecipe transmute -> {
                kind = RecipeDef.Kind.CRAFTING;
                if (!add(slots, transmute.getInput(), true) || !add(slots, transmute.getMaterial(), true)) {
                    return null;
                }
            }
            case FurnaceRecipe furnace -> {
                kind = RecipeDef.Kind.SMELTING;
                if (!add(slots, furnace.getInputChoice(), false)) {
                    return null;
                }
            }
            case BlastingRecipe blasting -> {
                kind = RecipeDef.Kind.BLASTING;
                if (!add(slots, blasting.getInputChoice(), false)) {
                    return null;
                }
            }
            case SmokingRecipe smoking -> {
                kind = RecipeDef.Kind.SMOKING;
                if (!add(slots, smoking.getInputChoice(), false)) {
                    return null;
                }
            }
            case CampfireRecipe campfire -> {
                kind = RecipeDef.Kind.CAMPFIRE;
                if (!add(slots, campfire.getInputChoice(), false)) {
                    return null;
                }
            }
            case StonecuttingRecipe stonecutting -> {
                kind = RecipeDef.Kind.STONECUTTING;
                if (!add(slots, stonecutting.getInputChoice(), false)) {
                    return null;
                }
            }
            case SmithingTransformRecipe smithing -> {
                kind = RecipeDef.Kind.SMITHING;
                if (!add(slots, smithing.getTemplate(), false) || !add(slots, smithing.getBase(), false)
                    || !add(slots, smithing.getAddition(), false)) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
        if (slots.isEmpty()) {
            return null;
        }
        return new RecipeDef(id, kind, output, count, RecipeDef.slots(slots));
    }

    /**
     * Adds one slot's options. Empty slots add nothing. Returns false when the choice can't be priced (custom
     * predicates), which makes the whole recipe unusable. In a crafting grid, options that leave an item behind
     * are skipped.
     */
    private static boolean add(List<List<String>> slots, RecipeChoice choice, boolean craftingGrid) {
        if (choice == null || choice == RecipeChoice.empty()) {
            return true;
        }
        List<String> options = new ArrayList<>();
        switch (choice) {
            case RecipeChoice.ItemTypeChoice types -> {
                for (TypedKey<ItemType> key : types.itemTypes()) {
                    ItemType type = Registry.ITEM.get(key.key());
                    if (type != null && !(craftingGrid && type.getCraftingRemainingItem() != null)) {
                        options.add(key.key().asString());
                    }
                }
            }
            case RecipeChoice.MaterialChoice materials -> {
                for (Material material : materials.getChoices()) {
                    if (!(craftingGrid && material.getCraftingRemainingItem() != null)) {
                        options.add(material.getKey().asString());
                    }
                }
            }
            case RecipeChoice.ExactChoice exact -> {
                // Only plain stacks are priced; a slot that needs a custom item has no priced option.
                for (ItemStack stack : exact.getChoices()) {
                    if (pristine(stack) && !(craftingGrid && stack.getType().getCraftingRemainingItem() != null)) {
                        options.add(stack.getType().getKey().asString());
                    }
                }
            }
            default -> {
                return false;
            }
        }
        slots.add(options);
        return true;
    }
}
