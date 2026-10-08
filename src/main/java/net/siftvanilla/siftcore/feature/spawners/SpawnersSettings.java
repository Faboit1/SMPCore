package net.siftvanilla.siftcore.feature.spawners;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/spawners.yml}.
 *
 * @param interval          how often active spawners make loot
 * @param radius            a spawner is active while a player is within this many blocks
 * @param countVanished     whether vanished staff keep spawners active
 * @param countAfk          whether AFK players keep spawners active
 * @param slotsPerSpawner   default storage slots of 64 items per stacked spawner
 * @param xpPerSpawner      XP one stacked spawner holds at most
 * @param flushInterval     how often generated loot is written to storage
 * @param defaultCap        default stack cap (mobs may set their own)
 * @param openRequiresSneak whether opening the storage needs sneaking
 * @param remoteRange       how close a player must be to open the storage from /spawners
 * @param blockInCombat     whether combat-tagged players are kept out of spawner storages
 * @param requireSilkTouch  whether picking up needs a silk touch tool
 * @param breakStorage      where stored items go when a spawner is picked up
 * @param maxClaimStacks    the most claim box stacks picking up may create
 * @param maxPerChunk       the most spawner blocks in one chunk (0 = no limit)
 * @param disabledWorlds    worlds where spawners can't be placed (lowercase names)
 * @param naturalPickup     whether natural spawners drop a spawner item when mined with silk touch
 * @param applyMending      whether collected XP repairs mending gear first
 * @param pageSize          spawners per page in /spawners
 * @param mobs              configured spawner types by id, in file order
 */
