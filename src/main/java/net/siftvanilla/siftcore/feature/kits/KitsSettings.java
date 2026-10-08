package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/kits.yml}. Every mistake is reported precisely; a broken item is left out of its kit and a
 * kit that gives nothing is left out entirely, so a startup with a typo still runs and {@code /sift reload} refuses
 * the change.
 *
 * @param blockInCombat players in combat can't claim kits (looking at them still works)
 * @param reminders     players are told on join which kits are ready, and while they play when one becomes ready
 * @param showLocked    kits a player lacks the permission for are listed as locked instead of hidden
 * @param kits          kits in file order
 * @param perks         the perk commands' settings
 */
public record KitsSettings(boolean blockInCombat, boolean reminders, boolean showLocked, List<Kit> kits, Perks perks) {

    /** Kit ids fit the kit_claims column and the permission node. */
    static final String KIT_ID = "[a-z0-9_-]{1,32}";
    /** Ids that are subcommands of /kits or would read like one. */
    static final Set<String> RESERVED = Set.of("give", "reset", "check", "list", "collect", "all", "perks", "help");
    static final int MAX_NAME = 24;
    static final int MAX_DESCRIPTION = 80;
    static final int MAX_ITEM_NAME = 48;
    static final int MAX_LORE_LINES = 8;
    static final int MAX_LORE_LENGTH = 80;
    static final int MAX_ITEMS = 36;
    static final int MAX_AMOUNT = 640;
    static final int MAX_KEYS = 64;

    private static final Set<String> TOP_KEYS = Set.of("block-in-combat", "reminders", "locked-kits", "kits", "perks");
    private static final Set<String> KIT_KEYS = Set.of("name", "description", "icon", "everyone", "cooldown", "items", "keys");
    private static final Set<String> ITEM_KEYS = Set.of("material", "amount", "name", "lore", "enchantments", "unsafe-enchantments",
        "unbreakable");
    private static final Set<String> PERK_KEYS = Set.of("blocked-in-combat", "close-on-combat", "hat");

    /** What the kits do with kits a player can't claim. */
    enum LockedKits {
        HIDE,
        SHOW
    }

    /**
     * The perk commands.
     *
     * @param blockedInCombat perks refused while the player is in combat
     * @param closeOnCombat   a blocked perk's screen closes when its player gets into combat
     * @param hatBlocked      item ids that can't be worn with /hat
     */
    public record Perks(Set<Perk> blockedInCombat, boolean closeOnCombat, Set<String> hatBlocked) {
        public Perks {
            blockedInCombat = blockedInCombat.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(blockedInCombat));
            hatBlocked = Set.copyOf(hatBlocked);
        }

