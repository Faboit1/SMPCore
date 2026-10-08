package net.siftvanilla.siftcore.feature.orders;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.item.ItemCategories;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.potion.PotionType;

/**
 * The item side of orders: which item types exist (read once from the registries), their names and categories, which
 * of them can be ordered, and the canonical {@link OrderItem} of every order key.
 * <p>
 * <b>Plain items.</b> An order for a type accepts only stacks that are exactly that type's default item. Enchanted
 * books, potions (and splash, lingering, tipped arrows) and spawners are never ordered plain; they are ordered as exact
 * variants (one enchantment at one level, one base potion type, one SiftCore spawner mob), each with its own switch in
 * the config. Everything else can be ordered unless {@code blocked-items} lists it.
 */
final class OrderItems {

    private static final String MINECRAFT = "minecraft:";
    static final String ENCHANTED_BOOK = "minecraft:enchanted_book";
    static final String SPAWNER = "minecraft:spawner";
    static final Set<String> POTIONS = Set.of("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion",
        "minecraft:tipped_arrow");
    /** Item types that are only ever ordered as a variant. */
    static final Set<String> VARIANT_ONLY;

    static {
        Set<String> set = new HashSet<>(POTIONS);
        set.add(ENCHANTED_BOOK);
        set.add(SPAWNER);
        VARIANT_ONLY = Set.copyOf(set);
    }

    /** {@code mending book}, {@code sharpness 5 book}, {@code sharpness v book}. */
    private static final Pattern BOOK_TEXT = Pattern.compile("(.+?)(?:\\s+([ivx]+|[0-9]{1,3}))?\\s+book");

    private final Lang lang;
    private final SpawnerItems spawners;
    private final Supplier<OrdersSettings> settings;
    private final Map<String, Material> byKey;
    private final Map<String, String> names;
    private final Map<String, ItemCategory> categories;
    private final ItemLookup lookup;
    private final Map<String, Enchantment> enchantments;
    private final Map<String, String> enchantmentNames;
    private final Map<String, PotionType> potionTypes;
    private final Map<String, Optional<OrderItem>> resolved = new ConcurrentHashMap<>();
    private volatile Orderable orderable;

    /** The plain orderable item types under one settings instance. */
    private record Orderable(OrdersSettings settings, List<String> keys) {
    }

    private OrderItems(Lang lang, SpawnerItems spawners, Supplier<OrdersSettings> settings, Map<String, Material> byKey,
                       Map<String, String> names, Map<String, ItemCategory> categories, Map<String, Enchantment> enchantments,
                       Map<String, String> enchantmentNames, Map<String, PotionType> potionTypes) {
        this.lang = lang;
        this.spawners = spawners;
        this.settings = settings;
        this.byKey = Map.copyOf(byKey);
        this.names = Map.copyOf(names);
        this.categories = Map.copyOf(categories);
        this.lookup = new ItemLookup(this.names);
        this.enchantments = Map.copyOf(enchantments);
        this.enchantmentNames = Map.copyOf(enchantmentNames);
        this.potionTypes = Map.copyOf(potionTypes);
    }

    /** Every item key in the item registry ({@code minecraft:diamond}); safe before the feature is built. */
    static Set<String> registryKeys() {
        Set<String> keys = new HashSet<>();
        for (ItemType type : Registry.ITEM) {
            NamespacedKey key = Registry.ITEM.getKey(type);
            if (key != null && !key.asString().equals("minecraft:air")) {
                keys.add(key.asString());
            }
        }
        return keys;
    }

