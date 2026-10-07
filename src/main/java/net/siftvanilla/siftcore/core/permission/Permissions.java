package net.siftvanilla.siftcore.core.permission;

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

    /** Registers every declared node with the server (skips nodes another source already registered). */
    public synchronized void install() {
        var manager = Bukkit.getPluginManager();
        for (Node node : this.nodes.values()) {
            if (manager.getPermission(node.name()) == null) {
                manager.addPermission(new Permission(node.name(), node.description(), node.defaultValue()));
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