        boolean blocked(Perk perk) {
            return this.blockedInCombat.contains(perk);
        }
    }

    /**
     * What the config may refer to in this Minecraft version and on this server.
     *
     * @param items        item ids
     * @param enchantments enchantment id to its highest vanilla level
     * @param crates       the crates whose keys a kit can give (empty when there are none)
     */
    public record Catalog(Set<String> items, Map<String, Integer> enchantments, Set<String> crates) {
        public Catalog {
            items = Set.copyOf(items);
            enchantments = Map.copyOf(enchantments);
            crates = Set.copyOf(crates);
        }
    }

    public KitsSettings {
        kits = List.copyOf(kits);
    }

    /** The kit with this id, or null. */
    public Kit kit(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (Kit kit : this.kits) {
            if (kit.id().equals(wanted)) {
                return kit;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ parsing

    public static KitsSettings parse(ConfigReader r, Catalog catalog) {
        for (String key : r.keys()) {
            if (!TOP_KEYS.contains(key)) {
                r.problem(key, "is not a kits setting (settings: block-in-combat, reminders, locked-kits, kits, perks)");
            }
        }
        boolean blockInCombat = r.bool("block-in-combat", true);
        boolean reminders = r.bool("reminders", true);
        LockedKits locked = r.enumValue("locked-kits", LockedKits.class, LockedKits.HIDE);
        Map<String, ConfigReader> sections = r.children("kits");
        if (sections.isEmpty()) {
            r.problem("kits", "has no kits; add at least one");
        }
        List<Kit> kits = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : sections.entrySet()) {
            String id = entry.getKey();
            if (!id.matches(KIT_ID) || RESERVED.contains(id)) {
                r.problem("kits." + id, RESERVED.contains(id) ? "uses a reserved id (" + String.join(", ", new java.util.TreeSet<>(RESERVED))
                    + "); pick another name" : "is not a valid kit id (1 to 32 lowercase letters, digits, - or _)");
                continue;
            }
            Kit kit = kit(id, entry.getValue(), catalog);
            if (kit != null) {
                kits.add(kit);
            }
        }
        Perks perks = perks(r.section("perks"), catalog);
        return new KitsSettings(blockInCombat, reminders, locked == LockedKits.SHOW, kits, perks);
    }

    private static Kit kit(String id, ConfigReader c, Catalog catalog) {
        for (String key : c.keys()) {
            if (!KIT_KEYS.contains(key)) {
                c.problem(key, "is not a kit setting (kit settings: name, description, icon, everyone, cooldown, items, keys)");
            }
        }
        String name = plain(c, "name", PlainText.capitalize(id), MAX_NAME);
        String description = c.has("description") ? plain(c, "description", null, MAX_DESCRIPTION) : null;
        boolean everyone = c.has("everyone") && c.bool("everyone", false);
        Cooldown cooldown = c.custom("cooldown", Cooldown::parse, "once or a duration like 24h or 1d12h", null);
        if (cooldown == null) {
            // Already reported; without a cooldown the kit can't be offered safely.
            return null;
        }
        List<KitItem> items = new ArrayList<>();
        Map<String, ConfigReader> itemSections = c.children("items");
        if (itemSections.size() > MAX_ITEMS) {
            c.problem("items", "has " + itemSections.size() + " items; a kit gives at most " + MAX_ITEMS);
        }
        int index = 0;
        for (Map.Entry<String, ConfigReader> entry : itemSections.entrySet()) {
            if (index++ >= MAX_ITEMS) {
                break;
            }
            KitItem item = item(entry.getKey(), entry.getValue(), catalog);
            if (item != null) {
                items.add(item);
            }
        }
        Map<String, Integer> keys = keys(c, catalog);
        if (items.isEmpty() && keys.isEmpty()) {
            c.problem("items", "gives nothing that works, so the kit is left out");
            return null;
        }
        String icon;
        if (c.has("icon")) {
            icon = item(c, "icon", catalog);
            if (icon == null) {
                icon = items.isEmpty() ? "minecraft:chest" : items.getFirst().material();
            }
        } else {
            icon = items.isEmpty() ? "minecraft:chest" : items.getFirst().material();
        }
        return new Kit(id, name, description, icon, everyone, cooldown, items, keys);
    }

    private static KitItem item(String key, ConfigReader c, Catalog catalog) {
        int before = c.problems().size();
        for (String setting : c.keys()) {
            if (!ITEM_KEYS.contains(setting)) {
                c.problem(setting, "is not an item setting (item settings: material, amount, name, lore, enchantments, "
                    + "unsafe-enchantments, unbreakable)");
            }
        }
        String material;
        if (c.has("material")) {
            material = item(c, "material", catalog);
        } else {
            material = PlainText.id(key);
            if (material == null || !catalog.items().contains(material)) {
                c.problem("material", "is missing and '" + key + "' is not an item of this Minecraft version; name the item with"
                    + " material: (for example material: diamond_sword)");
                material = null;
            }
        }
        int amount = c.has("amount") ? c.integer("amount", 1, MAX_AMOUNT, 1) : 1;
        String name = c.has("name") ? plain(c, "name", null, MAX_ITEM_NAME) : null;
        List<String> lore = new ArrayList<>();
        List<String> lines = c.optionalStringList("lore");
        if (lines.size() > MAX_LORE_LINES) {
            c.problem("lore", "has " + lines.size() + " lines; use at most " + MAX_LORE_LINES);
        }
        for (int i = 0; i < Math.min(MAX_LORE_LINES, lines.size()); i++) {
            String line = lines.get(i).strip();
            String problem = PlainText.problem(line);
            if (problem != null) {
                c.problem("lore", "line " + (i + 1) + " " + problem);
            } else if (line.length() > MAX_LORE_LENGTH) {
                c.problem("lore", "line " + (i + 1) + " is longer than " + MAX_LORE_LENGTH + " characters");
            } else {
                lore.add(line);
            }
        }
        boolean unsafe = c.has("unsafe-enchantments") && c.bool("unsafe-enchantments", false);
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        if (c.has("enchantments")) {
            ConfigReader section = c.section("enchantments");
            for (String raw : section.keys()) {
                String enchantment = PlainText.id(raw);
                Integer max = enchantment == null ? null : catalog.enchantments().get(enchantment);
                if (max == null) {
                    section.problem(raw, "is not an enchantment of this Minecraft version (use ids like sharpness or protection)");
                    continue;
                }
                int level = section.integer(raw, 1, unsafe ? 255 : Math.max(1, max), 1);
                enchantments.put(enchantment, level);
            }
        }
        boolean unbreakable = c.has("unbreakable") && c.bool("unbreakable", false);
        if (material == null || c.problems().size() > before) {
            return null;
        }
        return new KitItem(material, amount, name, lore, enchantments, unbreakable);
    }

    private static Map<String, Integer> keys(ConfigReader c, Catalog catalog) {
        Map<String, Integer> keys = new LinkedHashMap<>();
        if (!c.has("keys")) {
            return keys;
        }
        ConfigReader section = c.section("keys");
        for (String raw : section.keys()) {
            String crate = raw.strip().toLowerCase(Locale.ROOT);
            if (catalog.crates().isEmpty()) {
                section.problem(raw, "gives crate keys, but there are no crates (the crates feature has none)");
                continue;
            }
            if (!catalog.crates().contains(crate)) {
                section.problem(raw, "is not a crate (crates: " + String.join(", ", new java.util.TreeSet<>(catalog.crates())) + ")");
                continue;
            }
            int before = section.problems().size();
            int amount = section.integer(raw, 1, MAX_KEYS, 1);
            if (section.problems().size() == before) {
                keys.put(crate, amount);
            }
        }
        return keys;
    }

    private static Perks perks(ConfigReader p, Catalog catalog) {
        for (String key : p.keys()) {
            if (!PERK_KEYS.contains(key)) {
                p.problem(key, "is not a perks setting (settings: blocked-in-combat, close-on-combat, hat)");
            }
        }
        Set<Perk> blocked = EnumSet.noneOf(Perk.class);
        for (String id : p.stringList("blocked-in-combat", List.of())) {
            Perk perk = Perk.byId(id);
            if (perk == null) {
                p.problem("blocked-in-combat", "has '" + id + "', which is not a perk (perks: " + Perk.ids() + ")");
                continue;
            }
            blocked.add(perk);
        }
        boolean close = p.bool("close-on-combat", true);
        Set<String> hatBlocked = new LinkedHashSet<>();
        ConfigReader hat = p.section("hat");
        for (String raw : hat.stringList("blocked", List.of())) {
            String id = PlainText.id(raw);
            if (id == null || !catalog.items().contains(id)) {
                hat.problem("blocked", "has '" + raw + "', which is not an item of this Minecraft version");
                continue;
            }
            hatBlocked.add(id);
        }
        return new Perks(blocked, close, hatBlocked);
    }

    private static String item(ConfigReader c, String path, Catalog catalog) {
        String raw = c.string(path, "");
        String item = PlainText.id(raw);
        if (item == null || !catalog.items().contains(item)) {
            c.problem(path, "is '" + raw + "', which is not an item of this Minecraft version");
            return null;
        }
        return item;
    }

    private static String plain(ConfigReader c, String path, String fallback, int maxLength) {
        if (!c.has(path) && fallback != null) {
            return fallback;
        }
        String text = c.string(path, fallback == null ? "" : fallback).strip();
        String problem = PlainText.problem(text);
        if (problem != null) {
            c.problem(path, problem);
            return fallback;
        }
        if (text.length() > maxLength) {
            c.problem(path, "is longer than " + maxLength + " characters");
            return fallback;
        }
        return text;
    }
}
