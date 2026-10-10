package net.siftvanilla.siftcore.core.player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.storage.Database;
import org.bukkit.entity.Player;

/**
 * Every player's settings: the registry of settings features contribute (toggles, choices and numbers, grouped in
 * {@link SettingCategories}) and each online player's stored values.
 * <p>
 * <b>Storage.</b> Values are loaded when a player logs in (off-thread, before they enter the world), changed through
 * this class, written through at once and forgotten when they quit (a reconnect that replaced the session keeps the
 * new login's values). A row holds only a value that differs from the default: storing the default deletes the row,
 * so the player follows the server's default from then on (and a later change of that default reaches them). Rows
 * for ids nobody registered (disabled features, UI state such as a remembered sort order) are kept untouched.
 * <p>
 * <b>Reads</b> are lock-free and safe on any thread (region threads, the async chat thread, placeholder threads,
 * database callbacks): a value resolves as the server's lock, else the stored row, else the server's default, else
 * the code default, and a choice option that is not available now reads as its fallback. Players who are not loaded
 * read as defaults; {@link #lookup} reads one setting of an offline player from the database.
 * <p>
 * <b>Writes</b> go through {@link #set}: locked settings refuse, unchanged values write nothing, listeners may cancel
 * a player's own change (see {@link Change.Cause#cancellable()}), and {@link SettingOptions.Apply#INSTANT} settings
 * run their hook on the player's thread afterwards.
 */
public final class PlayerSettings {

    /** A change about to be stored, as a {@link ChangeListener} sees it. */
    public record Pending(UUID player, Registry.Entry<?> entry, String oldValue, String newValue, Change change) {
    }

    /**
     * Told about every change before it is stored (the composition root fires {@code SettingChangeEvent} from it).
     * Returning false cancels the change when its cause is {@linkplain Change.Cause#cancellable() cancellable}.
     */
    @FunctionalInterface
    public interface ChangeListener {
        boolean changing(Pending change);
    }

    /**
     * The settings dialog, bound late by the settings feature, so features built before it can open a category of
     * settings (for example the friends menu opening the social settings).
     */
    @FunctionalInterface
    public interface SettingsScreens {

        SettingsScreens NONE = (player, category, back) -> false;

        /**
         * Opens a category's settings page; {@code back} runs on Back (null shows Close). Returns false (and shows
         * nothing) when the player sees no setting in that category. Call on the player's thread.
         */
        boolean open(Player player, String category, Consumer<Player> back);
    }

    /** One row moved from a legacy id when a player loads: the new id, its new value (null: default, no row), the old id. */
    record Migration(String id, String value, String oldId) {
    }

    /** One login of a player: its read of their rows, put in place at most once. */
    private static final class Session {
        private volatile boolean installed;
    }

    /** The registry with the server's overrides decoded against it (rebuilt when either changes). */
    private record State(Registry registry, Overrides overrides, Map<String, Object> locked, Map<String, Object> defaults) {
    }

