package net.siftvanilla.siftcore.core.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of every registered setting. {@link PlayerSettings} publishes a new snapshot on each
 * registration (copy on write), so readers never lock and always see a consistent registry.
 *
 * @param categories the categories holding at least one setting, lowest order first
 * @param byId       every setting by id, in registration order
 * @param byCategory each category's settings, by {@link SettingOptions#order()} and then registration order
 * @param legacyIds  old setting ids claimed through {@link SettingOptions.Legacy}, to the id that claims them
 */
public record Registry(List<SettingCategory> categories, Map<String, Entry<?>> byId, Map<String, List<Entry<?>>> byCategory,
                       Map<String, String> legacyIds) {

    /** No settings. */
    public static final Registry EMPTY = new Registry(List.of(), Map.of(), Map.of(), Map.of());

    /**
     * A registered setting.
     *
     * @param setting    the setting
     * @param category   its category ({@link SettingCategories#GENERAL} when registered without one)
     * @param options    how it behaves
     * @param shortName  the id without a leading {@code <category id>-} or {@code <category id>_}, e.g. {@code volume}
     *                   for {@code sound-volume} in the sound category (what {@code /settings sound volume} takes)
     * @param inputKey   its dialog input key: the id with every character other than letters and digits turned into
     *                   {@code _}, made unique
     * @param index      registration order
     * @param superseded true while one of its legacy ids is still registered as a setting of its own: the old setting
     *                   stays in charge, this one is not offered and nothing is migrated until the old one is retired
     */
    public record Entry<T>(PlayerSetting<T> setting, SettingCategory category, SettingOptions<T> options, String shortName,
                           String inputKey, int index, boolean superseded) {

        public String id() {
            return this.setting.id();
        }

        /** Whether placeholders may expose the value. */
        public boolean placeholder() {
            return this.options.placeholder() != null ? this.options.placeholder() : this.setting.permission() == null;
        }

        /**
         * Whether the dialog and commands offer it at all (listed, not superseded, available). Permissions and the
         * server's {@code hidden} list are checked separately.
         */
        public boolean offered() {
            return this.options.listed() && !this.superseded && this.options.isAvailable();
        }

        Entry<T> with(String inputKey, boolean superseded) {
            return new Entry<>(this.setting, this.category, this.options, this.shortName, inputKey, this.index, superseded);
        }
    }

    public Registry {
        categories = List.copyOf(categories);
        byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        Map<String, List<Entry<?>>> lists = new LinkedHashMap<>();
        byCategory.forEach((id, list) -> lists.put(id, List.copyOf(list)));
        byCategory = java.util.Collections.unmodifiableMap(lists);
        legacyIds = Map.copyOf(legacyIds);
    }

    /** The setting with this id, or null. */
    public Entry<?> entry(String id) {
        return id == null ? null : this.byId.get(id);
    }

    /** The category with this id that holds settings, or null. */
    public SettingCategory category(String id) {
        for (SettingCategory category : this.categories) {
            if (category.id().equalsIgnoreCase(id)) {
                return category;
            }
        }
        return null;
    }

    /** One category's settings in dialog order (empty when it holds none). */
    public List<Entry<?>> in(String categoryId) {
        return this.byCategory.getOrDefault(categoryId, List.of());
    }

    /** Every setting in dialog order: category by category. */
    public List<Entry<?>> entries() {
        List<Entry<?>> all = new ArrayList<>(this.byId.size());
        for (SettingCategory category : this.categories) {
            all.addAll(in(category.id()));
        }
        return all;
    }

    /**
     * A setting by what a player typed: its id, its dialog input key, or (within {@code categoryId}, when given) its
     * short name. Case-insensitive; null when nothing matches.
     */
    public Entry<?> find(String word, String categoryId) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String lower = word.strip().toLowerCase(Locale.ROOT);
        Entry<?> exact = this.byId.get(lower);
        if (exact != null && (categoryId == null || exact.category().id().equals(categoryId))) {
            return exact;
        }
        List<Entry<?>> scope = categoryId == null ? List.copyOf(this.byId.values()) : in(categoryId);
        for (Entry<?> entry : scope) {
            if (entry.shortName().equals(lower) && categoryId != null) {
                return entry;
            }
        }
        for (Entry<?> entry : scope) {
            if (entry.inputKey().equalsIgnoreCase(lower)) {
                return entry;
            }
        }
        return null;
    }

    /** Every setting's short name: the id without its category's prefix. */
    static String shortName(String id, String categoryId) {
        for (String separator : List.of("-", "_")) {
            String prefix = categoryId + separator;
            if (id.startsWith(prefix) && id.length() > prefix.length()) {
                return id.substring(prefix.length());
            }
        }
        return id;
    }

    /** A setting id as a dialog input key (letters, digits and {@code _} only). */
    public static String inputKey(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ? c : '_');
        }
        return sb.isEmpty() ? "_" : sb.toString();
    }

    /**
     * This registry plus one setting. Throws when the setting breaks a rule: a used id, a category id standing for a
     * different category, a reserved category id, a missing label, a short name that collides inside the category,
     * option rules naming unknown options, or a legacy id another setting already claims.
     */
    public <T> Registry with(PlayerSetting<T> setting, SettingCategory category, SettingOptions<T> options) {
        Objects.requireNonNull(setting);
        Objects.requireNonNull(category);
        Objects.requireNonNull(options);
        String id = setting.id();
        if (this.byId.containsKey(id)) {
            throw new IllegalStateException("Setting " + id + " is registered twice");
        }
        if (setting.label() == null || setting.description() == null) {
            throw new IllegalArgumentException("Setting " + id + " needs a label and a description");
        }
        if (SettingCategory.RESERVED.contains(category.id())) {
            throw new IllegalArgumentException("Setting category id " + category.id() + " is reserved");
        }
        SettingCategory known = categoryById(category.id());
        if (known != null && !known.equals(category)) {
            throw new IllegalStateException("Setting category " + category.id() + " is registered twice with different text");
        }
        if (!options.optionAvailable().isEmpty()) {
            if (!(setting instanceof Choice<?> choice)) {
                throw new IllegalArgumentException("Setting " + id + " has option rules but no options");
            }
            for (String option : options.optionAvailable().keySet()) {
                if (!choice.optionIds().contains(option)) {
                    throw new IllegalArgumentException("Setting " + id + " has a rule for an unknown option " + option);
                }
            }
        }
        Map<String, String> legacyIds = new HashMap<>(this.legacyIds);
        for (SettingOptions.Legacy legacy : options.legacy()) {
            if (legacy.oldId().equals(id)) {
                throw new IllegalArgumentException("Setting " + id + " names itself as a legacy id");
            }
            String claimed = legacyIds.putIfAbsent(legacy.oldId(), id);
            if (claimed != null) {
                throw new IllegalStateException("Legacy id " + legacy.oldId() + " is claimed by " + claimed + " and " + id);
            }
        }
        String shortName = shortName(id, category.id());
        for (Entry<?> other : in(category.id())) {
            if (other.shortName().equals(shortName) || other.id().equals(shortName) || other.shortName().equals(id)) {
                throw new IllegalStateException("Setting " + id + " and " + other.id() + " share the name " + shortName
                    + " in category " + category.id());
            }
        }
        Map<String, Entry<?>> byId = new LinkedHashMap<>(this.byId);
        byId.put(id, new Entry<>(setting, category, options, shortName, "", byId.size(), false));
        return rebuild(byId, legacyIds);
    }

    private SettingCategory categoryById(String id) {
        for (Entry<?> entry : this.byId.values()) {
            if (entry.category().id().equals(id)) {
                return entry.category();
            }
        }
        return null;
    }

    private static Registry rebuild(Map<String, Entry<?>> raw, Map<String, String> legacyIds) {
        Map<String, Entry<?>> byId = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (Entry<?> entry : raw.values()) {
            String base = inputKey(entry.id());
            String key = base;
            for (int n = 2; !keys.add(key); n++) {
                key = base + "_" + n;
            }
            boolean superseded = false;
            for (SettingOptions.Legacy legacy : entry.options().legacy()) {
                superseded |= raw.containsKey(legacy.oldId());
            }
            byId.put(entry.id(), entry.with(key, superseded));
        }
        Map<String, SettingCategory> categories = new LinkedHashMap<>();
        Map<String, List<Entry<?>>> byCategory = new HashMap<>();
        for (Entry<?> entry : byId.values()) {
            categories.putIfAbsent(entry.category().id(), entry.category());
            byCategory.computeIfAbsent(entry.category().id(), k -> new ArrayList<>()).add(entry);
        }
        List<SettingCategory> sorted = new ArrayList<>(categories.values());
        sorted.sort(Comparator.comparingInt(SettingCategory::order).thenComparing(SettingCategory::id));
        Map<String, List<Entry<?>>> ordered = new LinkedHashMap<>();
        for (SettingCategory category : sorted) {
            List<Entry<?>> list = byCategory.get(category.id());
            list.sort(Comparator.<Entry<?>>comparingInt(e -> e.options().order()).thenComparingInt(Entry::index));
            ordered.put(category.id(), list);
        }
        return new Registry(sorted, byId, ordered, legacyIds);
    }
}
