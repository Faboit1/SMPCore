package net.siftvanilla.siftcore.core.player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Per-player toggles. Loaded when a player logs in (off-thread, before they enter the world), changed only by the
 * player, written through immediately and forgotten when they quit. Unknown players read as defaults.
 * <p>
 * Toggles can be registered into a {@link SettingCategory}, which groups them in the settings dialog.
 */
public final class PlayerSettings {

    private final Database database;
    private final Map<String, Toggle> toggles = new LinkedHashMap<>();
    private final Map<String, SettingCategory> categories = new LinkedHashMap<>();
    /** Toggle id to the id of its category; toggles without one are not listed. */
    private final Map<String, String> toggleCategories = new HashMap<>();
    private final Map<UUID, Map<String, String>> values = new ConcurrentHashMap<>();

    public PlayerSettings(Database database) {
        this.database = database;
    }

    /** Registers a toggle; call during startup. */
    public synchronized void register(Toggle toggle) {
        if (this.toggles.putIfAbsent(toggle.id(), toggle) != null) {
            throw new IllegalStateException("Toggle " + toggle.id() + " is registered twice");
        }
    }

    /**
     * Registers a toggle in a category (registering the category too, the first time); call during startup. A
     * category id can only stand for one category: registering a different category under a used id fails.
     */
    public synchronized void register(SettingCategory category, Toggle toggle) {
        SettingCategory known = this.categories.get(category.id());
        if (known != null && !known.equals(category)) {
            throw new IllegalStateException("Setting category " + category.id() + " is registered twice with different text");
        }
        register(toggle);
        this.categories.putIfAbsent(category.id(), category);
        this.toggleCategories.put(toggle.id(), category.id());
    }

    /** Every category that holds a toggle, lowest {@link SettingCategory#order()} first, then by id. */
    public synchronized List<SettingCategory> categories() {
        List<SettingCategory> list = new ArrayList<>(this.categories.values());
        list.sort(Comparator.comparingInt(SettingCategory::order).thenComparing(SettingCategory::id));
        return list;
    }

    /** The category a toggle was registered in, or null when it has none. */
    public synchronized SettingCategory category(Toggle toggle) {
        String id = this.toggleCategories.get(toggle.id());
        return id == null ? null : this.categories.get(id);
    }

    public synchronized List<Toggle> toggles() {
        return List.copyOf(this.toggles.values());
    }

    public synchronized Toggle toggle(String id) {
        return this.toggles.get(id);
    }

    /** Loads a player's settings; safe to call from the async pre-login event. */
    public CompletableFuture<Void> load(UUID player) {
        return this.database.read(c -> {
            Map<String, String> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT setting, value FROM settings WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return map;
        }).thenAccept(map -> this.values.put(player, new ConcurrentHashMap<>(map)));
    }

    public void forget(UUID player) {
        this.values.remove(player);
    }

    public boolean enabled(UUID player, Toggle toggle) {
        Map<String, String> map = this.values.get(player);
        String value = map == null ? null : map.get(toggle.id());
        return value == null ? toggle.defaultOn() : Boolean.parseBoolean(value);
    }

    public void set(UUID player, Toggle toggle, boolean on) {
        setRaw(player, toggle.id(), Boolean.toString(on));
    }

    /** Free-form per-player value (e.g. a chosen sort order). */
    public String raw(UUID player, String key, String fallback) {
        Map<String, String> map = this.values.get(player);
        String value = map == null ? null : map.get(key);
        return value == null ? fallback : value;
    }

    public void setRaw(UUID player, String key, String value) {
        if (key.length() > 32 || value.length() > 64) {
            throw new IllegalArgumentException("Setting too long");
        }
        this.values.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(key, value);
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("settings",
                new String[] {"uuid", "setting"}, new String[] {"value"}))) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                ps.setString(3, value);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All toggles a player may see with their current values, in registration order. */
    public List<Map.Entry<Toggle, Boolean>> view(UUID player, java.util.function.Predicate<String> hasPermission) {
        List<Map.Entry<Toggle, Boolean>> result = new ArrayList<>();
        for (Toggle toggle : toggles()) {
            if (toggle.permission() == null || hasPermission.test(toggle.permission())) {
                result.add(Map.entry(toggle, enabled(player, toggle)));
            }
        }
        return result;
    }
}