public record SpawnersSettings(
    Duration interval,
    int radius,
    boolean countVanished,
    boolean countAfk,
    int slotsPerSpawner,
    long xpPerSpawner,
    Duration flushInterval,
    int defaultCap,
    boolean openRequiresSneak,
    int remoteRange,
    boolean blockInCombat,
    boolean requireSilkTouch,
    BreakStorage breakStorage,
    int maxClaimStacks,
    int maxPerChunk,
    Set<String> disabledWorlds,
    boolean naturalPickup,
    boolean applyMending,
    int pageSize,
    Map<String, MobDef> mobs) {

    /** Where stored items go when a spawner is picked up. */
    public enum BreakStorage {
        /** Into the owner's claim box. */
        CLAIM_BOX,
        /** Sold for the owner; items that can't be sold go to their claim box. */
        SELL
    }

    /** Which item and mob keys exist in this Minecraft version ({@code minecraft:stone}, {@code minecraft:zombie}). */
    public record Catalog(Predicate<String> isItem, Predicate<String> isMob) {
    }

    private static final Pattern MOB_ID = Pattern.compile("[a-z0-9_]{1,48}");
    /** Kills per cycle of a mob that doesn't set it. */
    static final double DEFAULT_KILLS = 2.0;

    public SpawnersSettings {
        disabledWorlds = Set.copyOf(disabledWorlds);
        mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
    }

    /** A configured mob, enabled or not. */
    MobDef mob(String id) {
        return id == null ? null : this.mobs.get(id);
    }

    /** Ids of the enabled mobs, in file order. */
    Set<String> enabledMobs() {
        Set<String> ids = new LinkedHashSet<>();
        this.mobs.forEach((id, mob) -> {
            if (mob.enabled()) {
                ids.add(id);
            }
        });
        return Collections.unmodifiableSet(ids);
    }

    /** Storage slots per stacked spawner of a mob (its own setting, or the default). */
    int slots(String mob) {
        MobDef def = mob(mob);
        return def == null || def.slots() <= 0 ? this.slotsPerSpawner : def.slots();
    }

    /** The stack cap of a mob before rank bonuses. */
    int cap(String mob) {
        MobDef def = mob(mob);
        return def == null || def.stackCap() <= 0 ? this.defaultCap : def.stackCap();
    }

    public static SpawnersSettings parse(ConfigReader r, Catalog catalog) {
        ConfigReader cycle = r.section("cycle");
        ConfigReader activation = r.section("activation");
        ConfigReader storage = r.section("storage");
        ConfigReader stacking = r.section("stacking");
        ConfigReader interaction = r.section("interaction");
        ConfigReader breaking = r.section("breaking");
        ConfigReader placement = r.section("placement");
        Duration interval = cycle.duration("interval", Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofSeconds(30));
        int radius = activation.integer("radius", 4, 128, 32);
        int slots = storage.integer("slots-per-spawner", 1, 54, 9);
        long xp = storage.longValue("xp-per-spawner", 0, 1_000_000_000L, 6_000);
        Duration flush = storage.duration("flush-interval", Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60));
        int defaultCap = stacking.integer("default-cap", 1, StorageMath.HARD_STACK_CAP, 1000);
        List<String> worlds = new ArrayList<>();
        for (String world : placement.stringList("disabled-worlds", List.of())) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        Map<String, MobDef> mobs = new LinkedHashMap<>();
        ConfigReader mobSection = r.section("mobs", false);
        for (Map.Entry<String, ConfigReader> entry : r.children("mobs").entrySet()) {
            MobDef mob = parseMob(entry.getKey(), entry.getValue(), mobSection, catalog);
            if (mob != null) {
                mobs.put(mob.id(), mob);
            }
        }
        if (mobs.isEmpty()) {
            r.problem("mobs", "has no valid spawner types");
        }
        return new SpawnersSettings(
            interval,
            radius,
            activation.bool("count-vanished", false),
            activation.bool("count-afk", true),
            slots,
            xp,
            flush,
            defaultCap,
            interaction.bool("open-requires-sneak", true),
            interaction.integer("remote-range", 0, 512, 32),
            interaction.bool("block-in-combat", true),
            breaking.bool("require-silk-touch", true),
            breaking.enumValue("storage", BreakStorage.class, BreakStorage.CLAIM_BOX),
            breaking.integer("max-claim-stacks", 1, 10_000, 108),
            placement.integer("max-per-chunk", 0, 4096, 0),
            new LinkedHashSet<>(worlds),
            r.section("natural-spawners").bool("silk-touch-pickup", false),
            r.section("xp").bool("apply-mending", true),
            r.integer("page-size", 4, 20, 10),
            mobs);
    }

    private static MobDef parseMob(String key, ConfigReader m, ConfigReader parent, Catalog catalog) {
        String id = key.toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (!MOB_ID.matcher(id).matches()) {
            parent.problem(key, "is not a mob id (use vanilla ids like zombie or cave_spider)");
            return null;
        }
        if (!catalog.isMob().test("minecraft:" + id)) {
            parent.problem(key, "is not a mob of this Minecraft version (use ids like zombie or cave_spider)");
            return null;
        }
        String name = m.optionalString("name", MobDef.fallbackName(id)).strip();
        if (name.isEmpty() || name.length() > 32) {
            m.problem("name", "must be 1 to 32 characters");
            name = MobDef.fallbackName(id);
        }
        boolean enabled = !m.has("enabled") || m.bool("enabled", true);
        double kills = m.has("kills-per-cycle") ? m.decimal("kills-per-cycle", 0, 100, DEFAULT_KILLS) : DEFAULT_KILLS;
        int xp = m.has("xp-per-kill") ? m.integer("xp-per-kill", 0, 1000, 0) : 0;
        int cap = m.has("stack-cap") ? m.integer("stack-cap", 1, StorageMath.HARD_STACK_CAP, 0) : 0;
        int slots = m.has("slots") ? m.integer("slots", 1, 54, 0) : 0;
        List<DropEntry> drops = new ArrayList<>();
        ConfigReader dropSection = m.section("drops", false);
        for (Map.Entry<String, ConfigReader> drop : m.children("drops").entrySet()) {
            DropEntry parsed = parseDrop(drop.getKey(), drop.getValue(), dropSection, catalog);
            if (parsed != null) {
                drops.add(parsed);
            }
        }
        if (drops.isEmpty() && xp == 0) {
            m.problem("drops", "is empty and xp-per-kill is 0, so this spawner would make nothing");
        }
        return new MobDef(id, name, enabled, kills, xp, cap, slots, drops);
    }

    private static DropEntry parseDrop(String key, ConfigReader d, ConfigReader parent, Catalog catalog) {
        String item = key.toLowerCase(Locale.ROOT);
        if (!item.contains(":")) {
            item = "minecraft:" + item;
        }
        if (!catalog.isItem().test(item)) {
            parent.problem(key, "is not an item of this Minecraft version");
            return null;
        }
        int min = d.has("min") ? d.integer("min", 0, DropEntry.MAX_AMOUNT, 0) : 0;
        int max = d.integer("max", 0, DropEntry.MAX_AMOUNT, 1);
        double chance = d.has("chance") ? d.decimal("chance", 0, 1, 1) : 1.0;
        if (max < min) {
            d.problem("max", "must be at least min (" + min + ")");
            return null;
        }
        if (max == 0) {
            d.problem("max", "is 0, so this drop never gives anything");
            return null;
        }
        return new DropEntry(item, min, max, chance);
    }
}