    private final Database database;
    private final Scheduler scheduler;
    private final Logger logger;
    private final Object registration = new Object();
    private final Object offlineWrites = new Object();
    private final Map<UUID, Map<String, String>> values = new ConcurrentHashMap<>();
    /** The newest login of each player (one per {@link #load}); a login read that is not the newest is dropped. */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** The login each online session joined with ({@link #joined}), so its quit leaves a newer login alone. */
    private final Map<UUID, Session> joined = new ConcurrentHashMap<>();
    /**
     * Ids changed in a map made for an online player whose login read had not arrived (failed or slow): when it
     * arrives late it fills in the rest without undoing these.
     */
    private final Map<UUID, Set<String>> provisional = new ConcurrentHashMap<>();
    private final Set<String> readers = ConcurrentHashMap.newKeySet();
    private volatile State state = new State(Registry.EMPTY, Overrides.NONE, Map.of(), Map.of());
    private volatile ChangeListener listener = change -> true;
    private volatile Function<UUID, Player> players = uuid -> null;
    private volatile SettingsScreens screens = SettingsScreens.NONE;

    public PlayerSettings(Database database, Scheduler scheduler, Logger logger) {
        this.database = database;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ wiring

    /** Sets who hears about changes before they are stored. */
    public void listener(ChangeListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    /** Sets how online players are found (for change hooks and the change listener). */
    public void players(Function<UUID, Player> players) {
        this.players = Objects.requireNonNull(players);
    }

    /** The settings dialog ({@link SettingsScreens#NONE} until the settings feature binds it). */
    public SettingsScreens screens() {
        return (player, category, back) -> this.screens.open(player, category, back);
    }

    /** Binds the settings dialog. */
    public void screens(SettingsScreens screens) {
        this.screens = Objects.requireNonNull(screens);
    }

    // ------------------------------------------------------------------ registration

    /**
     * Registers a setting in a category; normally from a feature constructor, but any thread at any time is fine (the
     * registry is copy-on-write). Throws when the setting breaks a registry rule (see {@link Registry#with}).
     */
    public <T> Registry.Entry<T> register(SettingCategory category, PlayerSetting<T> setting, SettingOptions<T> options) {
        synchronized (this.registration) {
            State current = this.state;
            Registry registry = current.registry().with(setting, category, options);
            this.state = resolve(registry, current.overrides());
            return typed(registry, setting);
        }
    }

    /** Registers a setting in a category with default options. */
    public <T> Registry.Entry<T> register(SettingCategory category, PlayerSetting<T> setting) {
        return register(category, setting, SettingOptions.defaults());
    }

    /** Registers a setting in the {@link SettingCategories#GENERAL} group. */
    public <T> Registry.Entry<T> register(PlayerSetting<T> setting) {
        return register(SettingCategories.GENERAL, setting, SettingOptions.defaults());
    }

    /**
     * Declares that a feature acts on a shared setting (one of {@link SharedSettings} that only takes effect in a
     * feature's code). Such a setting is offered from the first declaration on, so the dialog never shows a switch
     * that nothing reads. Call it where the reading is wired, normally in the feature's constructor.
     */
    public void reads(PlayerSetting<?> setting) {
        this.readers.add(setting.id());
    }

    /** Whether a feature declared that it acts on the setting ({@link #reads}). */
    public boolean hasReader(PlayerSetting<?> setting) {
        return this.readers.contains(setting.id());
    }

    /** The current registry snapshot. */
    public Registry registry() {
        return this.state.registry();
    }

    /** The server's overrides now in force. */
    public Overrides overrides() {
        return this.state.overrides();
    }

    /** The registered setting with this id, or null. */
    public PlayerSetting<?> setting(String id) {
        Registry.Entry<?> entry = this.state.registry().entry(id);
        return entry == null ? null : entry.setting();
    }

    /** The registered toggle with this id, or null (also null when the id is a choice or a number). */
    public Toggle toggle(String id) {
        return setting(id) instanceof Toggle toggle ? toggle : null;
    }

    /** Every registered toggle in registration order. */
    public List<Toggle> toggles() {
        List<Toggle> list = new ArrayList<>();
        for (Registry.Entry<?> entry : this.state.registry().byId().values()) {
            if (entry.setting() instanceof Toggle toggle) {
                list.add(toggle);
            }
        }
        return list;
    }

    /** Every category that holds a setting, lowest {@link SettingCategory#order()} first. */
    public List<SettingCategory> categories() {
        return this.state.registry().categories();
    }

    /** The category a setting is registered in, or null when it is not registered. */
    public SettingCategory category(PlayerSetting<?> setting) {
        Registry.Entry<?> entry = this.state.registry().entry(setting.id());
        return entry == null ? null : entry.category();
    }

    // ------------------------------------------------------------------ config overrides

    /**
     * Applies the server's defaults, locks and hidden list (validated by the settings feature first). Entries for ids
     * that register later take effect when they do. {@link SettingOptions.Apply#INSTANT} settings whose value changes
     * for an online player because of this run their hook.
     */
    public void overrides(Overrides overrides) {
        State before;
        State after;
        synchronized (this.registration) {
            before = this.state;
            after = resolve(before.registry(), Objects.requireNonNull(overrides));
            this.state = after;
        }
        for (Registry.Entry<?> entry : after.registry().byId().values()) {
            if (entry.options().onChange() != null) {
                for (UUID player : this.values.keySet()) {
                    hookIfChanged(entry, player, before, after);
                }
            }
        }
    }

    private <T> void hookIfChanged(Registry.Entry<T> entry, UUID player, State before, State after) {
        String stored = storedValue(player, entry.id());
        T old = resolve(entry, stored, before);
        T now = resolve(entry, stored, after);
        if (!entry.setting().same(old, now)) {
            hook(entry, player, old, now);
        }
    }

    /** Whether the server locked the setting to a value. */
    public boolean locked(PlayerSetting<?> setting) {
        return this.state.locked().containsKey(setting.id());
    }

    /**
     * Whether the server hides the setting from the dialog, commands and placeholders. A hidden setting is the
     * server's: everyone reads its lock, else the server default, else the code default, and nobody can change it.
     * Stored values are kept (ignored while hidden), so they come back if the server shows the setting again.
     */
    public boolean hidden(PlayerSetting<?> setting) {
        return this.state.overrides().hidden().contains(setting.id());
    }

    private static State resolve(Registry registry, Overrides overrides) {
        Map<String, Object> locked = new HashMap<>();
        Map<String, Object> defaults = new HashMap<>();
        overrides.locked().forEach((id, raw) -> {
            Object value = configValue(registry.entry(id), raw);
            if (value != null) {
                locked.put(id, value);
            }
        });
        overrides.defaults().forEach((id, raw) -> {
            Object value = configValue(registry.entry(id), raw);
            if (value != null) {
                defaults.put(id, value);
            }
        });
        return new State(registry, overrides, Map.copyOf(locked), Map.copyOf(defaults));
    }

    /**
     * A config value for a setting: a toggle word, an option id (or legacy value) of a choice, or a number that is in
     * range and on a step. Null when the entry is unknown or the text is not a value of it.
     */
    public static Object configValue(Registry.Entry<?> entry, String raw) {
        if (entry == null || raw == null) {
            return null;
        }
        if (entry.setting() instanceof NumberSetting number) {
            return number.parseExact(raw);
        }
        return entry.setting().decodeOrNull(raw);
    }

    // ------------------------------------------------------------------ reads

    /**
     * A player's effective value: the server's lock, else the stored value (unless the server hides the setting),
     * else the server default, else the code default; an option that is not available now falls back.
     */
    public <T> T get(UUID player, PlayerSetting<T> setting) {
        State current = this.state;
        Registry.Entry<T> entry = typed(current.registry(), setting);
        String stored = storedValue(player, setting.id());
        if (entry == null) {
            T decoded = stored == null ? null : setting.decodeOrNull(stored);
            return decoded != null ? decoded : setting.defaultValue();
        }
        return resolve(entry, stored, current);
    }

    /**
     * A player's effective value with their permissions applied: without the setting's permission they read the
     * default (so a de-ranked player loses a perk at once), and an option they lack the permission for reads as its
     * fallback. Permission checks are thread-safe; prefer the player's thread.
     */
    public <T> T get(Player player, PlayerSetting<T> setting) {
        State current = this.state;
        Registry.Entry<T> entry = typed(current.registry(), setting);
        if (entry == null) {
            return get(player.getUniqueId(), setting);
        }
        Predicate<String> permissions = player::hasPermission;
        if (setting.permission() != null && !player.hasPermission(setting.permission())) {
            Object locked = current.locked().get(setting.id());
            T value = locked != null ? setting.cast(locked) : fallback(entry, current);
            return available(entry, value, current, permissions);
        }
        return available(entry, value(entry, storedValue(player.getUniqueId(), setting.id()), current), current, permissions);
    }

    /** Whether a toggle is on for a player (unknown players read the default). */
    public boolean enabled(UUID player, Toggle toggle) {
        return get(player, toggle);
    }

    /** A number setting's value. */
    public long number(UUID player, NumberSetting setting) {
        return get(player, setting);
    }

    /** The effective value of a registered setting in its stored form, or null when the id is unknown. */
    public String encoded(UUID player, String id) {
        Registry.Entry<?> entry = this.state.registry().entry(id);
        return entry == null ? null : encodedValue(entry, player);
    }

    private <T> String encodedValue(Registry.Entry<T> entry, UUID player) {
        return entry.setting().encode(get(player, entry.setting()));
    }

    /** The effective value of a registered setting as players read it ("On", "Everyone", "30 %"), or null. */
    public String display(UUID player, String id, Lang lang) {
        Registry.Entry<?> entry = this.state.registry().entry(id);
        return entry == null ? null : displayValue(entry, player, lang);
    }

    private <T> String displayValue(Registry.Entry<T> entry, UUID player, Lang lang) {
        return entry.setting().display(lang, get(player, entry.setting()));
    }

    /** What a player who never changed the setting reads: the server's lock, else its default, else the code default. */
    public <T> T defaultValue(PlayerSetting<T> setting) {
        State current = this.state;
        Object locked = current.locked().get(setting.id());
        if (locked != null) {
            return setting.cast(locked);
        }
        Object configured = current.defaults().get(setting.id());
        return configured != null ? setting.cast(configured) : setting.defaultValue();
    }

    /**
     * Whether the player chose something other than the default: a stored row whose value differs from the
     * effective default (rows equal to the default, left from before defaults deleted rows, count as unchanged).
     */
    public boolean changed(UUID player, PlayerSetting<?> setting) {
        return changedValue(player, setting);
    }

    private <T> boolean changedValue(UUID player, PlayerSetting<T> setting) {
        State current = this.state;
        if (current.locked().containsKey(setting.id()) || current.overrides().hidden().contains(setting.id())) {
            return false;
        }
        String stored = storedValue(player, setting.id());
        T decoded = stored == null ? null : setting.decodeOrNull(stored);
        Registry.Entry<T> entry = typed(current.registry(), setting);
        T fallback = entry == null ? setting.defaultValue() : fallback(entry, current);
        return decoded != null && !setting.same(decoded, fallback);
    }

    /** Whether a player's settings are loaded (they are online, or logging in). */
    public boolean loaded(UUID player) {
        return this.values.containsKey(player);
    }

    /**
     * One setting of any player: from memory while they are loaded, otherwise (or while their login read is still
     * missing) from one database read decoded with the server's locks and defaults. Use it for offline players (for
     * example who may see an offline player's balance).
     */
    public <T> CompletableFuture<T> lookup(UUID player, PlayerSetting<T> setting) {
        if (this.values.containsKey(player) && !this.provisional.containsKey(player)) {
            return CompletableFuture.completedFuture(get(player, setting));
        }
        Registry.Entry<T> entry = typed(this.state.registry(), setting);
        List<String> ids = new ArrayList<>();
        ids.add(setting.id());
        if (entry != null) {
            ids.addAll(oldIds(entry));
        }
        // Read in the writer's order (after every write queued so far), so a change made just before the player left is
        // seen: the read pool alone could run ahead of a write that is not committed yet.
        return this.database.write(c -> {
            Map<String, String> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT setting, value FROM settings WHERE uuid = ? AND setting IN ("
                + "?, ".repeat(ids.size() - 1) + "?)")) {
                ps.setString(1, player.toString());
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(i + 2, ids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return rows;
        }).thenApply(rows -> {
            State current = this.state;
            Registry.Entry<T> now = typed(current.registry(), setting);
            if (now == null) {
                String stored = rows.get(setting.id());
                T decoded = stored == null ? null : setting.decodeOrNull(stored);
                return decoded != null ? decoded : setting.defaultValue();
            }
            String stored = rows.get(setting.id());
            if (stored == null) {
                for (Migration migration : migrations(rows, current.registry(), id -> null)) {
                    if (migration.id().equals(setting.id())) {
                        stored = migration.value();
                    }
                }
            }
            return resolve(now, stored, current);
        });
    }

    /**
     * Every stored row of a player (settings and UI state), read from the database after every write queued so far;
     * for staff tools and the API.
     */
    public CompletableFuture<Map<String, String>> stored(UUID player) {
        return this.database.write(c -> readRows(c, player));
    }

    // ------------------------------------------------------------------ writes

    /**
     * Changes a player's setting without permission checks (features, staff, the API). Works for players who are not
     * loaded too: the row is written (or deleted when the value is the default), rows under the setting's old ids
     * ({@link SettingOptions#legacy}) are deleted with it so the next login can't move them over the change, and the
     * change applies when they join; the listener then sees no old value and nothing reports
     * {@link SetResult#UNCHANGED}. A locked setting refuses
     * ({@link SetResult#LOCKED}), and so does one the server hides ({@link SetResult#NOT_ALLOWED}: its value is the
     * server's, see {@link #hidden}).
     */
    public <T> SetResult set(UUID player, PlayerSetting<T> setting, T value, Change change) {
        State current = this.state;
        Registry.Entry<T> entry = typed(current.registry(), setting);
        if (entry == null) {
            return SetResult.UNKNOWN;
        }
        if (value == null || !setting.valid(value)) {
            return SetResult.INVALID;
        }
        if (current.locked().containsKey(setting.id())) {
            return SetResult.LOCKED;
        }
        if (current.overrides().hidden().contains(setting.id())) {
            return SetResult.NOT_ALLOWED;
        }
        return store(player, entry, value, change);
    }

    /**
     * Changes a setting for a player as they would themselves: refused ({@link SetResult#NOT_ALLOWED}) when they lack
     * its permission or the option's, when the option or setting is not offered now, or when the server hides it.
     */
    public <T> SetResult set(Player player, PlayerSetting<T> setting, T value, Change change) {
        State current = this.state;
        Registry.Entry<T> entry = typed(current.registry(), setting);
        if (entry == null) {
            return SetResult.UNKNOWN;
        }
        if (value == null || !setting.valid(value)) {
            return SetResult.INVALID;
        }
        if (!allowed(entry, player::hasPermission, current) || !optionAllowed(entry, value, player::hasPermission)) {
            return SetResult.NOT_ALLOWED;
        }
        return set(player.getUniqueId(), setting, value, change);
    }

    /** The classic switch flip used by feature commands such as {@code /msgtoggle}. */
    public void set(UUID player, Toggle toggle, boolean on) {
        set(player, toggle, on, Change.feature());
    }

    /**
     * Changes a setting from typed text (commands, the API): a toggle takes on/off/true/false/yes/no or
     * {@code toggle} (flip), a choice takes an option id, a number takes a whole number in range and on a step.
     * {@code id} may be the setting id or its dialog input key. No permission checks (see the {@link Player} form).
     */
    public SetResult setParsed(UUID player, String id, String input, Change change) {
        Registry.Entry<?> entry = this.state.registry().find(id, null);
        return entry == null ? SetResult.UNKNOWN : setParsed(entry, player, null, input, change);
    }

    /** {@link #setParsed(UUID, String, String, Change)} with the player's permissions applied. */
    public SetResult setParsed(Player player, String id, String input, Change change) {
        Registry.Entry<?> entry = this.state.registry().find(id, null);
        return entry == null ? SetResult.UNKNOWN : setParsed(entry, player.getUniqueId(), player, input, change);
    }

    private <T> SetResult setParsed(Registry.Entry<T> entry, UUID uuid, Player player, String input, Change change) {
        T value = parse(entry.setting(), input, get(uuid, entry.setting()));
        if (value == null) {
            return SetResult.INVALID;
        }
        return player == null ? set(uuid, entry.setting(), value, change) : set(player, entry.setting(), value, change);
    }

    /**
     * Typed text as a value of the setting, or null: toggles take on/off words or {@code toggle} (the opposite of
     * {@code current}), choices an option id or legacy value, numbers an exact whole number in range and on a step.
     */
    public static <T> T parse(PlayerSetting<T> setting, String input, T current) {
        if (input == null) {
            return null;
        }
        return switch (setting) {
            case Toggle toggle -> {
                Boolean on = "toggle".equalsIgnoreCase(input.strip())
                    ? Boolean.valueOf(!Boolean.TRUE.equals(current)) : Toggle.parse(input);
                yield setting.cast(on);
            }
            case NumberSetting number -> setting.cast(number.parseExact(input));
            case Choice<T> choice -> choice.decodeOrNull(input);
        };
    }

    /**
     * Puts settings back to the default (deletes their rows), reporting one {@link Change.Cause#RESET} change per
     * setting whose value really changes (whatever cause {@code change} names, only its actor is kept: a reset can't be
     * cancelled). Locked settings are skipped. Listeners hear about the changes before anything is removed, outside
     * any lock. Returns how many settings changed; for a player who is not loaded the rows (and those under the
     * settings' old ids) are deleted without reports and 0 is returned.
     */
    public int reset(UUID player, Collection<? extends PlayerSetting<?>> settings, Change change) {
        Change reset = change.cause() == Change.Cause.RESET ? change : Change.reset(change.actor());
        State current = this.state;
        List<Registry.Entry<?>> entries = new ArrayList<>();
        for (PlayerSetting<?> setting : settings) {
            Registry.Entry<?> entry = current.registry().entry(setting.id());
            if (entry != null && !current.locked().containsKey(setting.id())) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return 0;
        }
        Map<String, String> map = this.values.get(player);
        if (map == null) {
            // Rows under the settings' old ids go too, or the next login would move them over the reset.
            List<String> ids = new ArrayList<>();
            for (Registry.Entry<?> entry : entries) {
                ids.add(entry.id());
                ids.addAll(oldIds(entry));
            }
            Map<String, String> removed = new HashMap<>();
            ids.forEach(id -> removed.put(id, null));
            CompletableFuture<Void> deleted;
            synchronized (this.offlineWrites) {
                deleted = deleteRows(player, ids);
            }
            // A login may have read the rows before the delete committed: bring its cache up to date.
            deleted.thenRun(() -> applyCommitted(player, removed));
            return 0;
        }
        for (Registry.Entry<?> entry : entries) {
            reportReset(player, entry, map.get(entry.id()), current, reset);
        }
        List<String> ids = new ArrayList<>();
        List<Runnable> hooks = new ArrayList<>();
        int changed = 0;
        // The cache and the queued delete change together, so a concurrent change can't land between them.
        synchronized (map) {
            // Before the player's login read arrived the cache doesn't know every row: delete all the given ones.
            boolean partial = this.provisional.containsKey(player);
            for (Registry.Entry<?> entry : entries) {
                int result = resetOne(player, entry, map, current, hooks);
                if (result >= 0 || partial) {
                    ids.add(entry.id());
                    touch(player, entry.id());
                }
                if (result > 0) {
                    changed++;
                }
            }
            if (!ids.isEmpty()) {
                deleteRows(player, ids);
            }
        }
        hooks.forEach(Runnable::run);
        return changed;
    }

    /** Tells the listener that a reset will change a setting (nothing when the stored value already is the default). */
    private <T> void reportReset(UUID player, Registry.Entry<T> entry, String stored, State current, Change reset) {
        if (stored == null) {
            return;
        }
        T old = resolve(entry, stored, current);
        T now = resolve(entry, null, current);
        if (!entry.setting().same(old, now)) {
            // Only reported: a reset is not cancellable, so the listener's answer does not matter.
            this.listener.changing(new Pending(player, entry, entry.setting().encode(old), entry.setting().encode(now), reset));
        }
    }

    /** Removes one row from the cache (under its lock). -1: no row; 0: the row went, the value stays; 1: it changed. */
    private <T> int resetOne(UUID player, Registry.Entry<T> entry, Map<String, String> map, State current, List<Runnable> hooks) {
        String stored = map.remove(entry.id());
        if (stored == null) {
            return -1;
        }
        T old = resolve(entry, stored, current);
        T now = resolve(entry, null, current);
        if (entry.setting().same(old, now)) {
            return 0;
        }
        hooks.add(() -> hook(entry, player, old, now));
        return 1;
    }

    /** A free-form per-player value (for example a remembered sort order). A registered id reads its effective value. */
    public String raw(UUID player, String key, String fallback) {
        Registry.Entry<?> entry = this.state.registry().entry(key);
        if (entry != null) {
            return encodedValue(entry, player);
        }
        String value = storedValue(player, key);
        return value == null ? fallback : value;
    }

    /**
     * Stores a free-form value (at most 32 characters of key, 64 of value). A registered id is set like a feature
     * change, and a value that setting can't read throws {@link IllegalArgumentException}.
     */
    public void setRaw(UUID player, String key, String value) {
        Registry.Entry<?> entry = this.state.registry().entry(key);
        if (entry != null) {
            setRegistered(entry, player, value);
            return;
        }
        if (key.length() > 32 || value.length() > PlayerSetting.MAX_ENCODED) {
            throw new IllegalArgumentException("Setting too long");
        }
        Map<String, String> map = cache(player);
        if (map != null) {
            synchronized (map) {
                map.put(key, value);
                touch(player, key);
                write(player, key, value);
            }
        } else {
            CompletableFuture<Void> written;
            synchronized (this.offlineWrites) {
                written = write(player, key, value);
            }
            written.thenRun(() -> applyCommitted(player, Map.of(key, value)));
        }
    }

    private <T> void setRegistered(Registry.Entry<T> entry, UUID player, String value) {
        T decoded = entry.setting().decodeOrNull(value);
        if (decoded == null) {
            throw new IllegalArgumentException(value + " is not a value of " + entry.id());
        }
        set(player, entry.setting(), decoded, Change.feature());
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Loads a player's settings; safe to call from the async pre-login event. Rows of retired ids claimed through
     * {@link SettingOptions#legacy()} move to their new setting (in memory now, in the table with one write).
     * <p>
     * The rows are read in the writer's order and put in place right there, before any later write runs: a write
     * queued before (a staff change just before the player logged in) is in the rows, and one queued after finds the
     * player loaded and updates the cache when it commits. A read that comes after the player left, or after a newer
     * login of the same player started, is dropped; one that comes after the player already joined (a slow database)
     * fills in what they did not change meanwhile.
     */
    public CompletableFuture<Void> load(UUID player) {
        Session session = new Session();
        this.sessions.put(player, session);
        return this.database.write(c -> {
            install(player, session, readRows(c, player));
            return null;
        });
    }

    /** Puts a login read in place, if it still belongs to the newest login of the player (once, if the writer retries). */
    private void install(UUID player, Session session, Map<String, String> rows) {
        if (session.installed) {
            return;
        }
        State current = this.state;
        Map<String, String> loaded = new ConcurrentHashMap<>(rows);
        List<Migration> moves = migrations(rows, current.registry(), id -> {
            Registry.Entry<?> entry = current.registry().entry(id);
            return entry == null ? null : encodedFallback(entry, current);
        });
        for (Migration move : moves) {
            loaded.remove(move.oldId());
            if (move.value() != null) {
                loaded.put(move.id(), move.value());
            }
        }
        Set<String> keep = new HashSet<>();
        boolean[] installed = {false};
        this.sessions.computeIfPresent(player, (id, newest) -> {
            if (newest != session) {
                return newest;
            }
            this.values.compute(id, (key, existing) -> {
                Set<String> touched = this.provisional.remove(key);
                if (existing == null || touched == null) {
                    return loaded;
                }
                // The player joined before this read arrived and got an empty map: keep what they changed since.
                synchronized (existing) {
                    loaded.forEach((setting, value) -> {
                        if (!touched.contains(setting)) {
                            existing.put(setting, value);
                        }
                    });
                }
                keep.addAll(touched);
                return existing;
            });
            installed[0] = true;
            session.installed = true;
            return newest;
        });
        if (installed[0] && !moves.isEmpty()) {
            this.database.write(c -> {
                for (Migration move : moves) {
                    // A write for the player while they were not loaded, queued between the login read and this move,
                    // already stored its value and deleted the old row: that value stands.
                    if (move.value() != null && !keep.contains(move.id()) && exists(c, player, move.oldId())) {
                        upsert(c, player, move.id(), move.value());
                    }
                    delete(c, player, move.oldId());
                }
                return null;
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logger.log(Level.WARNING, "Could not move old settings rows of " + player, error);
                }
            });
        }
    }

    /**
     * The old ids whose rows still stand in for a setting until its player loads ({@link SettingOptions#legacy}); none
     * while the setting is superseded (an old id that is still registered is a setting of its own).
     */
    static List<String> oldIds(Registry.Entry<?> entry) {
        if (entry.superseded() || entry.options().legacy().isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(entry.options().legacy().size());
        for (SettingOptions.Legacy legacy : entry.options().legacy()) {
            ids.add(legacy.oldId());
        }
        return ids;
    }

    private <T> String encodedFallback(Registry.Entry<T> entry, State current) {
        return entry.setting().encode(fallback(entry, current));
    }

    /**
     * Binds the session that just joined to the values its login loaded (call when the player joins), so that its
     * {@link #forget} leaves a newer login of the same player alone.
     */
    public void joined(UUID player) {
        Session session = this.sessions.get(player);
        if (session != null) {
            this.joined.put(player, session);
        }
    }

    /**
     * Forgets a player's values (they quit). When the same player logged in again while this session was still on (a
     * reconnect that replaces it: the new login loads before the old session quits), the new login's values stay.
     */
    public void forget(UUID player) {
        Session session = this.joined.remove(player);
        this.sessions.compute(player, (id, newest) -> {
            if (newest != null && session != null && newest != session) {
                return newest;
            }
            this.values.remove(id);
            this.provisional.remove(id);
            return null;
        });
    }

    /**
     * The rows that move from legacy ids: for each setting with no row of its own, the first legacy id that has a row
     * whose mapped value the setting can read. Superseded settings (their old id is still registered) move nothing.
     *
     * @param defaults the encoded effective default of a setting id (a moved value equal to it needs no row), or null
     */
    static List<Migration> migrations(Map<String, String> rows, Registry registry, Function<String, String> defaults) {
        List<Migration> moves = new ArrayList<>();
        for (Registry.Entry<?> entry : registry.byId().values()) {
            if (entry.superseded() || entry.options().legacy().isEmpty() || rows.containsKey(entry.id())) {
                continue;
            }
            for (SettingOptions.Legacy legacy : entry.options().legacy()) {
                String old = rows.get(legacy.oldId());
                if (old == null) {
                    continue;
                }
                String mapped;
                try {
                    mapped = legacy.mapValue().apply(old);
                } catch (RuntimeException e) {
                    mapped = null;
                }
                String encoded = mapped == null ? null : normalized(entry, mapped);
                if (encoded == null) {
                    continue;
                }
                moves.add(new Migration(entry.id(), encoded.equals(defaults.apply(entry.id())) ? null : encoded, legacy.oldId()));
                break;
            }
        }
        return moves;
    }

    private static <T> String normalized(Registry.Entry<T> entry, String stored) {
        T value = entry.setting().decodeOrNull(stored);
        return value == null ? null : entry.setting().encode(value);
    }

    // ------------------------------------------------------------------ visibility helpers

    /**
     * Whether a player may see and change a setting in the dialog and commands: offered, not hidden by the server,
     * and they have its permission.
     */
    public boolean visible(Registry.Entry<?> entry, Predicate<String> permissions) {
        return allowed(entry, permissions, this.state);
    }

    private static boolean allowed(Registry.Entry<?> entry, Predicate<String> permissions, State current) {
        String permission = entry.setting().permission();
        return entry.offered() && !current.overrides().hidden().contains(entry.id())
            && (permission == null || permissions.test(permission));
    }

    /** The options of a choice a player may pick now (available, and they have the option's permission). */
    public <T> List<Choice.Option<T>> options(Registry.Entry<T> entry, Predicate<String> permissions) {
        List<Choice.Option<T>> list = new ArrayList<>();
        if (entry.setting() instanceof Choice<T> choice) {
            for (Choice.Option<T> option : choice.options()) {
                if (optionOpen(entry, option, permissions)) {
                    list.add(option);
                }
            }
        }
        return list;
    }

    private static <T> boolean optionAllowed(Registry.Entry<T> entry, T value, Predicate<String> permissions) {
        if (!(entry.setting() instanceof Choice<T> choice)) {
            return true;
        }
        Choice.Option<T> option = choice.optionOf(value);
        return option != null && optionOpen(entry, option, permissions);
    }

    private static <T> boolean optionOpen(Registry.Entry<T> entry, Choice.Option<T> option, Predicate<String> permissions) {
        return entry.options().isOptionAvailable(option.id()) && (option.permission() == null || permissions.test(option.permission()));
    }

    // ------------------------------------------------------------------ internals

    @SuppressWarnings("unchecked")
    private static <T> Registry.Entry<T> typed(Registry registry, PlayerSetting<T> setting) {
        Registry.Entry<?> entry = registry.entry(setting.id());
        boolean same = entry != null && (entry.setting() == setting || entry.setting().equals(setting));
        return same ? (Registry.Entry<T>) entry : null;
    }

    private String storedValue(UUID player, String id) {
        Map<String, String> map = this.values.get(player);
        return map == null ? null : map.get(id);
    }

    /**
     * The value before availability: the lock, else the stored row (ignored while the server hides the setting), else
     * the default.
     */
    private static <T> T value(Registry.Entry<T> entry, String stored, State current) {
        PlayerSetting<T> setting = entry.setting();
        Object locked = current.locked().get(setting.id());
        if (locked != null) {
            return setting.cast(locked);
        }
        boolean hidden = current.overrides().hidden().contains(setting.id());
        T decoded = stored == null || hidden ? null : setting.decodeOrNull(stored);
        return decoded != null ? decoded : fallback(entry, current);
    }

    private static <T> T resolve(Registry.Entry<T> entry, String stored, State current) {
        return available(entry, value(entry, stored, current), current, permission -> true);
    }

    /** The server's default, else the code default. */
    private static <T> T fallback(Registry.Entry<T> entry, State current) {
        Object configured = current.defaults().get(entry.id());
        return configured != null ? entry.setting().cast(configured) : entry.setting().defaultValue();
    }

    /**
     * A choice value with availability applied: an option that is not open reads as its {@code unavailableAs}, else
     * the default, else the code default, else the first open option.
     */
    private static <T> T available(Registry.Entry<T> entry, T value, State current, Predicate<String> permissions) {
        if (!(entry.setting() instanceof Choice<T> choice)) {
            return value;
        }
        Choice.Option<T> option = choice.optionOf(value);
        if (option == null || optionOpen(entry, option, permissions)) {
            return value;
        }
        List<Choice.Option<T>> candidates = new ArrayList<>(3);
        if (option.unavailableAs() != null) {
            candidates.add(choice.option(option.unavailableAs()));
        }
        candidates.add(choice.optionOf(fallback(entry, current)));
        candidates.add(choice.optionOf(choice.defaultValue()));
        candidates.addAll(choice.options());
        for (Choice.Option<T> candidate : candidates) {
            if (candidate != null && optionOpen(entry, candidate, permissions)) {
                return candidate.value();
            }
        }
        return value;
    }

    /**
     * The loaded player's map; for an online player whose login read failed or has not arrived, an empty one whose
     * changes a late read won't undo. Null for players who are not loaded.
     */
    private Map<String, String> cache(UUID player) {
        Map<String, String> map = this.values.get(player);
        if (map == null && this.players.apply(player) != null) {
            map = this.values.computeIfAbsent(player, k -> {
                this.provisional.put(k, ConcurrentHashMap.newKeySet());
                return new ConcurrentHashMap<>();
            });
        }
        return map;
    }

    /** Records a change to a map made before the player's login read arrived (call under the map's lock). */
    private void touch(UUID player, String id) {
        Set<String> touched = this.provisional.get(player);
        if (touched != null) {
            touched.add(id);
        }
    }

    /**
     * After a write for a player who was not loaded committed: a login whose read was queued before it (and so put the
     * old rows in place) gets the change too ({@code changes}: id to the stored value, null for a deleted row).
     */
    private void applyCommitted(UUID player, Map<String, String> changes) {
        Map<String, String> loaded = this.values.get(player);
        if (loaded == null) {
            return;
        }
        synchronized (loaded) {
            changes.forEach((id, value) -> {
                if (value == null) {
                    loaded.remove(id);
                } else {
                    loaded.put(id, value);
                }
                touch(player, id);
            });
        }
    }

    private <T> SetResult store(UUID player, Registry.Entry<T> entry, T value, Change change) {
        PlayerSetting<T> setting = entry.setting();
        State current = this.state;
        Map<String, String> map = cache(player);
        T old = map == null ? null : resolve(entry, map.get(setting.id()), current);
        String encoded = setting.same(fallback(entry, current), value) ? null : setting.encode(value);
        if (map != null && setting.same(old, value)) {
            if (this.provisional.containsKey(player)) {
                // The player's login read has not arrived, so the table may still hold another value: store it anyway.
                putCached(player, map, setting.id(), encoded);
            }
            return SetResult.UNCHANGED;
        }
        boolean allowed = this.listener.changing(new Pending(player, entry, old == null ? null : setting.encode(old),
            setting.encode(value), change));
        if (!allowed && change.cause().cancellable()) {
            return SetResult.CANCELLED;
        }
        if (map != null) {
            putCached(player, map, setting.id(), encoded);
            hook(entry, player, old, value);
        } else {
            // Rows under the setting's old ids go in the same write, or the next login would move them over this value.
            List<String> oldIds = oldIds(entry);
            CompletableFuture<Void> written;
            synchronized (this.offlineWrites) {
                written = write(player, setting.id(), encoded, oldIds);
            }
            // A login may have read the rows before this write committed: bring its cache up to date.
            Map<String, String> committed = new HashMap<>();
            oldIds.forEach(id -> committed.put(id, null));
            committed.put(setting.id(), encoded);
            written.thenRun(() -> applyCommitted(player, committed));
        }
        return SetResult.CHANGED;
    }

    /** Changes a loaded player's cached value and queues the write, together (null deletes the row). */
    private void putCached(UUID player, Map<String, String> map, String id, String encoded) {
        synchronized (map) {
            if (encoded == null) {
                map.remove(id);
            } else {
                map.put(id, encoded);
            }
            touch(player, id);
            write(player, id, encoded);
        }
    }

    private <T> void hook(Registry.Entry<T> entry, UUID uuid, T old, T now) {
        SettingOptions.ChangeHook<T> hook = entry.options().onChange();
        if (hook == null || entry.options().apply() != SettingOptions.Apply.INSTANT) {
            return;
        }
        Player player = this.players.apply(uuid);
        if (player == null) {
            return;
        }
        T before = old == null ? now : old;
        Runnable run = () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                hook.changed(player, before, now);
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "The change hook of setting " + entry.id() + " failed for " + player.getName(), t);
            }
        };
        if (this.scheduler == null || this.scheduler.owns(player)) {
            run.run();
        } else {
            this.scheduler.entity(player, run, null);
        }
    }

    private CompletableFuture<Void> write(UUID player, String id, String value) {
        return write(player, id, value, List.of());
    }

    /** Stores one value (null deletes the row) and deletes the rows of {@code alsoDelete}, in one write. */
    private CompletableFuture<Void> write(UUID player, String id, String value, List<String> alsoDelete) {
        return this.database.<Void>write(c -> {
            if (value == null) {
                delete(c, player, id);
            } else {
                upsert(c, player, id, value);
            }
            for (String old : alsoDelete) {
                delete(c, player, old);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not store setting " + id + " of " + player, error);
            }
        });
    }

    private CompletableFuture<Void> deleteRows(UUID player, List<String> ids) {
        return this.database.<Void>write(c -> {
            for (String id : ids) {
                delete(c, player, id);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not reset settings of " + player, error);
            }
        });
    }

    private void upsert(java.sql.Connection c, UUID player, String id, String value) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("settings",
            new String[] {"uuid", "setting"}, new String[] {"value"}))) {
            ps.setString(1, player.toString());
            ps.setString(2, id);
            ps.setString(3, value);
            ps.executeUpdate();
        }
    }

    private static boolean exists(java.sql.Connection c, UUID player, String id) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM settings WHERE uuid = ? AND setting = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void delete(java.sql.Connection c, UUID player, String id) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM settings WHERE uuid = ? AND setting = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static Map<String, String> readRows(java.sql.Connection c, UUID player) throws java.sql.SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT setting, value FROM settings WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return map;
    }

    /** The ids of every loaded player (online or logging in). */
    public Set<UUID> loadedPlayers() {
        return Set.copyOf(this.values.keySet());
    }
}
