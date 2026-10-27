package net.siftvanilla.siftcore.ui.hub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** The main menu's entries. Features register theirs at startup; the hub feature renders them. */
public final class HubRegistry {

    private final Map<String, HubEntry> entries = new ConcurrentHashMap<>();

    public void register(HubEntry entry) {
        if (this.entries.putIfAbsent(entry.id(), entry) != null) {
            throw new IllegalStateException("Hub entry " + entry.id() + " is registered twice");
        }
    }

    public HubEntry get(String id) {
        return this.entries.get(id);
    }

    /** Entries the player may see, in order. */
    public List<HubEntry> visibleTo(Player player) {
        List<HubEntry> list = new ArrayList<>();
        for (HubEntry entry : this.entries.values()) {
            if (entry.permission() == null || player.hasPermission(entry.permission())) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparingInt(HubEntry::order).thenComparing(HubEntry::id));
        return list;
    }

    public List<HubEntry> all() {
        List<HubEntry> list = new ArrayList<>(this.entries.values());
        list.sort(Comparator.comparingInt(HubEntry::order).thenComparing(HubEntry::id));
        return list;
    }
}
