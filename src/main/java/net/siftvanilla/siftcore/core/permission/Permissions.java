package net.siftvanilla.siftcore.core.permission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * Registers SiftCore's permission nodes with the server at runtime (so each feature declares its own nodes next
 * to its code) and keeps them for documentation and {@code /sift permissions}.
 */
public final class Permissions {

    /** A declared node. */
    public record Node(String name, String description, PermissionDefault defaultValue) {
    }

    private final Map<String, Node> nodes = new TreeMap<>();
    private final Map<String, Map<String, Boolean>> children = new TreeMap<>();

    /** Declares a node; call from a feature constructor. Players get it by default when {@code everyone}. */
    public synchronized String declare(String name, String description, boolean everyone) {
        return declare(name, description, everyone ? PermissionDefault.TRUE : PermissionDefault.OP);
    }

    public synchronized String declare(String name, String description, PermissionDefault defaultValue) {
        Node previous = this.nodes.putIfAbsent(name, new Node(name, description, defaultValue));
        if (previous != null && previous.defaultValue() != defaultValue) {
            throw new IllegalStateException("Permission " + name + " is declared twice with different defaults");
        }
        return name;
    }

    /**
     * Makes a declared node grant other nodes too (Bukkit child permissions), for nodes that include lower tiers
     * ({@code siftcore.tags.tycoon} includes {@code siftcore.tags.baron}) and wildcards such as
     * {@code siftcore.killeffect.*}. LuckPerms follows registered children as well, so the nodes work the same with
     * or without it. Call from a feature constructor, after declaring {@code parent}.
     */
    public synchronized void children(String parent, Map<String, Boolean> granted) {
        if (!this.nodes.containsKey(parent)) {
            throw new IllegalStateException("Permission " + parent + " must be declared before its children");
        }
        this.children.computeIfAbsent(parent, k -> new LinkedHashMap<>()).putAll(granted);
    }

    /** The nodes a declared node grants as well (empty for most). */
    public synchronized Map<String, Boolean> childrenOf(String parent) {
        Map<String, Boolean> granted = this.children.get(parent);
        return granted == null ? Map.of() : Map.copyOf(granted);
    }

    /** Registers every declared node with the server (skips nodes another source already registered). */
    public synchronized void install() {
        var manager = Bukkit.getPluginManager();
        for (Node node : this.nodes.values()) {
            if (manager.getPermission(node.name()) == null) {
                Map<String, Boolean> granted = this.children.get(node.name());
                manager.addPermission(granted == null
                    ? new Permission(node.name(), node.description(), node.defaultValue())
                    : new Permission(node.name(), node.description(), node.defaultValue(), new LinkedHashMap<>(granted)));
            }
        }
    }

    /** Removes the nodes again (on disable). */
    public synchronized void uninstall() {
        var manager = Bukkit.getPluginManager();
        for (Node node : this.nodes.values()) {
            manager.removePermission(node.name());
        }
    }

    public synchronized Map<String, Node> all() {
        return Map.copyOf(this.nodes);
    }
}
