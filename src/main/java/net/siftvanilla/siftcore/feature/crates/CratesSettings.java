package net.siftvanilla.siftcore.feature.crates;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/crates.yml}. Every mistake is reported precisely; a broken reward is left out of its crate,
 * a crate without a working reward is left out entirely, and a broken keyall is switched off, so a startup with a
 * typo still runs and {@code /sift reload} refuses the change.
 *
 * @param openCooldown   shortest time between two openings with a command or a crate block
 * @param blockInCombat  players in combat can't open crates (previews still work)
 * @param bulkOpen       the most keys one click or command opens in a row (below 2: one at a time only)
 * @param quickOpen      sneaking and right-clicking a crate block opens a key straight away
 * @param joinReminder   players with unopened keys are reminded when they join
 * @param rememberGrants how long grant references are remembered (a repeated grant inside this window is refused)
 * @param keyall         the keyall schedule
 * @param rarities       rarity tiers in file order
 * @param crates         crates by tier (then file order)
 * @param effects        holograms, particles and the opening animation
 */
public record CratesSettings(Duration openCooldown, boolean blockInCombat, int bulkOpen, boolean quickOpen,
                             boolean joinReminder, Duration rememberGrants,
                             Keyall keyall, List<Rarity> rarities, List<Crate> crates, Effects effects) {

    /** Crate ids fit the crate_keys and crate_log columns. */
    static final String CRATE_ID = "[a-z0-9_-]{1,32}";
    static final String REWARD_ID = "[a-z0-9_-]{1,32}";
    static final String RARITY_ID = "[a-z0-9_-]{1,16}";
    /** Ids that would clash with a placeholder ({@code keys_total}) or a command ({@code /keyall in}). */
    static final Set<String> RESERVED = Set.of("total", "in");
    static final int MAX_ITEM_AMOUNT = 640;
    static final int MAX_KEY_REWARD = 64;
    static final int MAX_BULK_OPEN = 64;
    static final int MAX_NAME = 24;
    static final int MAX_DISPLAY = 64;

    private static final Set<String> CRATE_KEYS = Set.of("name", "tier", "color", "icon", "blocks", "rewards");
    private static final Set<String> REWARD_KEYS = Set.of("item", "amount", "name", "lore", "enchants", "unsafe-enchants",
        "money", "shards", "keys", "spawner", "commands", "weight", "rarity", "display", "icon");
    private static final List<String> KINDS = List.of("item", "money", "shards", "keys", "spawner", "commands");

    /**
     * The keyall: every so often, everyone online gets keys.
     *
     * @param enabled         whether it runs on its own
     * @param interval        time between two keyalls
     * @param crate           the crate whose keys it gives
     * @param amount          keys per player
     * @param missedDelay     when the server was offline at keyall time, how long after startup it runs instead
     * @param includeVanished whether vanished staff get keys too
     * @param includeAfk      whether players who are AFK get keys too
     * @param chatAt          countdown announcements in chat, longest first
     * @param actionBarFrom   the last seconds are counted down in the action bar (zero turns it off)
     */
    public record Keyall(boolean enabled, Duration interval, String crate, int amount, Duration missedDelay,
                         boolean includeVanished, boolean includeAfk, List<Duration> chatAt, Duration actionBarFrom) {
        public Keyall {
            chatAt = List.copyOf(chatAt);
        }

    }

    /**
     * How the crates look and sound.
     *
     * @param holograms      a floating name above every crate block
     * @param hologramHeight how far above the top of the block the name floats, in blocks
     * @param particleRange  particles circle every crate block while a player is this close (0: no particles)
     * @param animation      the opening animation
     */
    public record Effects(boolean holograms, double hologramHeight, int particleRange, Animation animation) {

        /** Everything on, as shipped (tests and settings built in code). */
        public static final Effects DEFAULTS = new Effects(true, 0.5, 16, Animation.DEFAULTS);
    }

    /**
     * The opening animation.
     *
     * @param enabled   whether single openings from a crate screen or the preview animate
     * @param length    how long the row of rewards rolls
     * @param reveal    how long the window shows the reward before it closes
     * @param tick      played on every step of the roll (null: none)
     * @param revealed  played when the reward shows (null: none)
     * @param bigReveal played instead when the reward's rarity is announced (null: none)
     */
    public record Animation(boolean enabled, Duration length, Duration reveal, Sound tick, Sound revealed, Sound bigReveal) {

        public static final Animation DEFAULTS = new Animation(true, Duration.ofSeconds(4), Duration.ofSeconds(2),
            Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 0.6f, 1.0f),
            Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 0.7f, 1.2f),
            Sound.sound(Key.key("ui.toast.challenge_complete"), Sound.Source.MASTER, 0.6f, 1.0f));
    }

    /** What the config may refer to in this Minecraft version and on this server. */
    public record Catalog(Set<String> items, Map<String, Integer> enchantments, Set<String> mobs, Set<String> worlds) {
        public Catalog {
            items = Set.copyOf(items);
            enchantments = Map.copyOf(enchantments);
            mobs = Set.copyOf(mobs);
            worlds = Set.copyOf(worlds);
        }
    }

    public CratesSettings {
        rarities = List.copyOf(rarities);
        crates = List.copyOf(crates);
        effects = effects == null ? Effects.DEFAULTS : effects;
    }

    /** Settings with the shipped effects (tests and settings built in code). */
    public CratesSettings(Duration openCooldown, boolean blockInCombat, int bulkOpen, boolean quickOpen, boolean joinReminder,
                          Duration rememberGrants, Keyall keyall, List<Rarity> rarities, List<Crate> crates) {
        this(openCooldown, blockInCombat, bulkOpen, quickOpen, joinReminder, rememberGrants, keyall, rarities, crates, Effects.DEFAULTS);
    }

    /** The rank of a rarity, 0 for the most common (and for an unknown one). */
    public int rank(String rarity) {
        for (int i = 0; i < this.rarities.size(); i++) {
            if (this.rarities.get(i).id().equals(rarity)) {
                return i;
            }
        }
        return 0;
    }

    /** The crate of the next tier above this one, or null at the top (the lowest tier when several share it). */
    public Crate nextTier(Crate crate) {
        Crate next = null;
        for (Crate candidate : this.crates) {
            if (candidate.tier() > crate.tier() && (next == null || candidate.tier() < next.tier())) {
                next = candidate;
            }
        }
        return next;
    }

    public Crate crate(String id) {
        if (id == null) {
            return null;
        }
        for (Crate crate : this.crates) {
            if (crate.id().equals(id)) {
                return crate;
            }
        }
        return null;
    }

    public Rarity rarity(String id) {
        for (Rarity rarity : this.rarities) {
            if (rarity.id().equals(id)) {
                return rarity;
            }
        }
        return this.rarities.isEmpty() ? new Rarity(id, id, false, false) : this.rarities.getFirst();
    }

    /** Ids of every crate, in file order. */
    public Set<String> crateIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Crate crate : this.crates) {
            ids.add(crate.id());
        }
        return Set.copyOf(ids);
    }

    // ------------------------------------------------------------------ parsing

    public static CratesSettings parse(ConfigReader r, Catalog catalog, MoneyFormat money) {
        Duration openCooldown = r.duration("open-cooldown", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1));
        boolean blockInCombat = r.bool("block-in-combat", true);
        int bulkOpen = r.integer("bulk-open", 0, MAX_BULK_OPEN, 10);
        boolean quickOpen = r.bool("quick-open", true);
        boolean joinReminder = r.bool("join-reminder", true);
        Duration remember = r.section("grants").duration("remember", Duration.ofDays(1), Duration.ofDays(3650), Duration.ofDays(90));
        List<Rarity> rarities = rarities(r, catalog);
        Set<String> rarityIds = new LinkedHashSet<>();
        for (Rarity rarity : rarities) {
            rarityIds.add(rarity.id());
        }

        Map<String, ConfigReader> sections = r.children("crates");
        if (sections.isEmpty()) {
            r.problem("crates", "has no crates; add at least one");
        }
        Map<String, Draft> drafts = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : sections.entrySet()) {
            String id = entry.getKey();
            ConfigReader c = entry.getValue();
            if (!id.matches(CRATE_ID) || RESERVED.contains(id)) {
                r.problem("crates." + id, RESERVED.contains(id) ? "uses a reserved id; pick another name"
                    : "is not a valid crate id (1 to 32 lowercase letters, digits, - or _)");
                continue;
            }
            Draft draft = crate(id, c, catalog, money, rarityIds, drafts.size() + 1);
            if (draft != null) {
                drafts.put(id, draft);
            }
        }

        // Keys rewards may name any crate, so they are resolved once every crate is known.
        Map<String, String> names = new HashMap<>();
        for (Draft draft : drafts.values()) {
            names.put(draft.id, draft.name);
        }
        List<Crate> crates = new ArrayList<>();
        Map<BlockKey, String> blockOwners = new HashMap<>();
        for (Draft draft : drafts.values()) {
            List<Reward> rewards = new ArrayList<>();
            for (Reward reward : draft.rewards) {
                if (reward.kind() instanceof Reward.Keys keys) {
                    String targetName = names.get(keys.crate());
                    if (targetName == null) {
                        draft.reader.problem("rewards." + reward.id() + ".keys", "names the crate '" + keys.crate()
                            + "', which does not exist (crates: " + String.join(", ", names.keySet()) + ")");
                        continue;
                    }
                    if (!reward.customDisplay()) {
                        reward = new Reward(reward.id(), reward.weight(), reward.rarity(), keysText(keys.amount(), targetName),
                            false, reward.icon(), reward.kind());
                    }
                }
                rewards.add(reward);
            }
            if (rewards.isEmpty()) {
                draft.reader.problem("rewards", "has no working rewards, so the crate is left out");
                continue;
            }
            List<BlockKey> blocks = new ArrayList<>();
            for (BlockKey block : draft.blocks) {
                String owner = blockOwners.putIfAbsent(block, draft.id);
                if (owner != null) {
                    draft.reader.problem("blocks", "lists " + block + ", which is already the " + owner + " crate");
                    continue;
                }
                blocks.add(block);
            }
            crates.add(new Crate(draft.id, draft.name, draft.icon, rewards, blocks, draft.tier, draft.color));
        }
        // Listed from the lowest tier up; crates of one tier keep their file order (the sort is stable). New crates
        // added to an older file land at its end, so the order of the file can't be relied on.
        crates.sort(Comparator.comparingInt(Crate::tier));

        Keyall keyall = keyall(r, crates);
        Effects effects = effects(r);
        return new CratesSettings(openCooldown, blockInCombat, bulkOpen, quickOpen, joinReminder, remember, keyall, rarities, crates,
            effects);
    }

    private static Effects effects(ConfigReader r) {
        if (!r.has("effects")) {
            return Effects.DEFAULTS;
        }
        ConfigReader e = r.section("effects");
        boolean holograms = e.bool("holograms", true);
        double height = e.decimal("hologram-height", 0.0, 3.0, 0.5);
        int range = e.integer("particle-range", 0, 64, 16);
        Animation animation = Animation.DEFAULTS;
        if (e.has("animation")) {
            ConfigReader a = e.section("animation");
            ConfigReader sounds = a.section("sounds", false);
            animation = new Animation(a.bool("enabled", true),
                a.duration("length", Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(4)),
                a.duration("reveal", Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(2)),
                sound(sounds, "tick", Animation.DEFAULTS.tick()),
                sound(sounds, "reveal", Animation.DEFAULTS.revealed()),
                sound(sounds, "big-reveal", Animation.DEFAULTS.bigReveal()));
        }
        return new Effects(holograms, height, range, animation);
    }

    /** One sound: {@code sound}, {@code volume} and {@code pitch}; the shipped one when the section is missing. */
    private static Sound sound(ConfigReader sounds, String name, Sound fallback) {
        if (!sounds.has(name)) {
            return fallback;
        }
        ConfigReader s = sounds.section(name);
        return Sound.sound(s.key("sound", fallback.name()), Sound.Source.MASTER,
            (float) s.decimal("volume", 0.0, 2.0, fallback.volume()), (float) s.decimal("pitch", 0.5, 2.0, fallback.pitch()));
    }

    /** A hex colour like {@code #4DA6FF}, or the fallback after reporting a mistake. */
    private static TextColor color(ConfigReader c, String path, TextColor fallback) {
        if (!c.has(path)) {
            return fallback;
        }
        return c.custom(path, value -> {
            TextColor parsed = TextColor.fromHexString(value.strip());
            if (parsed == null) {
                throw new IllegalArgumentException("is not a hex colour");
            }
            return parsed;
        }, "a hex colour like \"#4DA6FF\"", fallback);
    }

    /** The default text of a keys reward: {@code 1 Rare key}, {@code 3 Basic keys}. */
    static String keysText(int amount, String crateName) {
        return amount + " " + crateName + (amount == 1 ? " key" : " keys");
    }

    private static List<Rarity> rarities(ConfigReader r, Catalog catalog) {
        List<Rarity> rarities = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : r.children("rarities").entrySet()) {
            String id = entry.getKey();
            ConfigReader c = entry.getValue();
            if (!id.matches(RARITY_ID)) {
                r.problem("rarities." + id, "is not a valid rarity id (1 to 16 lowercase letters, digits, - or _)");
                continue;
            }
            String label = plain(c, "label", id, MAX_NAME);
            boolean audit = c.has("audit") ? c.bool("audit", false) : false;
            boolean announce = c.has("announce") ? c.bool("announce", false) : false;
            String glass = c.has("glass") ? item(c, "glass", catalog, null) : null;
            rarities.add(new Rarity(id, label, audit, announce, color(c, "color", Rarity.DEFAULT_COLOR), glass));
        }
        if (rarities.isEmpty()) {
            r.problem("rarities", "has no rarities; add at least one (for example common)");
            rarities.add(new Rarity("common", "Common", false, false));
        }
        return rarities;
    }

    /** A parsed crate whose keys rewards are not resolved yet. */
    private record Draft(String id, String name, String icon, List<Reward> rewards, List<BlockKey> blocks, int tier, TextColor color,
                         ConfigReader reader) {
    }

    /** @param position the crate's place in the file (from 1), its tier when none is set */
    private static Draft crate(String id, ConfigReader c, Catalog catalog, MoneyFormat money, Set<String> rarityIds, int position) {
        for (String key : c.keys()) {
            if (!CRATE_KEYS.contains(key)) {
                c.problem(key, "is not a crate setting (crate settings: name, tier, color, icon, blocks, rewards)");
            }
        }
        String name = plain(c, "name", capitalize(id), MAX_NAME);
        int tier = c.has("tier") ? c.integer("tier", 1, 100, position) : position;
        TextColor color = color(c, "color", Crate.DEFAULT_COLOR);
        String icon = c.has("icon") ? item(c, "icon", catalog, "minecraft:chest") : "minecraft:chest";
        List<BlockKey> blocks = new ArrayList<>();
        for (String text : c.optionalStringList("blocks")) {
            BlockKey block;
            try {
                block = BlockKey.parse(text);
            } catch (IllegalArgumentException e) {
                c.problem("blocks", "has '" + text + "', which " + e.getMessage());
                continue;
            }
            if (!catalog.worlds().contains(block.world())) {
                c.problem("blocks", "has '" + text + "' in the world '" + block.world() + "', which is not loaded (worlds: "
                    + String.join(", ", catalog.worlds()) + ")");
                continue;
            }
            if (blocks.contains(block)) {
                c.problem("blocks", "lists " + block + " twice");
                continue;
            }
            blocks.add(block);
        }
        Map<String, ConfigReader> rewardSections = c.children("rewards");
        if (rewardSections.isEmpty()) {
            c.problem("rewards", "has no rewards, so the crate is left out");
            return null;
        }
        String defaultRarity = rarityIds.iterator().next();
        List<Reward> rewards = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : rewardSections.entrySet()) {
            String rewardId = entry.getKey();
            if (!rewardId.matches(REWARD_ID)) {
                c.problem("rewards." + rewardId, "is not a valid reward id (1 to 32 lowercase letters, digits, - or _)");
                continue;
            }
            Reward reward = reward(rewardId, c, entry.getValue(), catalog, money, rarityIds, defaultRarity);
            if (reward != null) {
                rewards.add(reward);
            }
        }
        return new Draft(id, name, icon, rewards, blocks, tier, color, c);
    }

    private static Reward reward(String id, ConfigReader parent, ConfigReader c, Catalog catalog, MoneyFormat money,
                                 Set<String> rarityIds, String defaultRarity) {
        int before = c.problems().size();
        for (String key : c.keys()) {
            if (!REWARD_KEYS.contains(key)) {
                c.problem(key, "is not a reward setting (typo?)");
            }
        }
        List<String> kinds = new ArrayList<>();
        for (String kind : KINDS) {
            if (c.has(kind)) {
                kinds.add(kind);
            }
        }
        if (kinds.size() != 1) {
            parent.problem("rewards." + id, kinds.isEmpty()
                ? "needs one of item, money, shards, keys, spawner or commands to say what it gives"
                : "has " + String.join(" and ", kinds) + "; a reward gives exactly one kind of thing (make separate rewards)");
            return null;
        }
        double weight = c.decimal("weight", 0.0001, 1_000_000, 1);
        String rarity = defaultRarity;
        if (c.has("rarity")) {
            String value = c.string("rarity", defaultRarity).strip().toLowerCase(Locale.ROOT);
            if (rarityIds.contains(value)) {
                rarity = value;
            } else {
                c.problem("rarity", "is '" + value + "', which is not a rarity (rarities: " + String.join(", ", rarityIds) + ")");
            }
        }
        String display = c.has("display") ? plain(c, "display", id, MAX_DISPLAY) : null;
        String icon = c.has("icon") ? item(c, "icon", catalog, null) : null;

        Reward.Kind kind;
        String generated;
        switch (kinds.getFirst()) {
            case "item" -> {
                String item = item(c, "item", catalog, null);
                int amount = c.has("amount") ? c.integer("amount", 1, MAX_ITEM_AMOUNT, 1) : 1;
                String name = c.has("name") ? plain(c, "name", null, 48) : null;
                List<String> lore = new ArrayList<>();
                List<String> lines = c.optionalStringList("lore");
                if (lines.size() > 8) {
                    c.problem("lore", "has " + lines.size() + " lines; use at most 8");
                }
                for (int i = 0; i < Math.min(8, lines.size()); i++) {
                    String line = lines.get(i);
                    String problem = Ids.plainTextProblem(line);
                    if (problem != null && !line.isEmpty()) {
                        c.problem("lore", "line " + (i + 1) + " " + problem);
                    } else if (line.length() > 80) {
                        c.problem("lore", "line " + (i + 1) + " is longer than 80 characters");
                    } else {
                        lore.add(line);
                    }
                }
                Map<String, Integer> enchants = enchants(c, catalog);
                if (item == null) {
                    return null;
                }
                kind = new Reward.Item(item, amount, name, lore, enchants);
                String label = name != null ? name : Ids.plainName(item);
                generated = amount == 1 ? label : amount + " " + label;
            }
            case "money" -> {
                long amount = c.money("money", money, false, 1);
                kind = new Reward.Money(amount);
                generated = money.formatExact(amount);
            }
            case "shards" -> {
                long amount = c.longValue("shards", 1, 1_000_000_000L, 1);
                kind = new Reward.Shards(amount);
                generated = amount == 1 ? "1 shard" : String.format(Locale.ROOT, "%,d shards", amount);
            }
            case "keys" -> {
                String crate = c.string("keys", "").strip().toLowerCase(Locale.ROOT);
                int amount = c.has("amount") ? c.integer("amount", 1, MAX_KEY_REWARD, 1) : 1;
                kind = new Reward.Keys(crate, amount);
                generated = keysText(amount, crate);
            }
            case "spawner" -> {
                String mob = Ids.normalize(c.string("spawner", ""));
                int amount = c.has("amount") ? c.integer("amount", 1, 64, 1) : 1;
                if (mob == null || !catalog.mobs().contains(mob)) {
                    c.problem("spawner", "is not a mob of this Minecraft version (use ids like zombie or cave_spider)");
                    return null;
                }
                kind = new Reward.Spawner(mob, amount);
                String label = Ids.plainName(mob) + " spawner";
                generated = amount == 1 ? label : amount + " " + label + "s";
            }
            default -> {
                List<String> commands = new ArrayList<>();
                for (String command : c.stringList("commands", List.of())) {
                    String trimmed = command.strip();
                    if (trimmed.startsWith("/")) {
                        trimmed = trimmed.substring(1);
                    }
                    if (trimmed.isEmpty() || trimmed.length() > 256) {
                        c.problem("commands", "has an empty command or one longer than 256 characters");
                        continue;
                    }
                    commands.add(trimmed);
                }
                if (commands.isEmpty()) {
                    c.problem("commands", "has no commands");
                    return null;
                }
                if (display == null) {
                    c.problem("display", "is missing; a command reward needs a display line saying what it gives");
                }
                if (icon == null && !c.has("icon")) {
                    c.problem("icon", "is missing; a command reward needs an icon item for the preview");
                }
                kind = new Reward.Command(commands);
                generated = id;
            }
        }
        if (c.problems().size() > before) {
            return null;
        }
        return new Reward(id, weight, rarity, display != null ? display : generated, display != null, icon, kind);
    }

    private static Map<String, Integer> enchants(ConfigReader c, Catalog catalog) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!c.has("enchants")) {
            return result;
        }
        boolean unsafe = c.has("unsafe-enchants") && c.bool("unsafe-enchants", false);
        ConfigReader section = c.section("enchants");
        for (String key : section.keys()) {
            String enchant = Ids.normalize(key);
            Integer max = enchant == null ? null : catalog.enchantments().get(enchant);
            if (max == null) {
                section.problem(key, "is not an enchantment of this Minecraft version");
                continue;
            }
            int level = section.integer(key, 1, unsafe ? 255 : Math.max(1, max), 1);
            result.put(enchant, level);
        }
        return result;
    }

    private static String item(ConfigReader c, String path, Catalog catalog, String fallback) {
        String raw = c.string(path, "");
        String item = Ids.normalize(raw);
        if (item == null || !catalog.items().contains(item)) {
            c.problem(path, "is '" + raw + "', which is not an item of this Minecraft version");
            return fallback;
        }
        return item;
    }

    private static String plain(ConfigReader c, String path, String fallback, int maxLength) {
        if (!c.has(path) && fallback != null) {
            return fallback;
        }
        String text = c.string(path, fallback == null ? "" : fallback).strip();
        String problem = Ids.plainTextProblem(text);
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

    private static String capitalize(String id) {
        String text = id.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Keyall keyall(ConfigReader r, List<Crate> crates) {
        ConfigReader k = r.section("keyall");
        boolean enabled = k.bool("enabled", false);
        Duration interval = k.duration("interval", Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofHours(4));
        String crate = k.string("crate", "").strip().toLowerCase(Locale.ROOT);
        int amount = k.integer("amount", 1, MAX_KEY_REWARD, 1);
        Duration missed = k.duration("missed-delay", Duration.ZERO, Duration.ofHours(1), Duration.ofMinutes(10));
        boolean includeVanished = k.bool("include-vanished", false);
        boolean includeAfk = k.bool("include-afk", true);
        ConfigReader countdown = k.section("countdown");
        List<Duration> chatAt = new ArrayList<>();
        for (String text : countdown.stringList("chat", List.of())) {
            Duration at;
            try {
                at = Durations.parse(text);
            } catch (IllegalArgumentException | ArithmeticException e) {
                countdown.problem("chat", "has '" + text + "', which is not a duration like 5m or 30s");
                continue;
            }
            if (at.compareTo(Duration.ofSeconds(10)) < 0 || at.compareTo(interval) >= 0) {
                countdown.problem("chat", "has " + text + "; announcements must be at least 10s and shorter than the interval");
                continue;
            }
            if (!chatAt.contains(at)) {
                chatAt.add(at);
            }
        }
        chatAt.sort(java.util.Comparator.reverseOrder());
        Duration actionBar = countdown.duration("action-bar", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(10));
        boolean known = false;
        for (Crate candidate : crates) {
            known |= candidate.id().equals(crate);
        }
        if (!known) {
            k.problem("crate", "is '" + crate + "', which is not a crate, so the keyall is off");
            return new Keyall(false, interval, crate, amount, missed, includeVanished, includeAfk, chatAt, actionBar);
        }
        return new Keyall(enabled, interval, crate, amount, missed, includeVanished, includeAfk, chatAt, actionBar);
    }
}