    /** Reads every item type, enchantment and potion type from the registries (call once, when the feature is built). */
    static OrderItems load(Lang lang, SpawnerItems spawners, Supplier<OrdersSettings> settings) {
        Map<String, Tag<Material>> tags = categoryTags();
        Map<String, Material> byKey = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, ItemCategory> categories = new HashMap<>();
        for (ItemType type : Registry.ITEM) {
            NamespacedKey key = Registry.ITEM.getKey(type);
            if (key == null) {
                continue;
            }
            Material material = Material.matchMaterial(key.asString());
            if (material == null || !material.isItem() || material.isAir()) {
                continue;
            }
            String full = key.asString();
            byKey.put(full, material);
            names.put(full, englishName(Component.translatable(material), material.translationKey(), key.value()));
            categories.put(full, category(material, full, tags));
        }
        Map<String, Enchantment> enchantments = new HashMap<>();
        Map<String, String> enchantmentNames = new HashMap<>();
        Registry<Enchantment> enchantmentRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Enchantment enchantment : enchantmentRegistry) {
            String key = enchantment.getKey().asString();
            enchantments.put(key, enchantment);
            enchantmentNames.put(key, englishName(enchantment.description(), "enchantment." + enchantment.getKey().getNamespace()
                + "." + enchantment.getKey().getKey(), enchantment.getKey().getKey()));
        }
        Map<String, PotionType> potions = new HashMap<>();
        for (PotionType type : Registry.POTION) {
            potions.put(type.getKey().asString(), type);
        }
        return new OrderItems(lang, spawners, settings, byKey, names, categories, enchantments, enchantmentNames, potions);
    }

    private static Map<String, Tag<Material>> categoryTags() {
        Map<String, Tag<Material>> map = new LinkedHashMap<>();
        map.put("swords", Tag.ITEMS_SWORDS);
        map.put("spears", Tag.ITEMS_SPEARS);
        map.put("axes", Tag.ITEMS_AXES);
        map.put("pickaxes", Tag.ITEMS_PICKAXES);
        map.put("shovels", Tag.ITEMS_SHOVELS);
        map.put("hoes", Tag.ITEMS_HOES);
        map.put("head_armor", Tag.ITEMS_HEAD_ARMOR);
        map.put("chest_armor", Tag.ITEMS_CHEST_ARMOR);
        map.put("leg_armor", Tag.ITEMS_LEG_ARMOR);
        map.put("foot_armor", Tag.ITEMS_FOOT_ARMOR);
        map.put("arrows", Tag.ITEMS_ARROWS);
        map.put("compasses", Tag.ITEMS_COMPASSES);
        map.put("bundles", Tag.ITEMS_BUNDLES);
        map.put("boats", Tag.ITEMS_BOATS);
        map.put("chest_boats", Tag.ITEMS_CHEST_BOATS);
        map.values().removeIf(java.util.Objects::isNull);
        return map;
    }

    private static ItemCategory category(Material material, String key, Map<String, Tag<Material>> tags) {
        Set<String> member = new HashSet<>();
        tags.forEach((name, tag) -> {
            if (tag.isTagged(material)) {
                member.add(name);
            }
        });
        boolean food = material.isEdible() || ItemStack.of(material).hasData(DataComponentTypes.FOOD);
        return ItemCategories.classify(new ItemCategories.Traits(key, member, material.isBlock(), food));
    }

    /**
     * The English name the server's language gives a translatable name, or one built from the key when the server
     * can't translate it (e.g. {@code diamond_block} becomes {@code Diamond block}).
     */
    private static String englishName(Component translatable, String translationKey, String path) {
        String translated = TextStyle.plain(translatable);
        if (!translated.isBlank() && !translated.equals(translationKey)) {
            return translated;
        }
        String spaced = path.replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    // ------------------------------------------------------------------ registry facts

    /** The material of an item key, or null when the type does not exist (any more). */
    Material material(String itemType) {
        return this.byKey.get(itemType);
    }

    static String key(Material material) {
        return material.getKey().asString();
    }

    /** The key without the {@code minecraft:} namespace. */
    static String path(String key) {
        return key.startsWith(MINECRAFT) ? key.substring(MINECRAFT.length()) : key;
    }

    /** The category of an item type ({@link ItemCategory#MISC} for unknown types). */
    ItemCategory category(String itemType) {
        return this.categories.getOrDefault(itemType, ItemCategory.MISC);
    }

    /** The category of an order (by its item type). */
    ItemCategory category(Order order) {
        return category(order.itemType());
    }

    // ------------------------------------------------------------------ what can be ordered

    /** True for item types only ever ordered as a variant (enchanted books, potions, spawners). */
    static boolean variantOnly(String itemType) {
        return VARIANT_ONLY.contains(itemType);
    }

    /** True when new orders for this variant family (an item type that is variant-only) may be placed. */
    boolean familyEnabled(String itemType) {
        OrdersSettings s = this.settings.get();
        if (ENCHANTED_BOOK.equals(itemType)) {
            return s.booksEnabled();
        }
        if (POTIONS.contains(itemType)) {
            return s.potionsEnabled();
        }
        if (SPAWNER.equals(itemType)) {
            return s.spawnersEnabled() && !this.spawners.mobs().isEmpty();
        }
        return false;
    }

    /** True when a plain order for this item type may be placed. */
    boolean plainOrderable(String itemType) {
        return this.byKey.containsKey(itemType) && !variantOnly(itemType) && !this.settings.get().isBlocked(itemType);
    }

    /**
     * True when a new order for this exact key may be placed: a plain item that is not blocked, or a valid variant of
     * an enabled family (curses only when allowed).
     */
    boolean orderable(String key) {
        OrderKeys.Parts parts = OrderKeys.parse(key);
        if (parts.variant() == null) {
            return plainOrderable(parts.itemType());
        }
        if (!familyEnabled(parts.itemType())) {
            return false;
        }
        OrderItem item = resolve(key);
        if (item == null) {
            return false;
        }
        if (item.variant() instanceof Variant.Enchant enchant) {
            Enchantment enchantment = this.enchantments.get(enchant.enchantment());
            return enchantment != null && enchant.level() <= enchantment.getMaxLevel()
                && (!enchantment.isCursed() || this.settings.get().allowCurses());
        }
        return true;
    }

    /**
     * Plain item types that can be ordered now, sorted by key. Worked out once per settings (a reload makes new ones),
     * since the picker and the browser filters ask on every redraw.
     */
    List<String> plainOrderable() {
        OrdersSettings current = this.settings.get();
        Orderable cached = this.orderable;
        if (cached != null && cached.settings() == current) {
            return cached.keys();
        }
        List<String> list = new ArrayList<>();
        for (String key : this.byKey.keySet()) {
            if (plainOrderable(key)) {
                list.add(key);
            }
        }
        list.sort(null);
        List<String> keys = List.copyOf(list);
        this.orderable = new Orderable(current, keys);
        return keys;
    }

    /** Variant families that can be ordered now (item types), sorted by key. */
    List<String> families() {
        List<String> list = new ArrayList<>();
        for (String key : VARIANT_ONLY) {
            if (this.byKey.containsKey(key) && familyEnabled(key)) {
                list.add(key);
            }
        }
        list.sort(null);
        return list;
    }

    /** Categories that hold at least one orderable item or family. */
    Set<ItemCategory> orderableCategories() {
        Set<ItemCategory> set = EnumSet.noneOf(ItemCategory.class);
        for (String key : plainOrderable()) {
            set.add(category(key));
        }
        for (String key : families()) {
            set.add(category(key));
        }
        return set;
    }

    /** Enchantments a book order can ask for, by English name, curses last (and only when allowed). */
    List<Enchantment> bookEnchantments() {
        boolean curses = this.settings.get().allowCurses();
        List<Enchantment> list = new ArrayList<>();
        for (Enchantment enchantment : this.enchantments.values()) {
            if (!enchantment.isCursed() || curses) {
                list.add(enchantment);
            }
        }
        list.sort(Comparator.comparing(Enchantment::isCursed).thenComparing(e -> enchantmentName(e.getKey().asString())));
        return list;
    }

    /** Base potion types a potion order can ask for, by key. */
    List<PotionType> potionTypes() {
        List<PotionType> list = new ArrayList<>(this.potionTypes.values());
        list.sort(Comparator.comparing(type -> type.getKey().asString()));
        return list;
    }

    /** Spawner mobs the spawner feature provides, sorted. */
    List<String> spawnerMobs() {
        List<String> list = new ArrayList<>(this.spawners.mobs());
        list.sort(null);
        return list;
    }

    Enchantment enchantment(String key) {
        return this.enchantments.get(key);
    }

    /** The English name of an enchantment key. */
    String enchantmentName(String key) {
        String name = this.enchantmentNames.get(key);
        return name == null ? key : name;
    }

    // ------------------------------------------------------------------ resolving keys

    /**
     * The canonical item of an order key, or null when it can't be built: an unknown item type, a malformed or unknown
     * variant (an enchantment removed by a data pack, a spawner mob the spawner feature no longer provides), or a
     * variant that does not fit the item type. Cached per key, except for spawners (their provider may change).
     */
    OrderItem resolve(String key) {
        OrderKeys.Parts parts = OrderKeys.parse(key);
        if (SPAWNER.equals(parts.itemType())) {
            return build(parts.itemType(), parts.variant());
        }
        return this.resolved.computeIfAbsent(key, k -> Optional.ofNullable(build(parts.itemType(), parts.variant()))).orElse(null);
    }

    OrderItem resolve(String itemType, String variant) {
        return resolve(OrderKeys.key(itemType, variant));
    }

    /** The canonical item of an order, or null when its item can't be built right now. */
    OrderItem of(Order order) {
        return resolve(order.key());
    }

    private OrderItem build(String itemType, String variantId) {
        Material material = this.byKey.get(itemType);
        if (material == null) {
            return null;
        }
        if (variantId == null) {
            return new OrderItem(itemType, null, material, ItemStack.of(material), Component.translatable(material),
                this.names.getOrDefault(itemType, path(itemType)));
        }
        Variant variant = Variant.tryParse(variantId);
        return switch (variant) {
            case Variant.Enchant enchant -> book(itemType, material, enchant);
            case Variant.Potion potion -> potion(itemType, material, potion);
            case Variant.Spawner spawner -> spawner(itemType, material, spawner);
            case null -> null;
        };
    }

    private OrderItem book(String itemType, Material material, Variant.Enchant variant) {
        Enchantment enchantment = this.enchantments.get(variant.enchantment());
        if (!ENCHANTED_BOOK.equals(itemType) || enchantment == null) {
            return null;
        }
        ItemStack prototype = ItemStack.of(material);
        prototype.setData(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantments.itemEnchantments(Map.of(enchantment, variant.level())));
        boolean showLevel = enchantment.getMaxLevel() > 1 || variant.level() > 1;
        String english = this.enchantmentNames.getOrDefault(variant.enchantment(), variant.enchantment());
        Component name;
        String plain;
        if (showLevel) {
            Component level = variant.level() <= 10 ? Component.translatable("enchantment.level." + variant.level())
                : Component.text(variant.level());
            name = this.lang.get(OrdersMessages.ITEM_BOOK_LEVEL, Arg.component("enchantment", enchantment.description()),
                Arg.component("level", level));
            plain = this.lang.plain(OrdersMessages.ITEM_BOOK_LEVEL, Arg.text("enchantment", english),
                Arg.text("level", Variant.roman(variant.level())));
        } else {
            name = this.lang.get(OrdersMessages.ITEM_BOOK, Arg.component("enchantment", enchantment.description()));
            plain = this.lang.plain(OrdersMessages.ITEM_BOOK, Arg.text("enchantment", english));
        }
        return new OrderItem(itemType, variant, material, prototype, unstyled(name), plain);
    }

    private OrderItem potion(String itemType, Material material, Variant.Potion variant) {
        PotionType type = this.potionTypes.get(variant.type());
        if (!POTIONS.contains(itemType) || type == null) {
            return null;
        }
        ItemStack prototype = ItemStack.of(material);
        prototype.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().potion(type).build());
        String typePath = type.getKey().getKey();
        String base = typePath.startsWith("long_") ? typePath.substring(5) : typePath.startsWith("strong_") ? typePath.substring(7) : typePath;
        String translationKey = "item.minecraft." + path(itemType) + ".effect." + base;
        Component baseName = Component.translatable(translationKey);
        String english = englishName(baseName, translationKey, path(itemType) + "_of_" + base);
        Component name = baseName;
        String plain = english;
        if (typePath.startsWith("long_")) {
            name = this.lang.get(OrdersMessages.ITEM_POTION_LONG, Arg.component("item", baseName));
            plain = this.lang.plain(OrdersMessages.ITEM_POTION_LONG, Arg.text("item", english));
        } else if (typePath.startsWith("strong_")) {
            name = this.lang.get(OrdersMessages.ITEM_POTION_STRONG, Arg.component("item", baseName));
            plain = this.lang.plain(OrdersMessages.ITEM_POTION_STRONG, Arg.text("item", english));
        }
        return new OrderItem(itemType, variant, material, prototype, unstyled(name), plain);
    }

    private OrderItem spawner(String itemType, Material material, Variant.Spawner variant) {
        if (!SPAWNER.equals(itemType)) {
            return null;
        }
        Optional<ItemStack> created = this.spawners.create(variant.mob(), 1);
        if (created.isEmpty() || created.get().isEmpty() || created.get().getType() != material) {
            return null;
        }
        String plain = TextStyle.plain(Items.name(created.get()));
        return new OrderItem(itemType, variant, material, created.get(), Component.text(plain), plain);
    }

    /** A name without any colour or decoration of its own (it takes the surrounding text's style). */
    private static Component unstyled(Component name) {
        return name.style(net.kyori.adventure.text.format.Style.empty());
    }

    /**
     * The order key of an exact stack, or null when no order could take it: a plain item that may be ordered, or a
     * book, potion or spawner that is exactly one variant of an enabled family.
     */
    String key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String itemType = key(stack.getType());
        if (!this.byKey.containsKey(itemType)) {
            return null;
        }
        if (!variantOnly(itemType)) {
            if (!plainOrderable(itemType)) {
                return null;
            }
            OrderItem plain = resolve(itemType);
            return plain != null && plain.matches(stack) ? itemType : null;
        }
        if (!familyEnabled(itemType)) {
            return null;
        }
        String candidate = null;
        if (ENCHANTED_BOOK.equals(itemType)) {
            ItemEnchantments stored = stack.getData(DataComponentTypes.STORED_ENCHANTMENTS);
            if (stored == null || stored.enchantments().size() != 1) {
                return null;
            }
            var entry = stored.enchantments().entrySet().iterator().next();
            candidate = new Variant.Enchant(entry.getKey().getKey().asString(), entry.getValue()).id();
        } else if (POTIONS.contains(itemType)) {
            PotionContents contents = stack.getData(DataComponentTypes.POTION_CONTENTS);
            if (contents == null || contents.potion() == null) {
                return null;
            }
            candidate = new Variant.Potion(contents.potion().getKey().asString()).id();
        } else if (SPAWNER.equals(itemType)) {
            for (String mob : this.spawners.mobs()) {
                OrderItem item = resolve(itemType, new Variant.Spawner(mob).id());
                if (item != null && item.matches(stack)) {
                    return orderable(item.key()) ? item.key() : null;
                }
            }
            return null;
        }
        String key = OrderKeys.key(itemType, candidate);
        OrderItem item = resolve(key);
        return item != null && item.matches(stack) && orderable(key) ? key : null;
    }

    /** The order key of every stack in the given contents, counted (outer stacks and the insides of shulker boxes). */
    Map<String, Integer> carried(ItemStack[] storage) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : storage) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String key = key(stack);
            if (key != null) {
                counts.merge(key, stack.getAmount(), Integer::sum);
            }
            if (ContainerItems.isShulker(stack)) {
                for (ItemStack inner : ContainerItems.contents(stack)) {
                    String innerKey = inner.isEmpty() ? null : key(inner);
                    if (innerKey != null) {
                        counts.merge(innerKey, inner.getAmount(), Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ names and text

    /** The name of an order key for messages and lore (unstyled), or the key itself when unknown. */
    Component name(String key) {
        OrderItem item = resolve(key);
        return item == null ? Component.text(key) : item.name();
    }

    /** The English name of an order key, or the key itself when unknown. */
    String plainName(String key) {
        OrderItem item = resolve(key);
        if (item != null) {
            return item.plainName();
        }
        String name = this.names.get(OrderKeys.itemType(key));
        return name == null ? key : name;
    }

    /** Lowercase text menus search: the English name and the key with spaces. */
    String searchText(String key) {
        return (plainName(key) + " " + path(OrderKeys.itemType(key)).replace('_', ' ')).toLowerCase(Locale.ROOT);
    }

    /**
     * The order key for what a player typed: an item key or name ({@code diamond}, {@code Block of Diamond}), or an
     * enchanted book ({@code mending book}, {@code sharpness 5 book}). A variant family typed on its own (enchanted
     * book, potion) comes back as its item type, which the caller turns into "choose which one". Empty when nothing
     * matches.
     */
    Optional<String> find(String input) {
        Optional<String> plain = this.lookup.find(input);
        if (plain.isPresent()) {
            return plain;
        }
        String text = ItemLookup.normalize(input);
        Matcher matcher = BOOK_TEXT.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String enchantmentText = matcher.group(1).strip();
        String key = null;
        for (Map.Entry<String, String> entry : this.enchantmentNames.entrySet()) {
            String name = ItemLookup.normalize(entry.getValue());
            String keyText = path(entry.getKey()).replace('_', ' ');
            if (name.equals(enchantmentText) || keyText.equals(enchantmentText) || entry.getKey().equals(enchantmentText)) {
                key = entry.getKey();
                break;
            }
        }
        if (key == null) {
            return Optional.empty();
        }
        int level = 1;
        String levelText = matcher.group(2);
        if (levelText != null) {
            level = levelText.chars().allMatch(Character::isDigit) ? Integer.parseInt(levelText) : fromRoman(levelText);
        }
        if (level < 1 || level > 255) {
            return Optional.empty();
        }
        return Optional.of(OrderKeys.key(ENCHANTED_BOOK, new Variant.Enchant(key, level).id()));
    }

    private static int fromRoman(String text) {
        for (int level = 1; level <= 10; level++) {
            if (Variant.roman(level).toLowerCase(Locale.ROOT).equals(text)) {
                return level;
            }
        }
        return -1;
    }

    /** Orderable item key paths starting with {@code prefix} (command suggestions), sorted, at most {@code limit}. */
    List<String> suggest(String prefix, int limit) {
        List<String> result = new ArrayList<>();
        for (String path : this.lookup.suggest(prefix, this.byKey.size())) {
            if (plainOrderable(MINECRAFT + path) || plainOrderable(path)) {
                result.add(path);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** The text the item field of the create form shows for an order key: the key path or the book text. */
    String fieldText(String key) {
        OrderKeys.Parts parts = OrderKeys.parse(key);
        if (parts.variant() == null) {
            return path(parts.itemType());
        }
        OrderItem item = resolve(key);
        return item == null ? key : item.plainName();
    }
}
